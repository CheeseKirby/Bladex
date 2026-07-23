package org.springblade.aiworkflow.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles explicit reviewed-plan deliverables into structured artifacts without assigning physical paths. */
final class PlanArtifactCompiler {

    private static final Pattern HEADING = Pattern.compile("(?m)^\\s*(#{2,6})\\s+(.+?)\\s*$");
    private static final Pattern HEADING_CLASS = Pattern.compile(
            "^(?:\\d+\\s*[.)]\\s*)?([A-Z][A-Za-z0-9_]*)(?:\\s|\\(|$)");
    private static final Pattern PACKAGE = Pattern.compile(
            "(?i)(?:\\u5305(?:\\u8def\\u5f84)?|package)\\s*"
                    + "(?:[:\\uFF1A]\\s*)?[`*]*(org\\.springblade\\.[a-zA-Z0-9_.]+)");
    private static final Pattern FQCN = Pattern.compile(
            "(org\\.springblade\\.(?:[a-zA-Z0-9_]+\\.)+([A-Z][A-Za-z0-9_]*))");
    private static final Pattern CLASS_DECLARATION = Pattern.compile(
            "(?im)^\\s*[-*]?\\s*(?:\\u7c7b\\u540d|\\u6587\\u4ef6|\\u4f4d\\u7f6e|"
                    + "class(?:\\s+name)?|file|location)\\s*[:\\uFF1A]\\s*[`*]*"
                    + "([A-Z][A-Za-z0-9_]*)(?:\\.java)?[`*]*");
    private static final Pattern BOLD_DTO = Pattern.compile("\\*\\*([A-Z][A-Za-z0-9_]*DTO)\\*\\*");
    private static final Pattern BACKTICK_DTO = Pattern.compile("`([A-Z][A-Za-z0-9_]*DTO)(?:\\.java)?`");
    private static final Pattern EXPLICIT_DTO = Pattern.compile(
            "(?i)(?:create|generate|add|new|\\u521b\\u5efa|\\u751f\\u6210|\\u65b0\\u589e|"
                    + "\\u7f16\\u5199|\\u5b9e\\u73b0|\\u4ea4\\u4ed8)[^\\n]{0,100}?([A-Z][A-Za-z0-9_]*DTO)");
    private static final Pattern GLOBAL_ARTIFACT = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9_]*(?:ServiceImpl|ExcelReadListener|ReadListener|ExcelUtil|"
                    + "Controller|QVO|IVO|UVO|EVO|VO|DTO|Client|Excel))\\b");
    private static final Pattern MODULE_MENTION = Pattern.compile(
            "(?i)(?:\\b([a-z][a-z0-9_-]*)\\s*\\u6a21\\u5757|"
                    + "\\b([a-z][a-z0-9_-]*)\\s+module\\b)");
    private static final Pattern ENTITY_EXTENSION = Pattern.compile(
            "(?i)([A-Z][A-Za-z0-9_]*)\\s*(?:实体|Entity)\\s*(?:需|需要|新增|扩展|修改)");
    private static final Pattern SERVICE_RECEIVER = Pattern.compile("\\b([a-z][A-Za-z0-9_]*)Service\\s*\\.");
    private static final Pattern NEGATIVE_EXCEL = Pattern.compile(
            "(?i)needExcel\\s*=\\s*false|无需(?:自身|本模块)?[^\\n]{0,20}(?:导出|Excel)|"
                    + "(?:不需要|不生成|禁止生成)[^\\n]{0,20}Excel");

    private static final Set<String> FRAMEWORK_TYPES = Set.of(
            "BladeController", "BaseEntity", "TenantEntity", "BaseMapper", "BaseService",
            "BaseServiceImpl", "BaseEntityWrapper", "R", "IPage", "Query", "Condition",
            "HttpServletResponse");
    private static final Set<String> NON_BUSINESS_MODULE_WORDS = Set.of(
            "api", "service", "impl", "本", "当前", "服务", "接口");
    private static final Set<String> NON_ARTIFACT_HEADING_TYPES = Set.of(
            "Feign", "Excel", "Controller", "Entity", "Service", "Mapper", "Wrapper",
            "DTO", "API", "Impl", "Implementation");

    PlanArtifactCompilation compileCanonical(Long subPlanId, List<String> deliverableIds,
                                                 GenerationContext context) {
        CanonicalPlanContractV2 contract = context.planContract();
        if (contract == null) return new PlanArtifactCompilation(List.of(), List.of(
                PlanCompilationIssue.error(subPlanId, "CANONICAL-CONTRACT-MISSING", "canonicalContract",
                        "V2 sub-plan cannot compile without the canonical contract")));
        List<String> requestedIds = deliverableIds == null ? List.of() : deliverableIds;
        Set<String> requested = new LinkedHashSet<>(requestedIds);
        List<PlannedArtifact> artifacts = new ArrayList<>();
        List<PlanCompilationIssue> issues = new ArrayList<>();
        if (requested.size() != requestedIds.size()) {
            issues.add(PlanCompilationIssue.error(subPlanId, "CANONICAL-DELIVERABLE-DUPLICATE", "deliverableIds",
                    "Sub-plan assigns the same canonical deliverable more than once"));
        }
        for (String id : requested) {
            CanonicalPlanContractV2.Deliverable deliverable = contract.deliverables().stream()
                    .filter(item -> id.equals(item.id())).findFirst().orElse(null);
            if (deliverable == null) {
                issues.add(PlanCompilationIssue.error(subPlanId, "CANONICAL-DELIVERABLE-UNKNOWN", id,
                        "Sub-plan references an unknown canonical deliverable"));
                continue;
            }
            artifacts.addAll(canonicalArtifacts(subPlanId, deliverable, context));
        }
        Set<String> emittedTypes = new LinkedHashSet<>();
        for (PlannedArtifact artifact : artifacts) emittedTypes.add(artifact.name());
        for (String id : requested) {
            CanonicalPlanContractV2.Deliverable deliverable = contract.deliverables().stream()
                    .filter(item -> id.equals(item.id())).findFirst().orElse(null);
            if (deliverable == null || "PROHIBIT".equals(deliverable.action())) continue;
            for (String providedType : deliverable.providesTypes()) {
                if (!emittedTypes.contains(providedType)) {
                    issues.add(PlanCompilationIssue.error(subPlanId, "CANONICAL-PROVIDED-TYPE-NOT-EMITTED", providedType,
                            "Canonical deliverable " + id + " declares a type that produced no artifact"));
                }
            }
        }
        if (artifacts.isEmpty() && issues.isEmpty()) {
            issues.add(PlanCompilationIssue.error(subPlanId, "CANONICAL-DELIVERABLE-EMPTY", "deliverableIds",
                    "V2 sub-plan has no canonical deliverables"));
        }
        return new PlanArtifactCompilation(artifacts, issues);
    }

    private List<PlannedArtifact> canonicalArtifacts(Long subPlanId,
                                                      CanonicalPlanContractV2.Deliverable deliverable,
                                                      GenerationContext context) {
        String entity = context.identity().entityName();
        Map<String, Object> moduleContract = context.planContract().modules().stream()
                .filter(item -> deliverable.moduleId() != null && deliverable.moduleId().equals(String.valueOf(item.get("id"))))
                .findFirst().orElse(null);
        String declaredModuleName = moduleContract == null ? null : stringValue(moduleContract.get("name"));
        String module = declaredModuleName == null ? context.identity().moduleName() : declaredModuleName;
        String moduleBasePackage = moduleContract == null ? null : stringValue(moduleContract.get("basePackage"));
        ArtifactAction action = parseAction(deliverable.action());
        ModuleSide side = parseSide(deliverable.moduleSide());
        String evidence = "canonical-plan-v2:" + deliverable.id();
        List<PlannedArtifact> result = new ArrayList<>();
        switch (deliverable.kind()) {
            case "DDL" -> result.add(canonical(subPlanId, module + "Migration", ArtifactKind.DDL,
                    action, module, ModuleSide.DOC, evidence));
            case "ENTITY" -> result.add(canonical(subPlanId,
                    preferredType(deliverable, entity, name -> true),
                    ArtifactKind.ENTITY, action, module, ModuleSide.API, evidence));
            case "VO" -> {
                List<String> names = deliverable.providesTypes().isEmpty()
                        ? List.of(entity + "QVO", entity + "IVO", entity + "UVO", entity + "VO", entity + "EVO")
                        : deliverable.providesTypes();
                for (String name : names) {
                    result.add(canonical(subPlanId, name, ArtifactKind.VO, action, module, ModuleSide.API, evidence));
                }
            }
            case "MAPPER" -> {
                String mapperName = preferredType(deliverable, entity + "Mapper", name -> name.endsWith("Mapper"));
                result.add(canonical(subPlanId, mapperName, ArtifactKind.MAPPER,
                        action, module, ModuleSide.IMPL, evidence));
                result.add(canonical(subPlanId, mapperName, ArtifactKind.MAPPER_XML,
                        action, module, ModuleSide.IMPL, evidence));
            }
            case "SERVICE" -> {
                List<String> serviceTypes = deliverable.providesTypes();
                String interfaceName = serviceTypes.stream()
                        .filter(name -> name.startsWith("I") && name.endsWith("Service"))
                        .findFirst().orElseGet(() -> hasText(deliverable.className())
                                && deliverable.className().endsWith("Service")
                                && !deliverable.className().endsWith("ServiceImpl")
                                ? deliverable.className() : "I" + entity + "Service");
                String implementationName = serviceTypes.stream().filter(name -> name.endsWith("ServiceImpl"))
                        .findFirst().orElse(entity + "ServiceImpl");
                result.add(canonical(subPlanId, interfaceName, ArtifactKind.SERVICE_INTERFACE,
                        action, module, ModuleSide.IMPL, evidence));
                result.add(canonical(subPlanId, implementationName, ArtifactKind.SERVICE_IMPL,
                        action, module, ModuleSide.IMPL, evidence));
            }
            case "WRAPPER" -> result.add(canonical(subPlanId,
                    preferredType(deliverable, entity + "Wrapper", name -> name.endsWith("Wrapper")),
                    ArtifactKind.WRAPPER, action, module, ModuleSide.IMPL, evidence));
            case "CONTROLLER" -> result.add(canonical(subPlanId,
                    preferredType(deliverable, entity + "Controller", name -> true),
                    ArtifactKind.CONTROLLER, action, module, ModuleSide.IMPL, evidence));
            case "FEIGN" -> {
                List<String> names = deliverable.providesTypes().isEmpty()
                        ? List.of(preferredType(deliverable, "I" + entity + "Client", name -> true))
                        : deliverable.providesTypes();
                for (String name : names) {
                    ArtifactKind kind = name.endsWith("Impl") || name.endsWith("Provider")
                            ? ArtifactKind.FEIGN_PROVIDER : ArtifactKind.FEIGN_INTERFACE;
                    result.add(canonical(subPlanId, name, kind, action, module,
                            kind == ArtifactKind.FEIGN_INTERFACE ? ModuleSide.API : ModuleSide.IMPL, evidence));
                }
            }
            case "EXCEL" -> {
                List<String> names = deliverable.providesTypes().isEmpty()
                        ? List.of(entity + "EVO") : deliverable.providesTypes();
                for (String name : names) result.add(canonical(subPlanId, name, ArtifactKind.EXCEL_MODEL,
                        action, module, side == ModuleSide.UNKNOWN ? ModuleSide.API : side, evidence));
            }
            case "CONFIG" -> {
                result.add(canonical(subPlanId, "bootstrap.yml", ArtifactKind.CONFIG,
                        action, module, ModuleSide.IMPL, evidence));
                result.add(canonical(subPlanId, "application-dev.yml", ArtifactKind.CONFIG,
                        action, module, ModuleSide.IMPL, evidence));
            }
            default -> {
                if (hasText(deliverable.className())) result.add(canonical(subPlanId, deliverable.className(),
                        ArtifactKind.OTHER, action, module, side, evidence));
            }
        }
        if (moduleBasePackage != null) {
            List<PlannedArtifact> owned = new ArrayList<>();
            for (PlannedArtifact artifact : result) {
                String declaredPackage = canonicalPackage(moduleBasePackage, artifact.kind());
                owned.add(new PlannedArtifact(artifact.subPlanId(), artifact.name(), artifact.kind(), artifact.action(),
                        artifact.ownerModule(), artifact.moduleSide(), declaredPackage, artifact.targetPath(),
                        artifact.required(), artifact.evidence()));
            }
            return owned;
        }
        return result;
    }

    private String canonicalPackage(String basePackage, ArtifactKind kind) {
        return switch (kind) {
            case ENTITY -> basePackage + ".entity";
            case VO, DTO, EXCEL_MODEL -> basePackage + ".vo";
            case MAPPER -> basePackage + ".mapper";
            case SERVICE_INTERFACE -> basePackage + ".service";
            case SERVICE_IMPL -> basePackage + ".service.impl";
            case WRAPPER -> basePackage + ".wrapper";
            case CONTROLLER -> basePackage + ".controller";
            case FEIGN_INTERFACE, FEIGN_PROVIDER -> basePackage + ".feign";
            default -> null;
        };
    }

    private PlannedArtifact canonical(Long subPlanId, String name, ArtifactKind kind,
                                      ArtifactAction action, String module, ModuleSide side, String evidence) {
        return new PlannedArtifact(subPlanId, name, kind, action, module, side,
                null, null, true, evidence);
    }

    private String preferredType(CanonicalPlanContractV2.Deliverable deliverable, String fallback,
                                 java.util.function.Predicate<String> predicate) {
        if (hasText(deliverable.className()) && predicate.test(deliverable.className())) {
            return deliverable.className();
        }
        return deliverable.providesTypes().stream().filter(predicate).findFirst().orElse(fallback);
    }

    private ArtifactAction parseAction(String value) {
        try { return value == null ? ArtifactAction.CREATE : ArtifactAction.valueOf(value); }
        catch (IllegalArgumentException ignored) { return ArtifactAction.CREATE; }
    }

    private ModuleSide parseSide(String value) {
        try { return value == null ? ModuleSide.UNKNOWN : ModuleSide.valueOf(value); }
        catch (IllegalArgumentException ignored) { return ModuleSide.UNKNOWN; }
    }

    PlanArtifactCompilation compile(Long subPlanId, String title, String content, GenerationContext context) {
        String safeContent = content == null ? "" : content;
        String currentModule = context.identity().moduleName();
        List<PlannedArtifact> artifacts = new ArrayList<>();
        List<PlanCompilationIssue> issues = new ArrayList<>();

        if (NEGATIVE_EXCEL.matcher(safeContent).find()) {
            artifacts.add(new PlannedArtifact(subPlanId, context.identity().entityName() + "Excel",
                    ArtifactKind.EXCEL_MODEL, ArtifactAction.PROHIBIT, currentModule, ModuleSide.IMPL,
                    null, null, false, "Explicit Excel negation in reviewed plan"));
        }

        Map<String, PlannedArtifact> unique = new LinkedHashMap<>();
        for (String dto : extractDtoNames(safeContent)) {
            PlannedArtifact artifact = new PlannedArtifact(subPlanId, dto, ArtifactKind.DTO,
                    ArtifactAction.CREATE, currentModule, ModuleSide.API,
                    inferDeclaredPackageAround(safeContent, dto), null, true,
                    "Explicit DTO deliverable " + dto);
            unique.put(key(artifact), artifact);
        }
        List<Section> planSections = sections(safeContent);
        if (planSections.isEmpty()) {
            for (PlannedArtifact artifact : extractProseArtifacts(subPlanId, safeContent, context)) {
                addIfBusiness(unique, artifact);
            }
        }

        for (Section section : planSections) {
            String headingLower = section.heading().toLowerCase(Locale.ROOT);
            HeadingArtifact headingArtifact = extractHeadingArtifact(section.heading());
            String declaredPackage = headingArtifact != null
                    ? headingArtifact.declaredPackage() : extractDeclaredPackage(section.body());
            String ownerModule = inferOwnerModule(section.heading() + "\n" + section.body(), declaredPackage, currentModule);
            List<String> classNames = new ArrayList<>(extractDeclaredClassNames(section.body()));
            for (String headingClass : extractHeadingClassNames(section.heading())) {
                if (!classNames.contains(headingClass)) classNames.add(headingClass);
            }

            if (headingArtifact != null && headingArtifact.kind() != null) {
                boolean modifiesExisting = headingArtifact.kind() == ArtifactKind.CONTROLLER
                        && (containsAny(section.combinedLower(), "\u65b0\u589e\u7aef\u70b9", "\u4e2d\u65b0\u589e", "\u6269\u5c55", "\u4fee\u6539")
                        || containsAny(section.combinedLower(), "extend", "extension", "modify"));
                ArtifactAction action = modifiesExisting ? ArtifactAction.MODIFY : ArtifactAction.CREATE;
                addIfBusiness(unique, artifact(subPlanId, headingArtifact.name(), headingArtifact.kind(),
                        action, ownerModule, headingArtifact.moduleSide(), declaredPackage, section));
            }

            if (containsAny(headingLower, "feign 客户端接口", "feign接口", "feign client interface")) {
                for (String name : classNames) {
                    addIfBusiness(unique, artifact(subPlanId, name, ArtifactKind.FEIGN_INTERFACE,
                            ArtifactAction.CREATE, ownerModule, ModuleSide.API, declaredPackage, section));
                }
                continue;
            }
            if (containsAny(headingLower, "feign \u5b9e\u73b0\u7c7b", "feign\u670d\u52a1\u7aef", "feign provider", "feign implementation", "feign \u5b9e\u73b0")) {
                for (String name : classNames) {
                    addIfBusiness(unique, artifact(subPlanId, name, ArtifactKind.FEIGN_PROVIDER,
                            ArtifactAction.CREATE, ownerModule, ModuleSide.IMPL, declaredPackage, section));
                }
                continue;
            }

            if (headingLower.contains("controller")) {
                boolean modifiesExisting = containsAny(section.combinedLower(), "新增端点", "中新增", "扩展", "修改")
                        || containsAny(section.combinedLower(), "extend", "extension", "modify");
                ArtifactAction action = modifiesExisting ? ArtifactAction.MODIFY : ArtifactAction.CREATE;
                for (String name : classNames) {
                    addIfBusiness(unique, artifact(subPlanId, name, ArtifactKind.CONTROLLER,
                            action, ownerModule, ModuleSide.IMPL, declaredPackage, section));
                }
            }

            if (headingLower.contains("excel") || headingLower.contains("导出")) {
                for (String name : classNames) {
                    if (!name.endsWith("EVO") && !name.endsWith("Excel") && !name.endsWith("ExcelUtil")) continue;
                    ModuleSide side = name.endsWith("EVO") ? ModuleSide.API : ModuleSide.IMPL;
                    addIfBusiness(unique, artifact(subPlanId, name, ArtifactKind.EXCEL_MODEL,
                            ArtifactAction.CREATE, ownerModule, side, declaredPackage, section));
                }
            }

            if (containsAny(headingLower, "entity 扩展", "实体扩展", "entity修改", "实体修改")) {
                List<String> entities = extractEntityExtensionNames(section.body());
                if (entities.isEmpty()) {
                    issues.add(PlanCompilationIssue.error(subPlanId, "PLAN-ARTIFACT-AMBIGUOUS", null,
                            "Entity extension section does not identify an exact entity type: " + section.heading()));
                }
                for (String name : entities) {
                    addIfBusiness(unique, artifact(subPlanId, name, ArtifactKind.ENTITY,
                            ArtifactAction.MODIFY, ownerModule, ModuleSide.API, declaredPackage, section));
                }
            }

            if (isServiceContributionSection(section)) {
                List<String> services = extractServiceNames(section.body());
                for (String className : classNames) {
                    if (((className.startsWith("I") && className.endsWith("Service"))
                            || className.endsWith("ServiceImpl")) && !services.contains(className)) {
                        services.add(className);
                    }
                }
                if (services.isEmpty() && ownerModule != null && !ownerModule.equals(currentModule)) {
                    String inferred = "I" + capitalize(ownerModule) + "Service";
                    issues.add(PlanCompilationIssue.error(subPlanId, "PLAN-ARTIFACT-AMBIGUOUS", null,
                            "Service extension does not declare an exact service type; inferred candidate " + inferred
                                    + " requires reference binding confirmation"));
                }
                for (String name : services) {
                    ArtifactKind kind = name.endsWith("ServiceImpl")
                            ? ArtifactKind.SERVICE_IMPL : ArtifactKind.SERVICE_INTERFACE;
                    addIfBusiness(unique, artifact(subPlanId, name, kind,
                            ArtifactAction.MODIFY, ownerModule, ModuleSide.IMPL, declaredPackage, section));
                }
            }

            if (containsAny(section.combinedLower(), "alter table", "表需 alter", "数据库表需")) {
                PlannedArtifact ddl = new PlannedArtifact(subPlanId, ownerModule + "Migration",
                        ArtifactKind.DDL, ArtifactAction.EXTEND, ownerModule, ModuleSide.DOC,
                        null, null, true, "Cross-module DDL extension in " + section.heading());
                unique.put(key(ddl), ddl);
            }
        }

        artifacts.addAll(unique.values());
        return new PlanArtifactCompilation(artifacts, issues);
    }

    private PlannedArtifact artifact(Long subPlanId, String name, ArtifactKind kind, ArtifactAction action,
                                     String ownerModule, ModuleSide side, String declaredPackage, Section section) {
        return new PlannedArtifact(subPlanId, name, kind, action, ownerModule, side,
                declaredPackage, null, true, section.heading());
    }

    private void addIfBusiness(Map<String, PlannedArtifact> result, PlannedArtifact artifact) {
        if (artifact == null || artifact.name() == null || FRAMEWORK_TYPES.contains(artifact.name())) return;
        result.putIfAbsent(key(artifact), artifact);
    }

    private String key(PlannedArtifact artifact) {
        return artifact.kind() + "|" + artifact.action() + "|" + safe(artifact.ownerModule()) + "|" + artifact.name();
    }

    private boolean isServiceContributionSection(Section section) {
        String heading = section.heading().toLowerCase(Locale.ROOT);
        if (!heading.contains("service") && !heading.contains("\u670d\u52a1")) return false;
        return containsAny(section.combinedLower(), "\u6269\u5c55", "\u4fee\u6539", "\u65b0\u589e",
                "\u8865\u5145", "\u65b9\u6cd5\u58f0\u660e", "\u59d4\u6258", "\u5b9e\u73b0",
                "extend", "modify", "add method", "implementation");
    }

    private List<PlannedArtifact> extractProseArtifacts(Long subPlanId, String content,
                                                          GenerationContext context) {
        List<PlannedArtifact> result = new ArrayList<>();
        Matcher matcher = GLOBAL_ARTIFACT.matcher(content);
        boolean hasNamedReadListener = false;
        while (matcher.find()) {
            String name = matcher.group(1);
            if (FRAMEWORK_TYPES.contains(name) || NON_ARTIFACT_HEADING_TYPES.contains(name)
                    || Set.of("ReadListener", "RestController", "EasyExcel", "FeignClient",
                    "QVO", "IVO", "UVO", "EVO", "VO", "DTO").contains(name)) {
                continue;
            }
            int start = Math.max(0, matcher.start() - 140);
            int end = Math.min(content.length(), matcher.end() + 180);
            String nearby = content.substring(start, end).toLowerCase(Locale.ROOT);
            ArtifactKind kind = classifyProseArtifact(name);
            if (kind == null) continue;
            if (kind == ArtifactKind.EXCEL_LISTENER) hasNamedReadListener = true;
            ModuleSide side = switch (kind) {
                case DTO, VO, FEIGN_INTERFACE -> ModuleSide.API;
                case DDL -> ModuleSide.DOC;
                default -> ModuleSide.IMPL;
            };
            String declaredPackage = inferDeclaredPackageAround(content, name);
            result.add(new PlannedArtifact(subPlanId, name, kind, ArtifactAction.CREATE,
                    context.identity().moduleName(), side, declaredPackage, null, true,
                    "Explicit class mentioned in reviewed prose near: " + compactEvidence(content.substring(start, end))));
        }
        if (content.toLowerCase(Locale.ROOT).contains("service")
                && containsAny(content.toLowerCase(Locale.ROOT), "exportdata", "importdata")) {
            String entity = context.identity().entityName();
            result.add(new PlannedArtifact(subPlanId, "I" + entity + "Service", ArtifactKind.SERVICE_INTERFACE,
                    ArtifactAction.MODIFY, context.identity().moduleName(), ModuleSide.IMPL,
                    null, null, true, "Reviewed prose adds service-layer operations"));
            result.add(new PlannedArtifact(subPlanId, entity + "ServiceImpl", ArtifactKind.SERVICE_IMPL,
                    ArtifactAction.MODIFY, context.identity().moduleName(), ModuleSide.IMPL,
                    null, null, true, "Reviewed prose adds service-layer implementations"));
        }
        if (content.contains("ExcelUtil") && result.stream().noneMatch(artifact ->
                artifact.kind() == ArtifactKind.EXCEL_UTILITY)) {
            result.add(new PlannedArtifact(subPlanId, "ExcelUtil", ArtifactKind.EXCEL_UTILITY,
                    ArtifactAction.CREATE, context.identity().moduleName(), ModuleSide.IMPL,
                    null, null, true, "Reviewed plan explicitly requires an ExcelUtil utility"));
        }
        if (!hasNamedReadListener && content.contains("ReadListener")
                && (content.toLowerCase(Locale.ROOT).contains("excel") || content.contains("导入"))) {
            String name = context.identity().entityName() + "ExcelReadListener";
            result.add(new PlannedArtifact(subPlanId, name, ArtifactKind.EXCEL_LISTENER,
                    ArtifactAction.CREATE, context.identity().moduleName(), ModuleSide.IMPL,
                    null, null, true, "Reviewed plan requires a concrete ReadListener implementation"));
        }
        return result;
    }

    private ArtifactKind classifyProseArtifact(String name) {
        if (name.endsWith("Controller")) return ArtifactKind.CONTROLLER;
        if (name.endsWith("ServiceImpl")) return ArtifactKind.SERVICE_IMPL;
        if (name.startsWith("I") && name.endsWith("Client")) {
            return ArtifactKind.FEIGN_INTERFACE;
        }
        if (name.endsWith("Client")) return ArtifactKind.FEIGN_PROVIDER;
        if (name.endsWith("DTO")) return ArtifactKind.DTO;
        if (name.endsWith("QVO") || name.endsWith("IVO") || name.endsWith("UVO")
                || name.endsWith("EVO") || name.endsWith("VO")) return ArtifactKind.VO;
        if (name.endsWith("ExcelReadListener") || name.endsWith("ReadListener")) {
            return ArtifactKind.EXCEL_LISTENER;
        }
        if (name.endsWith("ExcelUtil")) return ArtifactKind.EXCEL_UTILITY;
        if (name.endsWith("Excel")) return ArtifactKind.EXCEL_MODEL;
        return null;
    }

    private String compactEvidence(String value) {
        return value.replaceAll("\s+", " ").trim();
    }

    private List<String> extractDtoNames(String content) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Pattern pattern : List.of(BOLD_DTO, BACKTICK_DTO, EXPLICIT_DTO)) {
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) names.putIfAbsent(matcher.group(1), matcher.group(1));
        }
        return new ArrayList<>(names.values());
    }

    private List<String> extractHeadingClassNames(String heading) {
        List<String> names = new ArrayList<>();
        Matcher fqcn = FQCN.matcher(heading == null ? "" : heading);
        if (fqcn.find() && !FRAMEWORK_TYPES.contains(fqcn.group(2))) names.add(fqcn.group(2));
        Matcher matcher = HEADING_CLASS.matcher(heading == null ? "" : heading.trim());
        if (matcher.find() && !FRAMEWORK_TYPES.contains(matcher.group(1))
                && !NON_ARTIFACT_HEADING_TYPES.contains(matcher.group(1))) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private HeadingArtifact extractHeadingArtifact(String heading) {
        String safeHeading = heading == null ? "" : heading.trim();
        Matcher matcher = FQCN.matcher(safeHeading);
        if (!matcher.find()) return null;
        String fqcn = matcher.group(1);
        String name = matcher.group(2);
        String declaredPackage = fqcn.substring(0, fqcn.length() - name.length() - 1);
        String label = safeHeading.substring(0, matcher.start()).toLowerCase(Locale.ROOT);

        ArtifactKind kind = null;
        ModuleSide side = ModuleSide.UNKNOWN;
        if (label.contains("controller") || label.contains("\u63a7\u5236\u5668")) {
            kind = ArtifactKind.CONTROLLER;
            side = ModuleSide.IMPL;
        } else if (label.contains("excel") || label.contains("\u5bfc\u5165") || label.contains("\u5bfc\u51fa")) {
            kind = ArtifactKind.EXCEL_MODEL;
            side = name.endsWith("EVO") ? ModuleSide.API : ModuleSide.IMPL;
        } else if (label.contains("entity") || label.contains("\u5b9e\u4f53")) {
            kind = ArtifactKind.ENTITY;
            side = ModuleSide.API;
        } else if (label.contains("dto") || name.endsWith("DTO")) {
            kind = ArtifactKind.DTO;
            side = ModuleSide.API;
        } else if (containsAny(label, "qvo", "ivo", "uvo", "evo", " vo", "vo:")
                || name.endsWith("QVO") || name.endsWith("IVO") || name.endsWith("UVO")
                || name.endsWith("EVO") || name.endsWith("VO")) {
            kind = ArtifactKind.VO;
            side = ModuleSide.API;
        }
        return new HeadingArtifact(name, kind, side, declaredPackage);
    }

    private List<String> extractDeclaredClassNames(String body) {
        List<String> names = new ArrayList<>();
        Matcher matcher = CLASS_DECLARATION.matcher(body);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!FRAMEWORK_TYPES.contains(name) && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private List<String> extractEntityExtensionNames(String body) {
        List<String> names = new ArrayList<>();
        Matcher matcher = ENTITY_EXTENSION.matcher(body);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!FRAMEWORK_TYPES.contains(name) && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private List<String> extractServiceNames(String body) {
        List<String> names = new ArrayList<>();
        for (String className : extractDeclaredClassNames(body)) {
            if ((className.startsWith("I") && className.endsWith("Service")) || className.endsWith("ServiceImpl")) {
                names.add(className);
            }
        }
        Matcher receiver = SERVICE_RECEIVER.matcher(body);
        while (receiver.find()) {
            String name = "I" + capitalize(receiver.group(1)) + "Service";
            if (!names.contains(name)) names.add(name);
        }
        return names;
    }

    private List<Section> sections(String content) {
        List<Section> result = new ArrayList<>();
        Matcher matcher = HEADING.matcher(content);
        List<Integer> starts = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
            headings.add(matcher.group(2).trim());
        }
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int bodyStart = content.indexOf('\n', start);
            if (bodyStart < 0) bodyStart = content.length();
            int end = i + 1 < starts.size() ? starts.get(i + 1) : content.length();
            result.add(new Section(headings.get(i), content.substring(Math.min(bodyStart + 1, end), end)));
        }
        return result;
    }

    private String extractDeclaredPackage(String text) {
        Matcher matcher = PACKAGE.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String inferDeclaredPackageAround(String content, String className) {
        int index = content.indexOf(className);
        if (index < 0) return null;
        int lineStart = content.lastIndexOf('\n', index);
        int lineEnd = content.indexOf('\n', index);
        lineStart = lineStart < 0 ? 0 : lineStart + 1;
        lineEnd = lineEnd < 0 ? content.length() : lineEnd;
        return extractDeclaredPackage(content.substring(lineStart, lineEnd));
    }

    private String inferOwnerModule(String text, String declaredPackage, String currentModule) {
        if (declaredPackage != null && declaredPackage.startsWith("org.springblade.")) {
            String remaining = declaredPackage.substring("org.springblade.".length());
            int dot = remaining.indexOf('.');
            return dot > 0 ? remaining.substring(0, dot) : remaining;
        }
        Matcher matcher = MODULE_MENTION.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String candidate = raw.toLowerCase(Locale.ROOT);
            if (!NON_BUSINESS_MODULE_WORDS.contains(candidate)) return candidate;
        }
        return currentModule;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) return value;
        StringBuilder result = new StringBuilder();
        for (String part : value.split("[-_]")) {
            if (!part.isBlank()) result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record HeadingArtifact(String name, ArtifactKind kind, ModuleSide moduleSide,
                                   String declaredPackage) {
    }

    private record Section(String heading, String body) {
        String combinedLower() {
            return (heading + "\n" + body).toLowerCase(Locale.ROOT);
        }
    }
    private String stringValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
