package org.springblade.aiworkflow.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springblade.aiworkflow.config.AiWorkflowProperties;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * LLM API客户端
 *
 * <p>通过 OkHttp 调用 Anthropic Messages API,与 ccswitch / claude-code 一致:
 * <ul>
 *   <li>base URL 由 {@code ai-workflow.llm.base-url} 控制(默认 https://api.anthropic.com)</li>
 *   <li>优先使用 {@code ANTHROPIC_AUTH_TOKEN} -> {@code Authorization: Bearer ...}</li>
 *   <li>无 token 时回退到 {@code ANTHROPIC_API_KEY} -> {@code x-api-key: ...}</li>
 * </ul>
 *
 * @author AI Developer
 */
@Slf4j
public class LlmClient {

    private static final String MESSAGES_PATH = "/v1/messages";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiWorkflowProperties.LlmProperties config;

    public LlmClient(OkHttpClient httpClient, ObjectMapper objectMapper,
                     AiWorkflowProperties.LlmProperties config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.config = config;
        log.info("LlmClient 初始化: baseUrl={}, model={}, auth={}",
                resolveBaseUrl(), config.getModel(), authMode());
    }

    /** 每次调用都重新拼装,允许运行时通过 ConfigController 修改 base-url */
    private String resolveEndpoint() {
        return resolveBaseUrl() + MESSAGES_PATH;
    }

    private String resolveBaseUrl() {
        String base = config.getBaseUrl() == null ? "https://api.anthropic.com" : config.getBaseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private String authMode() {
        boolean hasToken = config.getAuthToken() != null && !config.getAuthToken().isBlank();
        boolean hasKey = config.getApiKey() != null && !config.getApiKey().isBlank();
        return hasToken ? "bearer" : hasKey ? "x-api-key" : "NONE (LLM 调用会失败)";
    }

    /** 网络重试次数(含首次),对齐 PartACallback 的 3 次退避策略 */
    private static final int NETWORK_MAX_RETRIES = 3;
    /** 网络重试初始退避(ms),指数退避 1s/2s/4s */
    private static final long NETWORK_BACKOFF_MS = 1000;

    /**
     * 调用LLM生成文本 - 对瞬时故障(5xx/IOException/超时)指数退避重试,4xx 不重试。
     *
     * <p>LLM 是核心依赖,此前零重试:单次网络抖动即致原子任务失败 -> 子方案 FAILED。
     * max_tokens 减半仍由 {@link #generateWithMaxTokens} 内部递归处理。
     */
    public String generate(String systemPrompt, String userPrompt) {
        return generateWithRetry(systemPrompt, userPrompt, config.getMaxTokens());
    }

    private String generateWithRetry(String systemPrompt, String userPrompt, int maxTokens) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= NETWORK_MAX_RETRIES; attempt++) {
            try {
                return generateWithMaxTokens(systemPrompt, userPrompt, maxTokens, true);
            } catch (RuntimeException e) {
                if (!isRetryable(e)) throw e;
                last = e;
                log.warn("LLM调用可重试异常(第{}/{}次): {}", attempt, NETWORK_MAX_RETRIES, e.getMessage());
                if (attempt < NETWORK_MAX_RETRIES) {
                    try {
                        Thread.sleep(NETWORK_BACKOFF_MS * (long) Math.pow(2, attempt - 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("LLM调用被中断", ie);
                    }
                }
            }
        }
        throw last;
    }

    /** 可重试判定: 网络异常(IOException cause)或 5xx;4xx(含 max_tokens 400 已减半处理)不重试 */
    private boolean isRetryable(RuntimeException e) {
        if (e.getCause() instanceof IOException) return true;
        String msg = e.getMessage();
        return msg != null && msg.contains("HTTP 5");
    }

    /**
     * 带 max_tokens 调整的生成 — 阶段2增强兜底。
     *
     * <p>若首次调用因 max_tokens 过大返回 400(模型上限 < 配置值),自动减半重试一次,
     * 避免整个生成流水线因模型 token 上限不匹配而全线失败。allowHalve=false 时不再降级(防无限递归)。
     */
    private String generateWithMaxTokens(String systemPrompt, String userPrompt, int maxTokens, boolean allowHalve) {
        try {
            Map<String, Object> requestBody = buildRequestBody(systemPrompt, userPrompt, maxTokens);
            String json = objectMapper.writeValueAsString(requestBody);

            Request.Builder builder = new Request.Builder()
                    .url(resolveEndpoint())
                    .addHeader("anthropic-version", config.getAnthropicVersion())
                    .addHeader("content-type", "application/json")
                    .post(RequestBody.create(json, MediaType.parse("application/json")));

            // 鉴权头优先级: Bearer Token > x-api-key
            if (config.getAuthToken() != null && !config.getAuthToken().isBlank()) {
                builder.addHeader("authorization", "Bearer " + config.getAuthToken());
            } else if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                builder.addHeader("x-api-key", config.getApiKey());
            }

            Request request = builder.build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = readBodyLimited(response, 1000);
                    // max_tokens 过大导致 400 时,自动减半重试一次(兜底模型上限不匹配)
                    if (response.code() == 400 && allowHalve && maxTokens > 1024
                            && errorBody != null && errorBody.toLowerCase().contains("max_tokens")) {
                        int halved = maxTokens / 2;
                        log.warn("LLM 调用 400 (max_tokens={} 过大?), 减半重试 max_tokens={}", maxTokens, halved);
                        return generateWithMaxTokens(systemPrompt, userPrompt, halved, false);
                    }
                    log.error("LLM调用失败: HTTP {} - {}", response.code(), errorBody);
                    throw new RuntimeException("LLM调用失败: HTTP " + response.code());
                }

                String responseBody = response.body() == null ? "" : response.body().string();
                return extractTextContent(responseBody);
            }
        } catch (IOException e) {
            log.error("LLM调用异常", e);
            throw new RuntimeException("LLM调用异常: " + e.getMessage(), e);
        }
    }

    /** 读取最多 maxChars 字符的 body,避免把上游大块 echo 写满日志 */
    private static String readBodyLimited(Response response, int maxChars) {
        try {
            if (response.body() == null) return "";
            String s = response.body().string();
            if (s == null) return "";
            return s.length() <= maxChars ? s : s.substring(0, maxChars) + "...[truncated " + (s.length() - maxChars) + " chars]";
        } catch (IOException e) {
            return "(failed to read body: " + e.getMessage() + ")";
        }
    }

    /**
     * 调用LLM生成JSON结构化输出
     */
    public <T> T generateStructured(String systemPrompt, String userPrompt, Class<T> responseType) {
        String raw = generate(systemPrompt, userPrompt);
        // 提取JSON部分（LLM可能用markdown包裹）
        String json = extractJson(raw);
        try {
            return objectMapper.readValue(json, responseType);
        } catch (JsonProcessingException e) {
            log.error("解析LLM JSON响应失败: {}", raw, e);
            throw new RuntimeException("解析LLM响应失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建Anthropic Messages API请求体
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt, int maxTokens) {
        return Map.of(
                "model", config.getModel(),
                "max_tokens", maxTokens,
                "temperature", config.getTemperature(),
                "system", systemPrompt,
                "messages", List.of(
                        Map.of("role", "user", "content", userPrompt)
                )
        );
    }

    /**
     * 从Anthropic响应中提取文本内容。
     *
     * <p>content 是 block 数组,可能含多种 type:
     * <ul>
     *   <li>{@code text} — 实际文本输出,需提取;</li>
     *   <li>{@code thinking} — 推理过程(GLM/Claude 偶发返回,且可能排在 text 之前),无 text 字段,需跳过;</li>
     *   <li>其它(如 tool_use)— 非 LLM 生成代码场景,跳过。</li>
     * </ul>
     *
     * <p>关键:不能只取 content[0]。火山方舟 glm-latest 会偶发返回
     * {@code [thinking, text]} 结构,若取首位 thinking block 会得到空串,
     * 导致下游 "LLM 响应未包含可识别的代码块 (rawPreview=)" 报错。
     * 正确做法是遍历所有 block,拼接全部 type=text 的内容。
     */
    @SuppressWarnings("unchecked")
    private String extractTextContent(String responseBody) throws JsonProcessingException {
        Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : content) {
            if ("text".equals(block.get("type"))) {
                Object text = block.get("text");
                if (text instanceof String s && !s.isEmpty()) {
                    sb.append(s);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 从LLM响应中提取JSON（处理markdown代码块包裹）
     */
    private String extractJson(String raw) {
        // 尝试提取```json ... ```包裹的内容
        int jsonStart = raw.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = raw.indexOf('\n', jsonStart) + 1;
            int jsonEnd = raw.indexOf("```", contentStart);
            if (jsonEnd > contentStart) {
                return raw.substring(contentStart, jsonEnd).trim();
            }
        }
        // 尝试提取``` ... ```包裹的内容
        int codeStart = raw.indexOf("```");
        if (codeStart >= 0) {
            int contentStart = raw.indexOf('\n', codeStart) + 1;
            int codeEnd = raw.indexOf("```", contentStart);
            if (codeEnd > contentStart) {
                return raw.substring(contentStart, codeEnd).trim();
            }
        }
        // 直接返回原始内容
        return raw.trim();
    }
}
