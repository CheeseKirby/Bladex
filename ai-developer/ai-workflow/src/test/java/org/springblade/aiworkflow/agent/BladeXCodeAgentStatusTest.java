package org.springblade.aiworkflow.agent;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BladeXCodeAgentStatusTest {

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
    void executionFailureTakesPrecedence() {
        assertEquals(PlanStatus.FAILED,
                BladeXCodeAgent.determineFinalPlanStatus(false, true, 2, true));
    }
}
