package org.springblade.aiworkflow.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Fail-closed gate between plan compilation/linking and code generation. */
final class PlanPreflightGate {

    private PlanPreflightGate() {
    }

    static Result evaluate(List<PlanCompilationIssue> issues) {
        List<PlanCompilationIssue> safeIssues = issues == null ? List.of() : List.copyOf(issues);
        List<PlanCompilationIssue> errors = safeIssues.stream()
                .filter(issue -> issue != null && "ERROR".equalsIgnoreCase(issue.severity()))
                .toList();
        LinkedHashSet<Long> affected = new LinkedHashSet<>();
        for (PlanCompilationIssue error : errors) {
            if (error.subPlanId() != null) affected.add(error.subPlanId());
        }
        return new Result(!errors.isEmpty(), errors, new ArrayList<>(affected));
    }

    record Result(boolean blocking, List<PlanCompilationIssue> errors, List<Long> affectedSubPlanIds) {
        Result {
            errors = errors == null ? List.of() : List.copyOf(errors);
            affectedSubPlanIds = affectedSubPlanIds == null ? List.of() : List.copyOf(affectedSubPlanIds);
        }
    }
}
