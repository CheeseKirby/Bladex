package org.springblade.aiworkflow.notification;

import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.service.IPartACallbackService;
import org.springblade.aiworkflow.vo.StatusUpdateRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Builds and sends workflow status updates to Part A. */
@Slf4j
@Component
public class WorkflowStatusNotifier {

    private final IPartACallbackService callbackService;

    public WorkflowStatusNotifier(IPartACallbackService callbackService) {
        this.callbackService = callbackService;
    }

    public void notifyPlan(AiPlan plan, List<AiSubPlan> subPlans) {
        StatusUpdateRequest request = new StatusUpdateRequest();
        request.setReceptionId(plan.getReceptionId());
        request.setProjectId(plan.getProjectId());
        request.setOverallStatus(plan.getStatus() != null ? plan.getStatus().name() : null);

        List<StatusUpdateRequest.SubPlanStatusItem> updates = new ArrayList<>();
        for (AiSubPlan subPlan : subPlans) updates.add(toStatusItem(subPlan));
        request.setSubPlanUpdates(updates);
        callbackService.notifyStatusUpdate(request);
    }

    public void notifySubPlan(AiSubPlan subPlan) {
        log.info("Sub-plan status update: id={}, status={}", subPlan.getId(), subPlan.getStatus());
        StatusUpdateRequest request = new StatusUpdateRequest();
        request.setReceptionId(null);
        request.setOverallStatus(subPlan.getStatus() != null ? subPlan.getStatus().name() : null);
        request.setSubPlanUpdates(List.of(toStatusItem(subPlan)));
        callbackService.notifyStatusUpdate(request);
    }

    private StatusUpdateRequest.SubPlanStatusItem toStatusItem(AiSubPlan subPlan) {
        StatusUpdateRequest.SubPlanStatusItem item = new StatusUpdateRequest.SubPlanStatusItem();
        item.setSubPlanId(subPlan.getPartASubPlanId() != null
                ? subPlan.getPartASubPlanId() : String.valueOf(subPlan.getId()));
        item.setStatus(subPlan.getStatus() != null ? subPlan.getStatus().name() : null);
        item.setGitCommitHash(subPlan.getGitCommitHash());
        if (subPlan.getCompletedAt() != null) item.setCompletedAt(subPlan.getCompletedAt().toString());
        return item;
    }
}
