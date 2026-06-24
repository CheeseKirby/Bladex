package org.springblade.aiworkflow.agent;

import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.enums.TaskType;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 代码生成路由器
 *
 * <p>每个 AtomicTask 只对应一个目标文件。LLM 一次只生成一个类/接口,
 * router 负责从 LLM 响应中提取代码。
 *
 * <p>提取策略(LLM 输出格式不稳定,需多层兜底):
 * <ol>
 *   <li>```lang ... ``` 代码块 — 最规范,优先取;</li>
 *   <li>裸 Java:任意位置出现 {@code package / import / public class|interface|enum|record};</li>
 *   <li>裸 SQL:任意位置出现 {@code CREATE TABLE / ALTER TABLE / DROP TABLE / INSERT INTO};</li>
 *   <li>以上都不满足 → 返回 null,调用方记录失败(并把 raw 前 300 字符落日志便于排查)。</li>
 * </ol>
 *
 * <p>关键变化:之前用 {@code ^} 行首锚定 + 仅识别首个代码块,对 GLM 的不稳定输出
 * (有时 ```sql 包裹,有时裸 SQL 以 DROP TABLE 开头)兼容性差。现在改为"任意位置出现关键字"
 * 的宽松匹配,同时仍保留代码块优先,避免把 Markdown 解释写进 .java。
 *
 * @author AI Developer
 */
@Slf4j
public class BladeCodeGenRouter {

    /** LLM 响应中提取首个 ```lang ... ``` 代码块 */
    private static final Pattern CODE_BLOCK = Pattern.compile(
            "```(?:java|sql|xml|yaml|yml|properties)?\\s*\\n([\\s\\S]*?)```");

    /** 裸 Java 源码检测 — 任意位置出现以下任一行即视为 Java */
    private static final Pattern JAVA_HINT = Pattern.compile(
            "(?m)^\\s*(package\\s+[\\w.]+\\s*;|import\\s+\\w|public\\s+(?:class|interface|enum|record)\\s+\\w+|@\\w+(?:\\(|\\s|$))");

    /** 裸 SQL 源码检测 — 任意位置出现以下任一关键字即视为 SQL */
    private static final Pattern SQL_HINT = Pattern.compile(
            "(?i)\\b(?:CREATE\\s+TABLE|ALTER\\s+TABLE|DROP\\s+TABLE|INSERT\\s+INTO|CREATE\\s+INDEX|CREATE\\s+DATABASE)\\b");

    /** 裸 XML 源码检测 — Mapper XML 文件检测 */
    private static final Pattern XML_HINT = Pattern.compile(
            "(?i)<\\?xml|<mapper[\\s>]|<resultMap[\\s>]|<select[\\s>]");

    private final LlmClient llmClient;
    private final PromptBuilder promptBuilder;

    public BladeCodeGenRouter(LlmClient llmClient, PromptBuilder promptBuilder) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
    }

    public boolean shouldUseTemplateGenerator(AtomicTask task) {
        return task.getType() == TaskType.STANDARD_CRUD_ENTITY
                || task.getType() == TaskType.STANDARD_CRUD_CONTROLLER
                || task.getType() == TaskType.STANDARD_CRUD_SERVICE;
    }

    public GenerationResult generate(AtomicTask task) {
        return generateWithLLM(task);
    }

    private GenerationResult generateWithLLM(AtomicTask task) {
        try {
            Prompt prompt = promptBuilder.build(task);
            log.info("LLM生成: type={}, targetPath={}", task.getType(), task.getTargetPath());

            String rawResponse = llmClient.generate(prompt.getSystemPrompt(), prompt.getUserPrompt());
            String code = extractCode(rawResponse, task);
            if (code == null) {
                // 把 raw 前 300 字符记到日志,便于诊断 LLM 到底返回了什么
                String preview = rawResponse == null ? "(null)" : rawResponse.substring(0, Math.min(300, rawResponse.length()));
                log.error("LLM 响应未包含可用代码: type={}, targetPath={}, rawPreview={}",
                        task.getType(), task.getTargetPath(), preview);
                return GenerationResult.failure("LLM",
                        "LLM 响应未包含可识别的代码块或 Java/SQL 源码 (rawPreview=" + preview + ")");
            }
            GeneratedFile file = GeneratedFile.create(task.getType(), task.getTargetPath(), code);
            task.setExpectedContent(code);
            return GenerationResult.llmSuccess(List.of(file));
        } catch (Exception e) {
            log.error("LLM生成失败: {}", task.getTargetPath(), e);
            return GenerationResult.failure("LLM", e.getMessage());
        }
    }

    public GenerationResult fix(ValidationResult validation, GeneratedFile file, AtomicTask task) {
        try {
            Prompt fixPrompt = promptBuilder.buildFixPrompt(validation, file.getContent(), task);
            log.info("LLM修复: {}", file.getFilePath());
            String rawResponse = llmClient.generate(fixPrompt.getSystemPrompt(), fixPrompt.getUserPrompt());
            String fixedCode = extractCode(rawResponse, task);
            if (fixedCode == null) {
                String preview = rawResponse == null ? "(null)" : rawResponse.substring(0, Math.min(300, rawResponse.length()));
                log.warn("LLM 修复响应未包含可用代码: filePath={}, rawPreview={}", file.getFilePath(), preview);
                return GenerationResult.failure("LLM", "LLM 修复响应未包含可识别代码 (rawPreview=" + preview + ")");
            }
            GeneratedFile fixedFile = GeneratedFile.create(file.getType(), file.getFilePath(), fixedCode);
            return GenerationResult.llmSuccess(List.of(fixedFile));
        } catch (Exception e) {
            log.error("LLM修复失败: {}", file.getFilePath(), e);
            return GenerationResult.failure("LLM", e.getMessage());
        }
    }

    /**
     * 从 LLM 响应中提取代码。
     * 优先级:
     * 1. ```lang ... ``` 代码块;
     * 2. 裸 Java 源码(检测 package/import/public class/@Annotation);
     * 3. 裸 SQL 源码(检测 CREATE/ALTER/DROP TABLE、INSERT INTO、CREATE INDEX);
     * 4. 都不满足 → 返回 null。
     */
    private String extractCode(String raw, AtomicTask task) {
        if (raw == null) return null;
        // 1. 代码块优先
        java.util.regex.Matcher m = CODE_BLOCK.matcher(raw);
        if (m.find()) {
            String code = m.group(1).trim();
            if (!code.isEmpty()) {
                return code;
            }
        }
        // 2/3. 裸响应兜底
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;

        boolean isDdl = task != null && task.getType() == TaskType.DDL_STATEMENT;
        boolean isXml = task != null && task.getType() == TaskType.MAPPER_XML;
        if (isDdl) {
            if (SQL_HINT.matcher(trimmed).find()) return trimmed;
        } else if (isXml) {
            if (XML_HINT.matcher(trimmed).find()) return trimmed;
        } else {
            if (JAVA_HINT.matcher(trimmed).find()) return trimmed;
            // Java 任务有时 LLM 也可能误返回 SQL(如生成建表语句),宽容接受
            if (SQL_HINT.matcher(trimmed).find()) return trimmed;
        }
        return null;
    }
}
