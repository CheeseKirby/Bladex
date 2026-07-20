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
        return generateWithLLM(task, null, null);
    }

    /**
     * 带参考项目的生成 — 阶段2增强。
     *
     * <p>REAL 模式下,把参考项目适配摘要 + 同类代码摘要注入 prompt,让新模块能接入参考项目编译 + 风格贴合。
     * adaptationSummary/referenceSummary 为 null 时等价于 {@link #generate}。
     */
    public GenerationResult generateWithReference(AtomicTask task, String adaptationSummary, String referenceSummary) {
        return generateWithLLM(task, adaptationSummary, referenceSummary);
    }

    private GenerationResult generateWithLLM(AtomicTask task, String adaptationSummary, String referenceSummary) {
        try {
            boolean hasRef = (adaptationSummary != null && !adaptationSummary.isBlank())
                    || (referenceSummary != null && !referenceSummary.isBlank());
            Prompt prompt = hasRef
                    ? promptBuilder.buildWithReference(task, adaptationSummary, referenceSummary)
                    : promptBuilder.build(task);
            log.info("LLM生成: type={}, targetPath={}, withReference={}",
                    task.getType(), task.getTargetPath(), hasRef);
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
     * 跨文件修复 — 以 Service 接口为契约源头,重生成与接口契约不符的实现类(Controller/ServiceImpl)。
     *
     * <p>与 {@link #fix} 的区别: 单文件 fix 拿不到对端文件; 本方法把 Service 接口源码作为 context 注入,
     * 让 LLM 据此修正实现类使其对齐接口。由 BladeXCodeAgent 在跨文件校验检出
     * CROSS-CONTROLLER-SERVICE-MISMATCH / CROSS-SERVICE-IMPL-IFACE-MISMATCH 后调用。
     *
     * @param fileToFix        需修复的实现类文件(其内容将被替换)
     * @param contractCode     契约对端(Service 接口)源码,作为修复 context
     * @param task             原子任务(系统提示词角色 + 实体名/模块名)
     * @param issueDescription CrossFileValidator 检出的问题描述
     */
    public GenerationResult fixWithCrossFileContext(GeneratedFile fileToFix, String contractCode,
                                                     AtomicTask task, String issueDescription) {
        try {
            Prompt fixPrompt = promptBuilder.buildCrossFileFixPrompt(
                    issueDescription, fileToFix.getContent(), contractCode, task);
            log.info("LLM跨文件修复: {}", fileToFix.getFilePath());
            String rawResponse = llmClient.generate(fixPrompt.getSystemPrompt(), fixPrompt.getUserPrompt());
            String fixedCode = extractCode(rawResponse, task);
            if (fixedCode == null) {
                String preview = rawResponse == null ? "(null)" : rawResponse.substring(0, Math.min(300, rawResponse.length()));
                log.warn("LLM 跨文件修复响应未包含可用代码: filePath={}, rawPreview={}", fileToFix.getFilePath(), preview);
                return GenerationResult.failure("LLM", "LLM 跨文件修复响应未包含可识别代码 (rawPreview=" + preview + ")");
            }
            GeneratedFile fixedFile = GeneratedFile.create(fileToFix.getType(), fileToFix.getFilePath(), fixedCode);
            return GenerationResult.llmSuccess(List.of(fixedFile));
        } catch (Exception e) {
            log.error("LLM跨文件修复失败: {}", fileToFix.getFilePath(), e);
            return GenerationResult.failure("LLM", e.getMessage());
        }
    }

    /** Repairs a file with the complete relevant generated-project contract. */
    public GenerationResult fixProjectQuality(GeneratedFile fileToFix, String projectContext,
                                               AtomicTask task, String issueDescription) {
        try {
            Prompt fixPrompt = promptBuilder.buildProjectQualityFixPrompt(
                    issueDescription, fileToFix.getContent(), projectContext, task);
            log.info("LLM project-quality repair: {}", fileToFix.getFilePath());
            String rawResponse = llmClient.generate(fixPrompt.getSystemPrompt(), fixPrompt.getUserPrompt());
            String fixedCode = extractCode(rawResponse, task);
            if (fixedCode == null) {
                String preview = rawResponse == null ? "(null)"
                        : rawResponse.substring(0, Math.min(300, rawResponse.length()));
                log.warn("Project-quality repair response contained no usable source: filePath={}, rawPreview={}",
                        fileToFix.getFilePath(), preview);
                return GenerationResult.failure("LLM",
                        "Project-quality repair response contained no recognizable source (rawPreview=" + preview + ")");
            }
            return GenerationResult.llmSuccess(List.of(
                    GeneratedFile.modify(fileToFix.getType(), fileToFix.getFilePath(), fixedCode)));
        } catch (Exception e) {
            log.error("Project-quality repair failed: {}", fileToFix.getFilePath(), e);
            return GenerationResult.failure("LLM", e.getMessage());
        }
    }

    /**
     * Entity↔DDL 修复 — 以 DDL 为契约源头,重生成与表结构不一致的 Entity。
     *
     * <p>由 BladeXCodeAgent 在 plan 级跨文件校验检出 ENTITY-DDL-* ERROR 后调用。
     * 与 {@link #fixWithCrossFileContext} 区别: 本方法注入 DDL(非 IService)作 context,
     * 用 Entity 专用修复 prompt(含字段类型映射/多租户规则)。
     *
     * @param entityFile     需修复的 Entity 文件(其内容将被替换)
     * @param ddlCode         DDL 源码(契约源头,作为修复 context)
     * @param task            原子任务(STANDARD_CRUD_ENTITY 类型,系统提示词角色 + 实体名/模块名)
     * @param issueDescription CrossFileValidator 检出的问题描述
     */
    public GenerationResult fixEntityWithDdl(GeneratedFile entityFile, String ddlCode,
                                              AtomicTask task, String issueDescription) {
        try {
            Prompt fixPrompt = promptBuilder.buildEntityDdlFixPrompt(
                    issueDescription, entityFile.getContent(), ddlCode, task);
            log.info("LLM Entity↔DDL 修复: {}", entityFile.getFilePath());
            String rawResponse = llmClient.generate(fixPrompt.getSystemPrompt(), fixPrompt.getUserPrompt());
            String fixedCode = extractCode(rawResponse, task);
            if (fixedCode == null) {
                String preview = rawResponse == null ? "(null)" : rawResponse.substring(0, Math.min(300, rawResponse.length()));
                log.warn("LLM Entity↔DDL 修复响应未包含可用代码: filePath={}, rawPreview={}", entityFile.getFilePath(), preview);
                return GenerationResult.failure("LLM", "LLM Entity↔DDL 修复响应未包含可识别代码 (rawPreview=" + preview + ")");
            }
            GeneratedFile fixedFile = GeneratedFile.create(task.getType(), entityFile.getFilePath(), fixedCode);
            return GenerationResult.llmSuccess(List.of(fixedFile));
        } catch (Exception e) {
            log.error("LLM Entity↔DDL 修复失败: {}", entityFile.getFilePath(), e);
            return GenerationResult.failure("LLM", e.getMessage());
        }
    }

    /**
     * VO↔Entity 修复 - 以 Entity 为契约源头,重生成与 Entity 字段不一致的 VO/IVO/UVO。
     *
     * <p>由 BladeXCodeAgent 在 plan 级跨文件校验检出 VO-ENTITY-FIELD-MISMATCH /
     * VO-ENTITY-FIELD-TYPE-MISMATCH 后调用。与 {@link #fixEntityWithDdl} 区别: 本方法注入 Entity(非 DDL)
     * 作 context, 用 VO 专用修复 prompt(含字段同名同类型/移除凭空字段规则)。
     *
     * @param voFile           需修复的 VO/IVO/UVO 文件(其内容将被替换)
     * @param entityCode       Entity 源码(契约源头,作为修复 context)
     * @param task             原子任务(系统提示词角色 + 实体名/模块名)
     * @param issueDescription CrossFileValidator 检出的问题描述
     */
    public GenerationResult fixVoWithEntity(GeneratedFile voFile, String entityCode,
                                             AtomicTask task, String issueDescription) {
        try {
            Prompt fixPrompt = promptBuilder.buildVoEntityFixPrompt(
                    issueDescription, voFile.getContent(), entityCode, task);
            log.info("LLM VO↔Entity 修复: {}", voFile.getFilePath());
            String rawResponse = llmClient.generate(fixPrompt.getSystemPrompt(), fixPrompt.getUserPrompt());
            String fixedCode = extractCode(rawResponse, task);
            if (fixedCode == null) {
                String preview = rawResponse == null ? "(null)" : rawResponse.substring(0, Math.min(300, rawResponse.length()));
                log.warn("LLM VO↔Entity 修复响应未包含可用代码: filePath={}, rawPreview={}", voFile.getFilePath(), preview);
                return GenerationResult.failure("LLM", "LLM VO↔Entity 修复响应未包含可识别代码 (rawPreview=" + preview + ")");
            }
            GeneratedFile fixedFile = GeneratedFile.create(task.getType(), voFile.getFilePath(), fixedCode);
            return GenerationResult.llmSuccess(List.of(fixedFile));
        } catch (Exception e) {
            log.error("LLM VO↔Entity 修复失败: {}", voFile.getFilePath(), e);
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
        // 2/3. 裸响应兜底 - 丢弃 LLM 前导散文/解释,从首个代码关键字行截取到尾
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        String stripped = stripLeadingProse(trimmed);

        boolean isDdl = task != null && task.getType() == TaskType.DDL_STATEMENT;
        boolean isXml = task != null && task.getType() == TaskType.MAPPER_XML;
        if (isDdl) {
            if (SQL_HINT.matcher(stripped).find()) return stripped;
        } else if (isXml) {
            if (XML_HINT.matcher(stripped).find()) return stripped;
        } else {
            if (JAVA_HINT.matcher(stripped).find()) return stripped;
            // Java 任务有时 LLM 也可能误返回 SQL(如生成建表语句),宽容接受
            if (SQL_HINT.matcher(stripped).find()) return stripped;
        }
        return null;
    }

    /**
     * 从裸响应中截取真正的代码起始位置,丢弃 LLM 前导散文/解释。
     *
     * <p>LLM 常返回 "这是修复后的代码:\npackage org.springblade..." 形式,前导散文会让 JavaParser 解析失败
     * 进而触发 CrossFileValidator 静默跳过该文件(所有跨文件规则绕过)。这里找首个代码关键字行的偏移,
     * 从那里截取到尾,保证返回的是合法代码起点。
     */
    private static String stripLeadingProse(String trimmed) {
        int best = Integer.MAX_VALUE;
        java.util.regex.Pattern[] codeStarts = new java.util.regex.Pattern[]{
                java.util.regex.Pattern.compile("(?m)^\\s*package\\s+"),
                java.util.regex.Pattern.compile("(?m)^\\s*import\\s+"),
                java.util.regex.Pattern.compile("(?m)^\\s*public\\s+(?:class|interface|enum|record)\\s+"),
                java.util.regex.Pattern.compile("(?m)^\\s*@(?:[A-Za-z]\\w*)"),
                java.util.regex.Pattern.compile("(?i)\\bCREATE\\s+TABLE\\b"),
                java.util.regex.Pattern.compile("(?i)\\bINSERT\\s+INTO\\b"),
                java.util.regex.Pattern.compile("(?i)<\\?xml"),
                java.util.regex.Pattern.compile("(?i)<mapper[\\s>]"),
        };
        for (java.util.regex.Pattern p : codeStarts) {
            java.util.regex.Matcher m = p.matcher(trimmed);
            if (m.find() && m.start() < best) best = m.start();
        }
        return best == Integer.MAX_VALUE ? trimmed : trimmed.substring(best);
    }
}
