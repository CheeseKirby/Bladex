package org.springblade.aiworkflow.agent;

import java.util.List;

/** Structured deliverable compiled from a reviewed sub-plan before atomic task generation. */
record PlannedArtifact(
        Long subPlanId,
        String name,
        ArtifactKind kind,
        ArtifactAction action,
        String ownerModule,
        ModuleSide moduleSide,
        String declaredPackage,
        String targetPath,
        boolean required,
        String evidence) {

    PlannedArtifact withTargetPath(String path) {
        return new PlannedArtifact(subPlanId, name, kind, action, ownerModule, moduleSide,
                declaredPackage, path, required, evidence);
    }

    boolean prohibited() {
        return action == ArtifactAction.PROHIBIT;
    }
}

enum ArtifactKind {
    DTO,
    ENTITY,
    VO,
    CONTROLLER,
    SERVICE_INTERFACE,
    SERVICE_IMPL,
    WRAPPER,
    MAPPER,
    MAPPER_XML,
    CONFIG,
    FEIGN_INTERFACE,
    FEIGN_PROVIDER,
    EXCEL_MODEL,
    EXCEL_UTILITY,
    EXCEL_LISTENER,
    DDL,
    OTHER
}

enum ArtifactAction {
    CREATE,
    MODIFY,
    EXTEND,
    PROHIBIT
}

enum ModuleSide {
    API,
    IMPL,
    DOC,
    UNKNOWN
}

record PlanCompilationIssue(
        Long subPlanId,
        String severity,
        String rule,
        String filePath,
        String message) {

    static PlanCompilationIssue error(Long subPlanId, String rule, String filePath, String message) {
        return new PlanCompilationIssue(subPlanId, "ERROR", rule, filePath, message);
    }

    GeneratedProjectValidator.Issue toProjectIssue() {
        return new GeneratedProjectValidator.Issue(severity, rule, filePath, message);
    }
}

record PlanArtifactCompilation(List<PlannedArtifact> artifacts, List<PlanCompilationIssue> issues) {
    PlanArtifactCompilation {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
