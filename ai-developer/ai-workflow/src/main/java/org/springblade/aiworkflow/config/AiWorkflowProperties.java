package org.springblade.aiworkflow.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI工作流配置属性
 *
 * <p>注意 {@link LlmProperties} 是运行时可变的(ConfigController 会改),
 * 字段标 {@code volatile} 保证跨线程可见性。
 *
 * @author AI Developer
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai-workflow")
public class AiWorkflowProperties {

    /** LLM配置 */
    @Valid
    private LlmProperties llm = new LlmProperties();

    /** 目标BladeX项目根路径（代码写入目标） */
    @NotBlank
    private String targetProjectRoot = "../../blade_hgsjy";

    /**
     * 生成产物独立输出根（BladeX 多模块结构落盘点）。
     * 默认与 target-project-root 同基准，落点为 hogan/ai-generated-modules，与 blade_hgsjy 物理隔离。
     * 产物按 blade-service/blade-{module} + blade-service-api/blade-{module}-api 多模块格式组织，
     * 结构 1:1 对齐参考 BladeX 项目；独立目录内不要求可编译（缺平台 jar）。
     */
    @NotBlank
    private String outputRoot = "../../ai-generated-modules";

    /** BladeX规范文档路径
     *  支持:
     *  - {@code classpath:bladex-docs/}  → 从 jar 包内置资源加载(默认)
     *  - 任意文件系统路径 (如 {@code ../doc/bladex/} 或绝对路径) → 开发期外部规范
     *  当文件系统路径不存在时会自动回退到 classpath 兜底,保证启动不失败。
     */
    @NotBlank
    private String conventionDocsPath = "classpath:bladex-docs/";

    /** 最大审查重试次数 */
    private int maxReviewRetries = 3;

    /** 是否自动Git提交 */
    private boolean autoCommit = false;

    /** Part A回调地址 */
    @NotBlank
    private String partACallbackUrl = "http://localhost:3001/api/transmission/status-update";

    /** 管理端配置 */
    private AdminProperties admin = new AdminProperties();

    @Data
    public static class AdminProperties {
        /** 管理端 token,匹配请求头 X-Admin-Token */
        private String token = "";
    }

    @Data
    public static class LlmProperties {
        /** LLM提供商 */
        @NotBlank
        private volatile String provider = "anthropic";

        /** API Base URL,默认 Anthropic 官方,可指向 ccswitch 中转网关或代理 */
        @NotBlank
        private volatile String baseUrl = "https://api.anthropic.com";

        /** 模型名称 */
        @NotBlank
        private volatile String model = "glm-5.1";

        /** Anthropic API Key (通过环境变量 ANTHROPIC_API_KEY 注入, 使用 x-api-key 头) */
        private volatile String apiKey;

        /**
         * 鉴权 Bearer Token (通过环境变量 ANTHROPIC_AUTH_TOKEN 注入, 使用 Authorization: Bearer 头)
         * 与 ccswitch / claude-code 一致;优先级高于 apiKey。
         */
        private volatile String authToken;

        /** API 版本 */
        @NotBlank
        private volatile String anthropicVersion = "2023-06-01";

        /** 最大Token数 */
        private volatile int maxTokens = 8192;

        /** 温度参数 */
        private volatile double temperature = 0.1;
    }
}
