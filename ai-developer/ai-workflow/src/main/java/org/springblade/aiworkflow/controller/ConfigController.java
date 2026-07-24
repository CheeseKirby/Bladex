package org.springblade.aiworkflow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.common.ApiResponse;
import org.springblade.aiworkflow.config.AiWorkflowProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/**
 * 运行时配置 — Part A BFF 在配置 LLM 后通过 PUT 同步到此处。
 *
 * <p>变更只在进程内生效;重启回退到 application.yml + 环境变量。
 *
 * <p>安全策略:
 * - 读取 (GET) 不强制 token;写入 (PUT) 要求 {@code X-Admin-Token} 头匹配
 *   {@code ai-workflow.admin.token} 配置(若未配置则**关闭**写入,只读 GET 仍可用);
 * - baseUrl 必须是 http/https + 非空 host;
 * - 禁止把 baseUrl 指向 loopback 之外的内网/链路本地地址,防止 SSRF 把 LLM 凭据回放给内网服务。
 *   开发期允许 localhost / 127.0.0.1 / docker 容器名(host.docker.internal)。
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
@Tag(name = "运行时配置", description = "LLM 等运行时配置(从 Part A 同步)")
public class ConfigController {

    private final AiWorkflowProperties properties;
    private final AdminTokenGuard guard;

    public ConfigController(AiWorkflowProperties properties, AdminTokenGuard guard) {
        this.properties = properties;
        this.guard = guard;
    }

    @GetMapping("/llm")
    @Operation(summary = "查看 LLM 配置(脱敏)")
    public ApiResponse<LlmConfigView> getLlm() {
        AiWorkflowProperties.LlmProperties cfg = properties.getLlm();
        LlmConfigView view = new LlmConfigView();
        view.setBaseUrl(cfg.getBaseUrl());
        view.setModel(cfg.getModel());
        view.setAnthropicVersion(cfg.getAnthropicVersion());
        view.setMaxTokens(cfg.getMaxTokens());
        view.setAuthToken(mask(cfg.getAuthToken()));
        view.setApiKey(mask(cfg.getApiKey()));
        view.setHasAuthToken(cfg.getAuthToken() != null && !cfg.getAuthToken().isBlank());
        view.setHasApiKey(cfg.getApiKey() != null && !cfg.getApiKey().isBlank());
        return ApiResponse.ok(view);
    }

    @PutMapping("/llm")
    @Operation(summary = "更新 LLM 配置(运行时,部分字段)— 需要 X-Admin-Token")
    public ApiResponse<LlmConfigView> updateLlm(@RequestBody LlmConfigPatch patch,
                                                HttpServletRequest req) {
        guard.requireAdmin(req);

        AiWorkflowProperties.LlmProperties cfg = properties.getLlm();

        if (patch.getBaseUrl() != null && !patch.getBaseUrl().isBlank()) {
            String newBase = patch.getBaseUrl().trim();
            if (!isAllowedBaseUrl(newBase)) {
                return ApiResponse.fail(400,
                        "baseUrl 不允许 — 必须 http/https,且不能指向内网/元数据地址: " + newBase);
            }
            cfg.setBaseUrl(newBase);
        }
        if (patch.getModel() != null && !patch.getModel().isBlank()) cfg.setModel(patch.getModel().trim());
        if (patch.getAnthropicVersion() != null && !patch.getAnthropicVersion().isBlank()) {
            cfg.setAnthropicVersion(patch.getAnthropicVersion().trim());
        }
        if (patch.getMaxTokens() != null && patch.getMaxTokens() > 0) cfg.setMaxTokens(patch.getMaxTokens());

        // 鉴权字段: 含 '*' 视为脱敏值,跳过;空串视为清空;明文视为新值
        if ("".equals(patch.getAuthToken())) {
            cfg.setAuthToken("");
        } else if (patch.getAuthToken() != null && !patch.getAuthToken().contains("*")) {
            cfg.setAuthToken(patch.getAuthToken());
        }
        if ("".equals(patch.getApiKey())) {
            cfg.setApiKey("");
        } else if (patch.getApiKey() != null && !patch.getApiKey().contains("*")) {
            cfg.setApiKey(patch.getApiKey());
        }

        log.info("Part B LLM 配置已更新: base={}, model={}, auth={}",
                cfg.getBaseUrl(), cfg.getModel(),
                (cfg.getAuthToken() != null && !cfg.getAuthToken().isBlank()) ? "bearer"
                        : (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) ? "x-api-key" : "NONE");
        return getLlm();
    }

    private static String mask(String s) {
        if (s == null || s.isEmpty()) return "";
        // 全部用 *,只保留末 4 位用于人工辨识 — 避免长 token 泄露 10 个明文字符
        if (s.length() <= 8) return "*".repeat(s.length());
        return "*".repeat(s.length() - 4) + s.substring(s.length() - 4);
    }

    /**
     * 允许的 LLM baseUrl 校验:
     * - 必须 http / https
     * - 必须有 host
     * - 禁止 169.254.x.x (云元数据)、127.x、10.x、172.16-31.x、192.168.x、::1、fe80::
     *   开发期 host = "localhost" / "host.docker.internal" 例外放行
     */
    public static boolean isAllowedBaseUrl(String url) {
        URL u;
        try {
            u = new URL(url);
        } catch (MalformedURLException e) {
            return false;
        }
        String scheme = u.getProtocol();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        String host = u.getHost();
        if (host == null || host.isBlank()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.equals("host.docker.internal")) return true;
        // 明确黑名单
        if (h.startsWith("169.254.")) return false;          // link-local / 云元数据
        if (h.startsWith("127.")) return false;              // loopback
        if (h.startsWith("10.")) return false;
        if (h.startsWith("192.168.")) return false;
        if (h.startsWith("172.")) {
            // 172.16-31 是私有
            int second = parseSecondOctet(h);
            if (second >= 16 && second <= 31) return false;
        }
        if (h.equals("::1") || h.startsWith("fe80:")) return false;
        return true;
    }

    private static int parseSecondOctet(String h) {
        try {
            String[] parts = h.split("\\.");
            if (parts.length >= 2) return Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    @Data
    public static class LlmConfigPatch {
        private String baseUrl;
        private String model;
        private String authToken;
        private String apiKey;
        private String anthropicVersion;
        private Integer maxTokens;
    }

    @Data
    public static class LlmConfigView {
        private String baseUrl;
        private String model;
        private String authToken;
        private String apiKey;
        private String anthropicVersion;
        private int maxTokens;
        private boolean hasAuthToken;
        private boolean hasApiKey;
    }

    /**
     * Admin token 校验。
     * - {@code ai-workflow.admin.token} 在 application.yml / env 中配置时,要求请求头 X-Admin-Token 精确匹配;
     * - 未配置 token 时,远程写入端点直接 403 拒绝,只允许本地回环(127.0.0.1 / ::1)的请求通过。
     */
    @Component
    public static class AdminTokenGuard {

        private final String adminToken;

        public AdminTokenGuard(@Value("${ai-workflow.admin.token:}") String adminToken) {
            this.adminToken = adminToken == null ? "" : adminToken.trim();
            if (this.adminToken.isEmpty()) {
                log.warn("ai-workflow.admin.token 未配置 — /api/config 的写入端点将只接受本地回环请求");
            }
        }

        /** Whether the admin token is configured for privileged reference-project management. */
        public boolean isTokenConfigured() {
            return !adminToken.isEmpty();
        }

        public void requireAdmin(HttpServletRequest req) {
            String token = req.getHeader("X-Admin-Token");
            if (!adminToken.isEmpty()) {
                if (adminToken.equals(token)) return;
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "X-Admin-Token 不匹配");
            }
            // 未配置 token: 只放行本地回环
            String remote = req.getRemoteAddr();
            if (remote == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无法解析远程地址");
            }
            if (remote.equals("127.0.0.1") || remote.equals("0:0:0:0:0:0:0:1") || remote.equals("::1")) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ai-workflow.admin.token 未配置,/api/config 写入端点拒绝非本地请求 (remoteAddr=" + remote + ")");
        }
    }
}
