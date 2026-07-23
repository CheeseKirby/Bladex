package org.springblade.aiworkflow.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Binds structured artifacts to canonical generated or existing-reference paths. */
final class ReferenceArtifactBinder {

    BindingResult bind(List<PlannedArtifact> artifacts, GenerationContext context,
                       ReferenceProjectIndex referenceIndex) {
        List<PlannedArtifact> bound = new ArrayList<>();
        List<PlanCompilationIssue> issues = new ArrayList<>();
        for (PlannedArtifact artifact : artifacts == null ? List.<PlannedArtifact>of() : artifacts) {
            Binding binding = bindOne(artifact, context, referenceIndex);
            if (binding.artifact() != null) bound.add(binding.artifact());
            if (binding.issue() != null) issues.add(binding.issue());
        }
        return new BindingResult(bound, issues);
    }

    private Binding bindOne(PlannedArtifact artifact, GenerationContext context,
                            ReferenceProjectIndex referenceIndex) {
        if (artifact == null) return new Binding(null, null);
        String ownerModule = normalizedModule(artifact.ownerModule(), context.identity().moduleName());
        PlannedArtifact normalized = new PlannedArtifact(artifact.subPlanId(), artifact.name(), artifact.kind(),
                artifact.action(), ownerModule, artifact.moduleSide(), artifact.declaredPackage(),
                artifact.targetPath(), artifact.required(), artifact.evidence());

        if (normalized.prohibited()) {
            String path = deriveCreatePath(normalized, context, null);
            return new Binding(normalized.withTargetPath(path), null);
        }

        if (normalized.action() == ArtifactAction.MODIFY || normalized.action() == ArtifactAction.EXTEND) {
            if (normalized.kind() == ArtifactKind.DDL) {
                if (!ownerModule.equals(context.identity().moduleName())) {
                    if (!referenceReady(referenceIndex)) {
                        return issue(normalized, "PLAN-REFERENCE-UNAVAILABLE",
                                "Cross-module DDL extension cannot be bound because the reference index is unavailable");
                    }
                    if (!moduleExists(ownerModule, referenceIndex)) {
                        return issue(normalized, "PLAN-MODULE-NOT-FOUND",
                                "Cross-module DDL extension targets module " + ownerModule
                                        + " which does not exist in the reference project");
                    }
                }
                return new Binding(normalized.withTargetPath("doc/sql/" + ownerModule + "/migration.sql"), null);
            }
            if (ownerModule.equals(context.identity().moduleName())) {
                String path = deriveCreatePath(normalized, context, null);
                return path == null
                        ? issue(normalized, "PLAN-ARTIFACT-UNSUPPORTED",
                        "No current-module target-path strategy exists for " + normalized.kind() + " " + normalized.name())
                        : new Binding(normalized.withTargetPath(path), null);
            }
            if (!referenceReady(referenceIndex)) {
                return issue(normalized, "PLAN-REFERENCE-UNAVAILABLE",
                        "Cross-module MODIFY target cannot be bound because the reference index is unavailable");
            }
            if (!moduleExists(ownerModule, referenceIndex)) {
                return issue(normalized, "PLAN-MODULE-NOT-FOUND",
                        "MODIFY target module " + ownerModule + " does not exist in the reference project");
            }
            List<IndexedClassInfo> matches = exactMatches(normalized, referenceIndex);
            if (matches.isEmpty()) {
                return issue(normalized, "PLAN-TARGET-NOT-FOUND",
                        "MODIFY target " + normalized.name() + " was not found in reference module " + ownerModule);
            }
            if (matches.size() > 1) {
                String candidates = matches.stream().map(IndexedClassInfo::relativePath).sorted()
                        .reduce((left, right) -> left + ", " + right).orElse("");
                return issue(normalized, "PLAN-TARGET-AMBIGUOUS",
                        "MODIFY target " + normalized.name() + " matched multiple reference files: " + candidates);
            }
            return new Binding(normalized.withTargetPath(matches.get(0).relativePath()), null);
        }

        if (ownerModule.equals(context.identity().moduleName())) {
            String path = deriveCreatePath(normalized, context, null);
            return path == null
                    ? issue(normalized, "PLAN-ARTIFACT-UNSUPPORTED",
                    "No target-path strategy exists for " + normalized.kind() + " " + normalized.name())
                    : new Binding(normalized.withTargetPath(path), null);
        }

        if (!referenceReady(referenceIndex)) {
            return issue(normalized, "PLAN-REFERENCE-UNAVAILABLE",
                    "Cross-module CREATE target cannot be bound because the reference index is unavailable");
        }
        if (!moduleExists(ownerModule, referenceIndex)) {
            return issue(normalized, "PLAN-MODULE-NOT-FOUND",
                    "CREATE target module " + ownerModule + " does not exist in the reference project");
        }
        String moduleRoot = moduleRoot(ownerModule, normalized.moduleSide(), referenceIndex);
        if (moduleRoot == null || normalized.declaredPackage() == null) {
            return issue(normalized, "PLAN-TARGET-AMBIGUOUS",
                    "Cross-module CREATE target " + normalized.name()
                            + " requires an exact package and resolvable Maven module root");
        }
        String path = moduleRoot + "/src/main/java/" + normalized.declaredPackage().replace('.', '/')
                + "/" + normalized.name() + ".java";
        return new Binding(normalized.withTargetPath(path), null);
    }

    private String deriveCreatePath(PlannedArtifact artifact, GenerationContext context, String ignored) {
        if (artifact.declaredPackage() != null) {
            String moduleRoot = artifact.moduleSide() == ModuleSide.API
                    ? "blade-service-api/" + context.identity().apiModuleName()
                    : "blade-service/" + context.identity().serviceModuleName();
            return moduleRoot + "/src/main/java/" + artifact.declaredPackage().replace('.', '/')
                    + "/" + artifact.name() + ".java";
        }
        return switch (artifact.kind()) {
            case DTO -> BladeXModuleLayout.dtoPath(context, artifact.name());
            case VO -> BladeXModuleLayout.apiClassPath(context,
                    context.referenceProfile().voPackageSuffix(artifact.name()), artifact.name());
            case FEIGN_INTERFACE -> BladeXModuleLayout.namedFeignPath(context, artifact.name());
            case FEIGN_PROVIDER -> BladeXModuleLayout.implClassPath(context,
                    context.referenceProfile().feignPackageSuffix(), artifact.name());
            case CONTROLLER -> BladeXModuleLayout.namedControllerPath(context, artifact.name());
            case ENTITY -> BladeXModuleLayout.entityPath(context, artifact.name());
            case SERVICE_INTERFACE -> BladeXModuleLayout.serviceInterfacePath(
                    context, serviceEntityName(artifact.name()));
            case SERVICE_IMPL -> BladeXModuleLayout.serviceImplPath(
                    context, serviceEntityName(artifact.name()));
            case WRAPPER -> BladeXModuleLayout.wrapperPath(context, context.identity().entityName());
            case MAPPER -> BladeXModuleLayout.mapperJavaPath(context, mapperEntityName(artifact.name()));
            case MAPPER_XML -> BladeXModuleLayout.mapperXmlPath(context, mapperEntityName(artifact.name()));
            case CONFIG -> "bootstrap.yml".equals(artifact.name())
                    ? BladeXModuleLayout.bootstrapPath(context) : BladeXModuleLayout.appDevPath(context);
            case EXCEL_MODEL -> artifact.name().endsWith("EVO")
                    ? BladeXModuleLayout.apiClassPath(context,
                    context.referenceProfile().voPackageSuffix(artifact.name()), artifact.name())
                    : BladeXModuleLayout.implClassPath(context,
                    context.referenceProfile().excelPackageSuffix(), artifact.name());
            case EXCEL_UTILITY, EXCEL_LISTENER -> BladeXModuleLayout.implClassPath(context,
                    context.referenceProfile().excelPackageSuffix(), artifact.name());
            case DDL -> BladeXModuleLayout.ddlPath(context);
            default -> null;
        };
    }

    private String mapperEntityName(String mapperName) {
        if (mapperName != null && mapperName.endsWith("Mapper") && mapperName.length() > "Mapper".length()) {
            return mapperName.substring(0, mapperName.length() - "Mapper".length());
        }
        return mapperName;
    }

    private String serviceEntityName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) return serviceName;
        String value = serviceName;
        if (value.startsWith("I") && value.length() > 1 && Character.isUpperCase(value.charAt(1))) {
            value = value.substring(1);
        }
        if (value.endsWith("ServiceImpl")) {
            return value.substring(0, value.length() - "ServiceImpl".length());
        }
        if (value.endsWith("Service")) {
            return value.substring(0, value.length() - "Service".length());
        }
        return value;
    }

    private List<IndexedClassInfo> exactMatches(PlannedArtifact artifact, ReferenceProjectIndex referenceIndex) {
        if (referenceIndex == null || !referenceIndex.isReady()) return List.of();
        String module = artifact.ownerModule();
        String expectedSide = artifact.moduleSide() == ModuleSide.API ? "API"
                : artifact.moduleSide() == ModuleSide.IMPL ? "IMPL" : null;
        return referenceIndex.getCachedClasses().stream()
                .filter(info -> artifact.name().equals(info.simpleName()))
                .filter(info -> module == null || module.isBlank() || module.equalsIgnoreCase(info.module()))
                .filter(info -> expectedSide == null || expectedSide.equalsIgnoreCase(info.side()))
                .filter(info -> artifact.declaredPackage() == null
                        || artifact.declaredPackage().equals(info.packageName()))
                .sorted(Comparator.comparing(IndexedClassInfo::relativePath))
                .toList();
    }

    private boolean referenceReady(ReferenceProjectIndex referenceIndex) {
        return referenceIndex != null && referenceIndex.isReady();
    }

    private boolean moduleExists(String module, ReferenceProjectIndex referenceIndex) {
        if (referenceIndex == null || !referenceIndex.isReady()) return false;
        return referenceIndex.getCachedClasses().stream()
                .anyMatch(info -> module.equalsIgnoreCase(info.module()));
    }

    private String moduleRoot(String module, ModuleSide side, ReferenceProjectIndex referenceIndex) {
        if (referenceIndex == null || !referenceIndex.isReady()) return null;
        String expectedSide = side == ModuleSide.API ? "API" : side == ModuleSide.IMPL ? "IMPL" : null;
        return referenceIndex.getCachedClasses().stream()
                .filter(info -> module.equalsIgnoreCase(info.module()))
                .filter(info -> expectedSide == null || expectedSide.equalsIgnoreCase(info.side()))
                .map(IndexedClassInfo::mavenModulePath)
                .filter(path -> path != null && !path.isBlank())
                .sorted()
                .findFirst().orElse(null);
    }

    private String normalizedModule(String ownerModule, String fallback) {
        return ownerModule == null || ownerModule.isBlank()
                ? fallback : ownerModule.toLowerCase(Locale.ROOT);
    }

    private Binding issue(PlannedArtifact artifact, String rule, String message) {
        String file = artifact.targetPath() != null ? artifact.targetPath()
                : "sub-plan:" + artifact.subPlanId() + "/" + artifact.name();
        return new Binding(artifact, PlanCompilationIssue.error(
                artifact.subPlanId(), rule, file, message + "; evidence=" + artifact.evidence()));
    }

    record BindingResult(List<PlannedArtifact> artifacts, List<PlanCompilationIssue> issues) {
        BindingResult {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    private record Binding(PlannedArtifact artifact, PlanCompilationIssue issue) {
    }
}
