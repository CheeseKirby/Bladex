package org.springblade.aiworkflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.entity.AiExecutionLog;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.enums.PlanStatus;
import org.springblade.aiworkflow.enums.SubPlanStatus;
import org.springblade.aiworkflow.mapper.AiExecutionLogMapper;
import org.springblade.aiworkflow.mapper.AiSubPlanMapper;
import org.springblade.aiworkflow.notification.WorkflowStatusNotifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BladeXCodeAgentStatusTest {

    @Test
    void executionLogValidationPayloadIsAlwaysValidJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        String wrapped = BladeXCodeAgent.normalizeValidationJson(mapper,
                "Fields: appointmentNo, visitorName");
        JsonNode wrappedNode = mapper.readTree(wrapped);
        assertEquals("Fields: appointmentNo, visitorName", wrappedNode.path("summary").asText());

        String structured = BladeXCodeAgent.normalizeValidationJson(mapper,
                "{\"passes\":true,\"issues\":[]}");
        JsonNode structuredNode = mapper.readTree(structured);
        assertTrue(structuredNode.path("passes").asBoolean());
        assertTrue(structuredNode.path("issues").isArray());

        assertNull(BladeXCodeAgent.normalizeValidationJson(mapper, null));
        assertNull(BladeXCodeAgent.normalizeValidationJson(mapper, "   "));
    }

    @Test
    void repairedContractErrorsProduceCompletedStatus() {
        assertEquals(PlanStatus.COMPLETED,
                BladeXCodeAgent.determineFinalPlanStatus(true, false, 0, false));
    }

    @Test
    void unresolvedPlanWideErrorsProducePartialCompletion() {
        assertEquals(PlanStatus.COMPLETED_WITH_ERRORS,
                BladeXCodeAgent.determineFinalPlanStatus(true, false, 1, false));
    }

    @Test
    void staleSubPlanErrorsAndCompileFailuresRemainVisible() {
        assertEquals(PlanStatus.COMPLETED_WITH_ERRORS,
                BladeXCodeAgent.determineFinalPlanStatus(true, true, 0, false));
        assertEquals(PlanStatus.COMPLETED_WITH_ERRORS,
                BladeXCodeAgent.determineFinalPlanStatus(true, false, 0, true));
    }

    @Test
    void repairedSubPlanStatusIsReconciledAndReported() {
        AiSubPlanMapper subPlanMapper = mock(AiSubPlanMapper.class);
        AiExecutionLogMapper logMapper = mock(AiExecutionLogMapper.class);
        WorkflowStatusNotifier notifier = mock(WorkflowStatusNotifier.class);
        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, subPlanMapper, logMapper, null,
                null, null, null, null, null, null,
                3, false, null, null, null, null, null, notifier);

        AiSubPlan repaired = new AiSubPlan();
        repaired.setId(10L);
        repaired.setStatus(SubPlanStatus.COMPLETED_WITH_ERRORS);
        repaired.setErrorMessage("stale error");
        AiSubPlan alreadyCompleted = new AiSubPlan();
        alreadyCompleted.setId(11L);
        alreadyCompleted.setStatus(SubPlanStatus.COMPLETED);

        agent.reconcileRepairedSubPlanStatuses(List.of(repaired, alreadyCompleted));

        assertEquals(SubPlanStatus.COMPLETED, repaired.getStatus());
        assertNull(repaired.getErrorMessage());
        verify(subPlanMapper).updateById(repaired);
        verify(subPlanMapper, never()).updateById(alreadyCompleted);
        verify(logMapper).insert(any(AiExecutionLog.class));
        verify(notifier).notifySubPlan(repaired);
    }


    @Test
    void planCompilationErrorsRemainVisibleOnOwningSubPlan() {
        AiSubPlanMapper subPlanMapper = mock(AiSubPlanMapper.class);
        WorkflowStatusNotifier notifier = mock(WorkflowStatusNotifier.class);
        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, subPlanMapper, null, null,
                null, null, null, null, null, null,
                3, false, null, null, null, null, null, notifier);

        AiSubPlan affected = new AiSubPlan();
        affected.setId(20L);
        affected.setStatus(SubPlanStatus.COMPLETED);
        AiSubPlan failed = new AiSubPlan();
        failed.setId(21L);
        failed.setStatus(SubPlanStatus.FAILED);

        agent.markCompilationIssueSubPlans(List.of(affected, failed), List.of(
                PlanCompilationIssue.error(20L, "PLAN-TARGET-NOT-FOUND", "HotworkController.java",
                        "Exact reference target was not found"),
                PlanCompilationIssue.error(21L, "PLAN-MODULE-NOT-FOUND", "hotwork",
                        "Module was not found")));

        assertEquals(SubPlanStatus.COMPLETED_WITH_ERRORS, affected.getStatus());
        assertEquals(SubPlanStatus.FAILED, failed.getStatus());
        verify(subPlanMapper).updateById(affected);
        verify(subPlanMapper, never()).updateById(failed);
        verify(notifier).notifySubPlan(affected);
    }

    @Test
    void finalProjectErrorsAreProjectedBackToOwningSubPlan() {
        AiSubPlanMapper subPlanMapper = mock(AiSubPlanMapper.class);
        WorkflowStatusNotifier notifier = mock(WorkflowStatusNotifier.class);
        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, subPlanMapper, null, null,
                null, null, null, null, null, null,
                3, false, null, null, null, null, null, notifier);
        AiSubPlan entityPlan = new AiSubPlan();
        entityPlan.setId(30L);
        entityPlan.setStatus(SubPlanStatus.COMPLETED);
        String path = "blade-service-api/blade-demo-api/src/main/java/org/springblade/demo/entity/Demo.java";
        ExpectedDeliverable deliverable = new ExpectedDeliverable(
                30L, org.springblade.aiworkflow.enums.TaskType.STANDARD_CRUD_ENTITY,
                path, "Demo", "demo", true);

        agent.markProjectIssueSubPlans(List.of(entityPlan), List.of(
                new GeneratedProjectValidator.Issue("ERROR", "CANONICAL-ENTITY-FIELD-MISSING", path,
                        "missing canonical field")), List.of(deliverable));

        assertEquals(SubPlanStatus.COMPLETED_WITH_ERRORS, entityPlan.getStatus());
        verify(subPlanMapper).updateById(entityPlan);
        verify(notifier).notifySubPlan(entityPlan);
    }

    @Test
    void executionFailureTakesPrecedence() {
        assertEquals(PlanStatus.FAILED,
                BladeXCodeAgent.determineFinalPlanStatus(false, true, 2, true));
    }
}
