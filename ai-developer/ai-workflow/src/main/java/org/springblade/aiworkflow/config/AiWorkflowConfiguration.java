package org.springblade.aiworkflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;
import org.mybatis.spring.annotation.MapperScan;
import org.springblade.aiworkflow.agent.*;
import org.springblade.aiworkflow.convention.BladeXConventionLoader;
import org.springblade.aiworkflow.mapper.AiExecutionLogMapper;
import org.springblade.aiworkflow.mapper.AiGeneratedFileMapper;
import org.springblade.aiworkflow.mapper.AiPlanMapper;
import org.springblade.aiworkflow.mapper.AiSubPlanMapper;
import org.springblade.aiworkflow.service.IPartACallbackService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * AI工作流配置类 — 注册所有Agent Bean
 *
 * @author AI Developer
 */
@Configuration
@EnableAsync
@MapperScan("org.springblade.aiworkflow.mapper")
public class AiWorkflowConfiguration {

    /**
     * 给 LLM 调用用的长连接客户端: 长 readTimeout 容忍 LLM 响应时延。
     */
    @Bean("llmHttpClient")
    public OkHttpClient llmHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(false)         // 防止重定向到内网导致 SSRF 放大
                .followSslRedirects(false)
                .build();
    }

    /**
     * 给 Part A 回调用的短连接客户端: 短超时,避免 LLM 慢调用拖累回调重试节奏。
     */
    @Bean("callbackHttpClient")
    public OkHttpClient callbackHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * AI 工作流专用线程池。
     *
     * <p>关键策略:
     * - {@code AbortPolicy}:满负荷时直接抛 RejectedExecutionException,绝不在 Tomcat HTTP 线程上同步执行 LLM 流水线,
     *   避免 caller-runs 反噬把整个服务卡死;
     * - 配合 {@link org.springblade.aiworkflow.service.impl.PlanExecutionServiceImpl#executeAsync} 的 try/catch,
     *   被拒绝的任务会在 controller 层被记录,Part A 仍可通过 /api/execution/trigger 手动重试;
     * - 优雅关闭:wait 30s 让进行中的工作流尽量收尾,数据库状态不至于停在 EXECUTING。
     */
    @Bean("aiWorkflowExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-workflow-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    // ─── Agent Beans ───

    @Bean
    public BladeXConventionLoader bladeXConventionLoader(AiWorkflowProperties properties) {
        BladeXConventionLoader loader = new BladeXConventionLoader(properties);
        loader.loadAll();
        return loader;
    }

    @Bean
    public ConventionValidator conventionValidator() {
        return new ConventionValidator();
    }

    @Bean
    public PromptBuilder promptBuilder(BladeXConventionLoader conventionLoader) {
        return new PromptBuilder(conventionLoader);
    }

    @Bean
    public LlmClient llmClient(@Qualifier("llmHttpClient") OkHttpClient llmHttpClient,
                                ObjectMapper objectMapper,
                                AiWorkflowProperties properties) {
        return new LlmClient(llmHttpClient, objectMapper, properties.getLlm());
    }

    @Bean
    public ChangeEvaluator changeEvaluator(AiWorkflowProperties properties) {
        // 改动评估基于产物输出目录（outputRoot）；独立目录每次全量生成，SKIP/MODIFY 不再适用
        return new ChangeEvaluator(properties.getOutputRoot());
    }

    @Bean
    public FileWriteExecutor fileWriteExecutor(AiWorkflowProperties properties) {
        // 产物写入独立输出目录 outputRoot（与真实 blade_hgsjy 隔离）
        return new FileWriteExecutor(properties.getOutputRoot());
    }

    @Bean
    public BuildVerifier buildVerifier(AiWorkflowProperties properties) {
        return new BuildVerifier(properties.getTargetProjectRoot());
    }

    @Bean
    public BladeCodeGenRouter bladeCodeGenRouter(LlmClient llmClient, PromptBuilder promptBuilder) {
        return new BladeCodeGenRouter(llmClient, promptBuilder);
    }

    @Bean
    public BladeXCodeAgent bladeXCodeAgent(AiPlanMapper planMapper, AiSubPlanMapper subPlanMapper,
                                            AiExecutionLogMapper executionLogMapper,
                                            AiGeneratedFileMapper generatedFileMapper,
                                            BladeCodeGenRouter codeGenRouter,
                                            ConventionValidator conventionValidator,
                                            ChangeEvaluator changeEvaluator,
                                            FileWriteExecutor fileWriteExecutor,
                                            BuildVerifier buildVerifier,
                                            ObjectMapper objectMapper,
                                            AiWorkflowProperties properties,
                                            IPartACallbackService callbackService) {
        return new BladeXCodeAgent(planMapper, subPlanMapper, executionLogMapper, generatedFileMapper,
                codeGenRouter, conventionValidator, changeEvaluator, fileWriteExecutor,
                buildVerifier, objectMapper, properties.getMaxReviewRetries(),
                properties.isAutoCommit(),
                callbackService::notifyStatusUpdate);
    }
}
