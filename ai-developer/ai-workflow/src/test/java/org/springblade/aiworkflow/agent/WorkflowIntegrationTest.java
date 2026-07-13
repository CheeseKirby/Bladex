package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.service.IPartACallbackService;
import org.springblade.aiworkflow.service.IPlanExecutionService;
import org.springblade.aiworkflow.vo.ExecutionStatusVO;
import org.springblade.aiworkflow.vo.GeneratedFileDetailVO;
import org.springblade.aiworkflow.vo.GeneratedFileSummaryVO;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;
import org.springblade.aiworkflow.vo.PlanReceiveResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * H1: 工作流集成测试 - mock LlmClient + H2 内存 DB, 验证端到端主路径与修复循环。
 *
 * <p>为 H8(拆分 God Object)/M9(抽象修复循环)大重构兜底: 重构后跑此测试确保主流程与修复循环不破。
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowIntegrationTest {

    @MockBean
    LlmClient llmClient;

    @MockBean
    IPartACallbackService callbackService;

    @Autowired
    IPlanExecutionService planExecutionService;

    @Test
    void contextLoadsAndLlmClientMocked() {
        assertNotNull(planExecutionService, "planExecutionService 应注入");
        assertNotNull(llmClient, "LlmClient 应被 @MockBean 替换");
    }

    /**
     * 端到端: 构造最小 plan(单子方案生成 Demo Entity + DDL), mock LlmClient 返回预制合规代码,
     * 断言 plan COMPLETED 且生成 >= 2 文件。
     */
    @Test
    void shouldExecuteSimplePlanWithMockedLlm() throws Exception {
        PlanReceiveRequest request = buildDemoPlanRequest("Demo Entity 与 DDL");

        String entityCode = """
                package org.springblade.demo.pojo.entity;
                import com.baomidou.mybatisplus.annotation.TableName;
                import lombok.Data;
                import lombok.EqualsAndHashCode;
                import org.springblade.core.mp.base.BaseEntity;
                @Data
                @TableName("blade_demo")
                @EqualsAndHashCode(callSuper = true)
                public class Demo extends BaseEntity {
                    private static final long serialVersionUID = 1L;
                    private String name;
                }
                """;
        String ddlCode = """
                CREATE TABLE blade_demo (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  name VARCHAR(64),
                  create_time DATETIME,
                  update_time DATETIME,
                  status INT,
                  is_deleted INT,
                  PRIMARY KEY (id)
                );
                """;
        when(llmClient.generate(contains("生成一个Entity类"), any())).thenReturn(entityCode);
        when(llmClient.generate(contains("MySQL DDL"), any())).thenReturn(ddlCode);

        PlanReceiveResponse resp = planExecutionService.receivePlan(request);
        planExecutionService.executeAsync(resp.getReceptionId());

        String status = pollStatus(resp.getReceptionId());
        assertEquals("COMPLETED", status, "简单 plan(mock LLM)应执行完成; 实际: " + status);

        List<GeneratedFileSummaryVO> files = planExecutionService.listGeneratedFiles(resp.getReceptionId());
        assertTrue(files.size() >= 2, "应至少生成 Entity + DDL 2 个文件; 实际: " + files.size());
    }

    /**
     * M9 前置: 验证 Entity↔DDL 修复循环。故意让 Entity 字段名(nameEntity)与 DDL 列(name)不一致,
     * 触发 retryPlanWideEntityDdlMismatches 以 DDL 为源头重生成 Entity。mock 第二次返回对齐的 Entity,
     * 断言修复后 plan 非 FAILED 且 Entity 内容含 name(不含 nameEntity)。
     *
     * <p>此测试为 M9(抽象修复循环)兜底: 抽象后跑此测试确保修复循环仍工作。
     */
    @Test
    void shouldTriggerEntityDdlRepairWhenInconsistent() throws Exception {
        PlanReceiveRequest request = buildDemoPlanRequest("Demo Entity 与 DDL");

        String ddlCode = """
                CREATE TABLE blade_demo (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  name VARCHAR(64),
                  create_time DATETIME,
                  update_time DATETIME,
                  status INT,
                  is_deleted INT,
                  PRIMARY KEY (id)
                );
                """;
        // 第一次 Entity: 字段 nameEntity(与 DDL 列 name 不一致) -> 触发 ENTITY-DDL-COLUMN-MISSING 修复
        String entityBad = """
                package org.springblade.demo.pojo.entity;
                import com.baomidou.mybatisplus.annotation.TableName;
                import lombok.Data;
                import lombok.EqualsAndHashCode;
                import org.springblade.core.mp.base.BaseEntity;
                @Data
                @TableName("blade_demo")
                @EqualsAndHashCode(callSuper = true)
                public class Demo extends BaseEntity {
                    private static final long serialVersionUID = 1L;
                    private String nameEntity;
                }
                """;
        // 修复后 Entity: 字段 name(对齐 DDL)
        String entityGood = """
                package org.springblade.demo.pojo.entity;
                import com.baomidou.mybatisplus.annotation.TableName;
                import lombok.Data;
                import lombok.EqualsAndHashCode;
                import org.springblade.core.mp.base.BaseEntity;
                @Data
                @TableName("blade_demo")
                @EqualsAndHashCode(callSuper = true)
                public class Demo extends BaseEntity {
                    private static final long serialVersionUID = 1L;
                    private String name;
                }
                """;
        when(llmClient.generate(contains("MySQL DDL"), any())).thenReturn(ddlCode);
        // 链式: 第一次 generate(Entity) 返回 BAD(生成), 第二次返回 GOOD(修复)
        when(llmClient.generate(contains("生成一个Entity类"), any()))
                .thenReturn(entityBad).thenReturn(entityGood);

        PlanReceiveResponse resp = planExecutionService.receivePlan(request);
        planExecutionService.executeAsync(resp.getReceptionId());

        String status = pollStatus(resp.getReceptionId());
        assertNotEquals("FAILED", status,
                "Entity↔DDL 修复循环应跑通, plan 不应 FAILED; 实际: " + status);

        // 验证修复后 Entity 内容对齐 DDL(含 name, 不含 nameEntity)
        List<GeneratedFileSummaryVO> files = planExecutionService.listGeneratedFiles(resp.getReceptionId());
        GeneratedFileSummaryVO entityFile = files.stream()
                .filter(f -> f.getFilePath() != null && f.getFilePath().endsWith("Demo.java"))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 Demo.java 文件"));
        GeneratedFileDetailVO detail = planExecutionService.getGeneratedFileDetail(entityFile.getId());
        assertTrue(detail.getContent().contains("private String name;"),
                "修复后 Entity 应含 name 字段(对齐 DDL); 实际 content: " + detail.getContent());
        assertFalse(detail.getContent().contains("nameEntity"),
                "修复后不应含 nameEntity; 实际 content: " + detail.getContent());
    }

    /** 构造最小 plan(单子方案生成 Demo Entity + DDL) */
    private PlanReceiveRequest buildDemoPlanRequest(String subPlanTitle) {
        PlanReceiveRequest request = new PlanReceiveRequest();
        request.setProjectId("test-demo-" + System.nanoTime());
        request.setProjectName("测试Demo");
        PlanReceiveRequest.MasterPlanVO master = new PlanReceiveRequest.MasterPlanVO();
        master.setId("m1");
        master.setVersion(1);
        master.setContent("# Demo 模块方案\n实体名: Demo\n模块: demo\n字段: name(String)");
        request.setMasterPlan(master);
        PlanReceiveRequest.SubPlanVO sp = new PlanReceiveRequest.SubPlanVO();
        sp.setId("sp1");
        sp.setIndex(1);
        sp.setTitle(subPlanTitle);
        sp.setContent("实体名: Demo\n模块: demo\n生成 Demo Entity 与建表 DDL, 字段 name(String)");
        request.setSubPlans(List.of(sp));
        return request;
    }

    /** 轮询 plan 状态直到终态(COMPLETED/COMPLETED_WITH_ERRORS/FAILED), 最多等 90s */
    private String pollStatus(String receptionId) throws InterruptedException {
        for (int i = 0; i < 90; i++) {
            ExecutionStatusVO st = planExecutionService.getStatus(receptionId);
            String status = st.getOverallStatus();
            if ("COMPLETED".equals(status) || "COMPLETED_WITH_ERRORS".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            Thread.sleep(1000);
        }
        return planExecutionService.getStatus(receptionId).getOverallStatus();
    }
}
