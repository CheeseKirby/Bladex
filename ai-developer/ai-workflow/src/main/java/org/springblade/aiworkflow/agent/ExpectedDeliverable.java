package org.springblade.aiworkflow.agent;

import org.springblade.aiworkflow.enums.TaskType;

/** Machine-readable deliverable expected from a reviewed sub-plan. */
public record ExpectedDeliverable(
        Long subPlanId,
        TaskType type,
        String targetPath,
        String entityName,
        String moduleName,
        boolean required) {

    public static ExpectedDeliverable from(Long subPlanId, AtomicTask task) {
        return new ExpectedDeliverable(subPlanId, task.getType(), task.getTargetPath(),
                task.getEntityName(), task.getModuleName(), true);
    }
}
