package org.springblade.aiworkflow.notification;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.enums.PlanStatus;
import org.springblade.aiworkflow.enums.SubPlanStatus;
import org.springblade.aiworkflow.service.IPartACallbackService;
import org.springblade.aiworkflow.vo.StatusUpdateRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowStatusNotifierTest {

    @Test
    void planNotificationContainsPlanAndSubPlanIdentity() {
        IPartACallbackService callback = mock(IPartACallbackService.class);
        WorkflowStatusNotifier notifier = new WorkflowStatusNotifier(callback);
        AiPlan plan = new AiPlan();
        plan.setReceptionId("rec-1");
        plan.setProjectId("project-1");
        plan.setStatus(PlanStatus.COMPLETED);
        AiSubPlan subPlan = subPlan();

        notifier.notifyPlan(plan, List.of(subPlan));

        ArgumentCaptor<StatusUpdateRequest> captor = ArgumentCaptor.forClass(StatusUpdateRequest.class);
        verify(callback).notifyStatusUpdate(captor.capture());
        StatusUpdateRequest request = captor.getValue();
        assertEquals("rec-1", request.getReceptionId());
        assertEquals("project-1", request.getProjectId());
        assertEquals("COMPLETED", request.getOverallStatus());
        assertEquals("part-a-sub-1", request.getSubPlanUpdates().get(0).getSubPlanId());
    }

    @Test
    void subPlanNotificationUsesDatabaseIdAsFallback() {
        IPartACallbackService callback = mock(IPartACallbackService.class);
        WorkflowStatusNotifier notifier = new WorkflowStatusNotifier(callback);
        AiSubPlan subPlan = subPlan();
        subPlan.setPartASubPlanId(null);

        notifier.notifySubPlan(subPlan);

        ArgumentCaptor<StatusUpdateRequest> captor = ArgumentCaptor.forClass(StatusUpdateRequest.class);
        verify(callback).notifyStatusUpdate(captor.capture());
        assertEquals("42", captor.getValue().getSubPlanUpdates().get(0).getSubPlanId());
        assertEquals("COMPLETED", captor.getValue().getOverallStatus());
    }

    private AiSubPlan subPlan() {
        AiSubPlan subPlan = new AiSubPlan();
        subPlan.setId(42L);
        subPlan.setPartASubPlanId("part-a-sub-1");
        subPlan.setStatus(SubPlanStatus.COMPLETED);
        subPlan.setGitCommitHash("abc123");
        subPlan.setCompletedAt(LocalDateTime.of(2026, 7, 15, 12, 0));
        return subPlan;
    }
}
