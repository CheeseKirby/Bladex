package org.springblade.aiworkflow.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates the independently compiled deliverable inventory against accepted atomic tasks. */
final class PlanArtifactTaskValidator {

    List<PlanCompilationIssue> validate(List<PlannedArtifact> artifacts, List<AtomicTask> acceptedTasks) {
        Set<String> taskPaths = new HashSet<>();
        for (AtomicTask task : acceptedTasks == null ? List.<AtomicTask>of() : acceptedTasks) {
            if (task.getTargetPath() != null) taskPaths.add(normalize(task.getTargetPath()));
        }

        List<PlanCompilationIssue> issues = new ArrayList<>();
        for (PlannedArtifact artifact : artifacts == null ? List.<PlannedArtifact>of() : artifacts) {
            if (artifact.targetPath() == null || artifact.targetPath().isBlank()) continue;
            boolean exists = taskPaths.contains(normalize(artifact.targetPath()));
            if (artifact.prohibited() && exists) {
                issues.add(PlanCompilationIssue.error(artifact.subPlanId(), "PLAN-PROHIBITED-DELIVERABLE",
                        artifact.targetPath(), "Reviewed plan explicitly prohibits generating " + artifact.name()));
            } else if (artifact.required() && !artifact.prohibited() && !exists) {
                issues.add(PlanCompilationIssue.error(artifact.subPlanId(), "PLAN-DELIVERABLE-MISSING",
                        artifact.targetPath(), "No accepted atomic task implements required artifact " + artifact.name()));
            }
        }
        return issues;
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }
}
