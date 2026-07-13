package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.service.IPlanExecutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * H1: 工作流集成测试 - mock LlmClient + H2 内存 DB, 验证 Spring 上下文与主路径。
 *
 * <p>为 H8(拆分 God Object)/M9(抽象修复循环)大重构兜底: 重构后跑此测试确保主流程不破。
 *
 * <p>当前: 骨架(上下文加载 + LlmClient mock 注入验证)。完整端到端(构造 plan + mock LLM 返回预制代码 +
 * 调 receivePlan/executeAsync + 断言生成文件与 COMPLETED 状态)待补,需 mock LlmClient.generate
 * 按任务类型返回 Entity/VO/Controller/Service 等预制源码。
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowIntegrationTest {

    @MockBean
    LlmClient llmClient;

    @Autowired
    IPlanExecutionService planExecutionService;

    @Test
    void contextLoadsAndLlmClientMocked() {
        // 验证 Spring 上下文能加载(H2 建表 + 所有 agent bean 注入 + LlmClient 被 mock 替换)
        assertNotNull(planExecutionService, "planExecutionService 应注入");
        assertNotNull(llmClient, "LlmClient 应被 @MockBean 替换");
    }
}
