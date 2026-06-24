package org.springblade.aiworkflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springblade.aiworkflow.config.AiWorkflowProperties;

/**
 * AI代码生成引擎 — 启动类
 *
 * <p>独立的 Spring Boot 应用，不依赖 BladeX 运行时。
 * 接收 Part A 的开发方案，面向 BladeX 4.1.0 框架生成代码。
 *
 * @author AI Developer
 */
@SpringBootApplication
@EnableConfigurationProperties(AiWorkflowProperties.class)
public class AiWorkflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiWorkflowApplication.class, args);
    }
}
