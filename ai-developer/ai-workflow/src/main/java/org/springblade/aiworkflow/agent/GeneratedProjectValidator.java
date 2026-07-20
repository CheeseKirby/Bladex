package org.springblade.aiworkflow.agent;

import org.springblade.aiworkflow.enums.ClassType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Project-level quality gate for generated files and reference-source contracts. */
public final class GeneratedProjectValidator {

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Z][A-Za-z0-9_]*)");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)(?:\\.\\*)?\\s*;");
    private static final Pattern FIELD = Pattern.compile(
            "(?:private|protected|public)\\s+(?:final\\s+)?([A-Z][A-Za-z0-9_<>?, .]*)\\s+([a-z][A-Za-z0-9_]*)\\s*[;=]");
    private static final Pattern METHOD_CALL = Pattern.compile("\\b([a-z][A-Za-z0-9_]*)\\.([a-zA-Z][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern XML_PROPERTY = Pattern.compile("property=\"([a-zA-Z][A-Za-z0-9_]*)\"");
    private static final Pattern RESULT_MAP = Pattern.compile(
            "(?is)<resultMap\\b[^>]*\\btype=\"(?:[\\w.]+\\.)?([A-Z][A-Za-z0-9_]*)\"[^>]*>(.*?)</resultMap>");
    private static final Pattern EXTENDS_TYPE = Pattern.compile(
            "\\b(?:class|interface)\\s+([A-Z][A-Za-z0-9_]*)(?:\\s*<[^>{}]+>)?\\s+extends\\s+([\\w.]+)");
    private static final Pattern JAVA_FIELD = Pattern.compile(
            "\\b(?:private|protected|public)\\s+(?:static\\s+)?(?:final\\s+)?[A-Za-z0-9_<>?, .]+\\s+([a-z][A-Za-z0-9_]*)\\s*(?:[;=])");
    private static final Set<String> EXTERNAL_PREFIXES = Set.of(
            "java.", "javax.", "jakarta.", "lombok.", "org.springframework.", "com.baomidou.",
            "io.swagger.", "cn.hutool.", "com.fasterxml.", "org.apache.", "org.slf4j.",
            "org.springblade.core.", "org.springblade.common.", "org.springblade.modules.");
    private static final Set<String> BASE_ENTITY_FIELDS = Set.of(
            "id", "createUser", "createDept", "createTime", "updateUser", "updateTime",
            "status", "isDeleted", "tenantId");
    private static final Set<String> INTERNAL_SERVICE_HELPER_PREFIXES = Set.of(
            "check", "validate", "verify", "assert", "ensure");

    public List<Issue> validate(List<GeneratedFile> files, List<ExpectedDeliverable> expected,
                                GenerationContext context, ReferenceProjectIndex referenceIndex) {
        List<Issue> issues = new ArrayList<>();
        Map<String, List<GeneratedFile>> byPath = new LinkedHashMap<>();
        if (files != null) {
            for (GeneratedFile file : files) {
                byPath.computeIfAbsent(normalize(file.getFilePath()), k -> new ArrayList<>()).add(file);
            }
        }
        List<GeneratedFile> canonicalFiles = byPath.values().stream()
                .filter(group -> !group.isEmpty())
                .map(group -> group.get(0))
                .toList();

        for (ExpectedDeliverable deliverable : expected == null ? List.<ExpectedDeliverable>of() : expected) {
            if (deliverable.required() && !byPath.containsKey(normalize(deliverable.targetPath()))) {
                issues.add(error("DELIVERABLE-MISSING", deliverable.targetPath(),
                        "Required deliverable was not generated for sub-plan " + deliverable.subPlanId()));
            }
        }
        byPath.forEach((path, duplicates) -> {
            if (duplicates.size() > 1) issues.add(error("DUPLICATE-PATH", path,
                    "The same target path was generated " + duplicates.size() + " times"));
        });

        Map<String, GeneratedFile> fqcnToFile = new LinkedHashMap<>();
        Set<String> generatedFqcns = new LinkedHashSet<>();
        Map<String, TypeShape> typeShapesBySimpleName = new HashMap<>();
        for (GeneratedFile file : canonicalFiles) {
            String path = normalize(file.getFilePath());
            if (path == null || !path.endsWith(".java") || file.getContent() == null) continue;
            Matcher pkgMatcher = PACKAGE.matcher(file.getContent());
            Matcher typeMatcher = TYPE.matcher(file.getContent());
            if (!pkgMatcher.find() || !typeMatcher.find()) continue;
            String pkg = pkgMatcher.group(1);
            String type = typeMatcher.group(1);
            String fqcn = pkg + "." + type;
            String expectedSuffix = "/src/main/java/" + pkg.replace('.', '/') + "/" + type + ".java";
            if (!path.endsWith(expectedSuffix)) {
                issues.add(error("PATH-PACKAGE-MISMATCH", path,
                        "Declared " + fqcn + " does not match the physical path"));
            }
            GeneratedFile previous = fqcnToFile.putIfAbsent(fqcn, file);
            if (previous != null) {
                issues.add(error("DUPLICATE-FQCN", path,
                        fqcn + " is also generated at " + previous.getFilePath()));
            }
            generatedFqcns.add(fqcn);
            Set<String> javaFields = new LinkedHashSet<>();
            Matcher fieldMatcher = JAVA_FIELD.matcher(file.getContent());
            while (fieldMatcher.find()) javaFields.add(fieldMatcher.group(1));
            typeShapesBySimpleName.putIfAbsent(type,
                    new TypeShape(javaFields, extractParentSimpleName(type, file.getContent())));

            String module = BladeXModuleLayout.moduleOfPath(path);
            if (module != null && !module.equals(context.identity().moduleName())) {
                issues.add(error("MODULE-IDENTITY-MISMATCH", path,
                        "File belongs to module " + module + " but canonical module is " + context.identity().moduleName()));
            }
        }

        Map<String, Set<String>> fieldsBySimpleName = resolveEffectiveFields(typeShapesBySimpleName);

        Set<String> referenceFqcns = new HashSet<>();
        Map<String, IndexedClassInfo> referencesBySimple = new HashMap<>();
        boolean referenceReady = referenceIndex != null && referenceIndex.isReady();
        if (referenceReady) {
            for (IndexedClassInfo info : referenceIndex.getCachedClasses()) {
                referenceFqcns.add(info.packageName() + "." + info.simpleName());
                referencesBySimple.putIfAbsent(info.simpleName(), info);
            }
        }
        GeneratedFile servicePom = canonicalFiles.stream()
                .filter(f -> normalize(f.getFilePath()).equals(BladeXModuleLayout.implPomPath(context)))
                .findFirst().orElse(null);
        for (GeneratedFile file : canonicalFiles) {
            String path = normalize(file.getFilePath());
            String content = file.getContent();
            if (path == null || content == null || !path.endsWith(".java")) continue;
            Matcher imports = IMPORT.matcher(content);
            while (imports.find()) {
                String imported = imports.group(1);
                if (!imported.startsWith("org.springblade.")) continue;
                if (generatedFqcns.contains(imported) || referenceFqcns.contains(imported)
                        || hasExternalPrefix(imported) || !referenceReady) continue;
                issues.add(error("UNRESOLVED-PROJECT-IMPORT", path,
                        "Project source does not provide imported type " + imported));
            }
            if (path.startsWith("blade-service/") && servicePom != null) {
                boolean importsGeneratedApi = generatedFqcns.stream()
                        .filter(fqcn -> fqcn.startsWith(context.identity().basePackage() + "."))
                        .anyMatch(content::contains);
                if (importsGeneratedApi && !servicePom.getContent().contains(
                        "<artifactId>" + context.identity().apiModuleName() + "</artifactId>")) {
                    issues.add(error("MAVEN-INTERNAL-DEPENDENCY-MISSING", servicePom.getFilePath(),
                            "Service module imports generated API classes without depending on "
                                    + context.identity().apiModuleName()));
                }
            }
            validateReferenceMethodCalls(path, content, referencesBySimple, issues);
        }

        validateSkeleton(canonicalFiles, context, issues);
        validateMapperXmlProperties(canonicalFiles, fieldsBySimpleName, issues);
        validateDatabaseContract(canonicalFiles, context, fieldsBySimpleName, issues);
        validateControllerServiceClosure(canonicalFiles, context, issues);
        validatePomModel(canonicalFiles, context, issues);
        return issues;
    }

    private void validateReferenceMethodCalls(String path, String content,
                                              Map<String, IndexedClassInfo> referencesBySimple, List<Issue> issues) {
        Map<String, IndexedClassInfo> variableTypes = new HashMap<>();
        Matcher fields = FIELD.matcher(content);
        while (fields.find()) {
            String simpleType = fields.group(1).replaceAll("<.*>", "").trim();
            IndexedClassInfo info = referencesBySimple.get(simpleType);
            if (info != null && !info.publicMethodSignatures().isEmpty()) variableTypes.put(fields.group(2), info);
        }
        Matcher calls = METHOD_CALL.matcher(content);
        while (calls.find()) {
            IndexedClassInfo info = variableTypes.get(calls.group(1));
            if (info == null) continue;
            String method = calls.group(2);
            boolean exists = info.publicMethodSignatures().stream().anyMatch(signature -> signature.startsWith(method + "("));
            if (!exists) {
                issues.add(error("REFERENCE-API-METHOD-MISSING", path,
                        info.simpleName() + " does not declare method " + method + " in reference source"));
            }
        }
    }

    private void validateSkeleton(List<GeneratedFile> files, GenerationContext context, List<Issue> issues) {
        Set<String> paths = new HashSet<>();
        for (GeneratedFile file : files) paths.add(normalize(file.getFilePath()));
        boolean hasApi = paths.stream().anyMatch(path -> path.startsWith("blade-service-api/"));
        boolean hasImpl = paths.stream().anyMatch(path -> path.startsWith("blade-service/"));
        if (hasApi && !paths.contains(BladeXModuleLayout.apiPomPath(context))) {
            issues.add(error("API-POM-MISSING", BladeXModuleLayout.apiPomPath(context), "Generated API module has no pom.xml"));
        }
        if (hasImpl) {
            for (String required : List.of(BladeXModuleLayout.implPomPath(context),
                    BladeXModuleLayout.applicationPath(context), BladeXModuleLayout.bootstrapPath(context))) {
                if (!paths.contains(required)) issues.add(error("SERVICE-SKELETON-MISSING", required,
                        "Generated service module skeleton is incomplete"));
            }
        }
    }

    private void validateMapperXmlProperties(List<GeneratedFile> files, Map<String, Set<String>> fieldsBySimple,
                                             List<Issue> issues) {
        for (GeneratedFile file : files) {
            String path = normalize(file.getFilePath());
            if (path == null || !path.endsWith("Mapper.xml") || file.getContent() == null) continue;
            Matcher resultMaps = RESULT_MAP.matcher(file.getContent());
            while (resultMaps.find()) {
                String typeName = resultMaps.group(1);
                Set<String> fields = fieldsBySimple.get(typeName);
                if (fields == null) continue;
                Matcher properties = XML_PROPERTY.matcher(resultMaps.group(2));
                while (properties.find()) {
                    String property = properties.group(1);
                    if (!fields.contains(property)) {
                        issues.add(error("MAPPER-RESULT-PROPERTY-MISSING", path,
                                "resultMap property " + property + " is absent from " + typeName));
                    }
                }
            }
        }
    }

    private void validateDatabaseContract(List<GeneratedFile> files, GenerationContext context,
                                          Map<String, Set<String>> fieldsBySimpleName, List<Issue> issues) {
        Set<String> ddlColumns = new LinkedHashSet<>();
        Pattern column = Pattern.compile("(?m)^\\s*`?([a-z][a-z0-9_]*)`?\\s+"
                + "(?:BIGINT|INT|INTEGER|VARCHAR|CHAR|TEXT|MEDIUMTEXT|DATETIME|TIMESTAMP|DATE|TIME|DECIMAL|DOUBLE|FLOAT|TINYINT|BOOLEAN|JSON)\\b",
                Pattern.CASE_INSENSITIVE);
        for (GeneratedFile file : files) {
            String path = normalize(file.getFilePath());
            if (path == null || !path.endsWith(".sql") || file.getContent() == null) continue;
            Matcher matcher = column.matcher(file.getContent());
            while (matcher.find()) ddlColumns.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        if (ddlColumns.isEmpty()) return;

        Set<String> baseColumns = Set.of("id", "create_user", "create_dept", "create_time", "update_user",
                "update_time", "status", "is_deleted", "tenant_id");
        Set<String> entityFields = fieldsBySimpleName.getOrDefault(context.identity().entityName(), Set.of());
        for (String field : entityFields) {
            if ("serialVersionUID".equals(field)) continue;
            String dbColumn = camelToSnake(field);
            if (!ddlColumns.contains(dbColumn) && !baseColumns.contains(dbColumn)) {
                issues.add(error("ENTITY-DDL-COLUMN-MISSING", null,
                        "Entity field " + field + " has no DDL column " + dbColumn));
            }
        }
        for (String dbColumn : ddlColumns) {
            if (baseColumns.contains(dbColumn)) continue;
            String field = snakeToCamel(dbColumn);
            if (!entityFields.contains(field)) {
                issues.add(error("DDL-ENTITY-FIELD-MISSING", null,
                        "DDL column " + dbColumn + " has no field " + field + " in " + context.identity().entityName()));
            }
        }

        Pattern select = Pattern.compile("(?is)<select\\b[^>]*>\\s*select\\s+(.*?)\\s+from\\s+");
        for (GeneratedFile file : files) {
            String path = normalize(file.getFilePath());
            if (path == null || !path.endsWith("Mapper.xml") || file.getContent() == null) continue;
            Matcher selects = select.matcher(file.getContent());
            while (selects.find()) {
                for (String expression : selects.group(1).split(",")) {
                    String candidate = expression.trim().replaceAll("(?i)\\s+as\\s+.*$", "")
                            .replaceAll("^[a-zA-Z][a-zA-Z0-9_]*\\.", "").trim();
                    if (candidate.equals("*") || candidate.contains("(") || candidate.contains(" ")
                            || !candidate.matches("[a-z][a-z0-9_]*")) continue;
                    if (!ddlColumns.contains(candidate.toLowerCase(Locale.ROOT))) {
                        issues.add(error("MAPPER-DDL-COLUMN-MISSING", path,
                                "Mapper SELECT references column " + candidate + " absent from generated DDL"));
                    }
                }
            }
        }
    }

    private void validateControllerServiceClosure(List<GeneratedFile> files, GenerationContext context,
                                                  List<Issue> issues) {
        GeneratedFile service = files.stream()
                .filter(file -> normalize(file.getFilePath()).equals(
                        BladeXModuleLayout.serviceInterfacePath(context, context.identity().entityName())))
                .findFirst().orElse(null);
        GeneratedFile controller = files.stream()
                .filter(file -> normalize(file.getFilePath()).equals(
                        BladeXModuleLayout.controllerPath(context, context.identity().entityName())))
                .findFirst().orElse(null);
        if (service == null || controller == null || service.getContent() == null || controller.getContent() == null) return;
        Matcher methods = Pattern.compile(
                "(?:public\\s+)?[A-Za-z0-9_<>?, .]+\\s+([a-z][A-Za-z0-9_]*)\\s*\\([^;{}]*\\)\\s*;")
                .matcher(service.getContent());
        Set<String> serviceMethods = new LinkedHashSet<>();
        while (methods.find()) serviceMethods.add(methods.group(1));
        for (String method : serviceMethods) {
            if (isInternalServiceHelper(method)) continue;
            if (!Pattern.compile("\\.\\s*" + Pattern.quote(method) + "\\s*\\(").matcher(controller.getContent()).find()) {
                issues.add(error("CONTROLLER-SERVICE-BUSINESS-GAP", controller.getFilePath(),
                        "Controller does not call custom service method " + method));
            }
        }
    }

    private String extractParentSimpleName(String typeName, String content) {
        if (content == null) return null;
        Matcher matcher = EXTENDS_TYPE.matcher(content);
        while (matcher.find()) {
            if (!typeName.equals(matcher.group(1))) continue;
            String parent = matcher.group(2);
            int dot = parent.lastIndexOf('.');
            return dot >= 0 ? parent.substring(dot + 1) : parent;
        }
        return null;
    }

    private Map<String, Set<String>> resolveEffectiveFields(Map<String, TypeShape> shapes) {
        Map<String, Set<String>> resolved = new HashMap<>();
        for (String typeName : shapes.keySet()) {
            resolveEffectiveFields(typeName, shapes, resolved, new LinkedHashSet<>());
        }
        return resolved;
    }

    private Set<String> resolveEffectiveFields(String typeName, Map<String, TypeShape> shapes,
                                               Map<String, Set<String>> resolved, Set<String> visiting) {
        Set<String> cached = resolved.get(typeName);
        if (cached != null) return cached;
        TypeShape shape = shapes.get(typeName);
        if (shape == null) return Set.of();
        if (!visiting.add(typeName)) return new LinkedHashSet<>(shape.directFields());

        Set<String> effective = new LinkedHashSet<>(shape.directFields());
        String parent = shape.parentSimpleName();
        if ("BaseEntity".equals(parent) || "TenantEntity".equals(parent)) {
            effective.addAll(BASE_ENTITY_FIELDS);
        } else if (parent != null && shapes.containsKey(parent)) {
            effective.addAll(resolveEffectiveFields(parent, shapes, resolved, visiting));
        }
        visiting.remove(typeName);
        resolved.put(typeName, effective);
        return effective;
    }

    private boolean isInternalServiceHelper(String method) {
        String lower = method == null ? "" : method.toLowerCase(Locale.ROOT);
        return INTERNAL_SERVICE_HELPER_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    private void validatePomModel(List<GeneratedFile> files, GenerationContext context, List<Issue> issues) {
        Map<String, GeneratedFile> byPath = new HashMap<>();
        for (GeneratedFile file : files) byPath.put(normalize(file.getFilePath()), file);
        GeneratedFile apiPom = byPath.get(BladeXModuleLayout.apiPomPath(context));
        GeneratedFile implPom = byPath.get(BladeXModuleLayout.implPomPath(context));
        validateChildPom(apiPom, context.referenceProfile().apiParentArtifactId(),
                context.referenceProfile().apiParentVersion(), issues);
        validateChildPom(implPom, context.referenceProfile().serviceParentArtifactId(),
                context.referenceProfile().serviceParentVersion(), issues);
        GeneratedFile apiParent = byPath.get("blade-service-api/pom.xml");
        if (apiParent != null && !apiParent.getContent().contains(
                "<module>" + context.identity().apiModuleName() + "</module>")) {
            issues.add(error("PARENT-POM-MODULE-MISSING", apiParent.getFilePath(),
                    "API parent pom does not register " + context.identity().apiModuleName()));
        }
        GeneratedFile serviceParent = byPath.get("blade-service/pom.xml");
        if (serviceParent != null && !serviceParent.getContent().contains(
                "<module>" + context.identity().serviceModuleName() + "</module>")) {
            issues.add(error("PARENT-POM-MODULE-MISSING", serviceParent.getFilePath(),
                    "Service parent pom does not register " + context.identity().serviceModuleName()));
        }
    }

    private void validateChildPom(GeneratedFile pom, String parentArtifact, String parentVersion, List<Issue> issues) {
        if (pom == null || pom.getContent() == null) return;
        if (!pom.getContent().contains("<artifactId>" + parentArtifact + "</artifactId>")) {
            issues.add(error("POM-PARENT-MISMATCH", pom.getFilePath(),
                    "Expected parent artifactId " + parentArtifact));
        }
        if (parentVersion != null && !"UNKNOWN".equalsIgnoreCase(parentVersion)
                && !pom.getContent().contains("<version>" + parentVersion + "</version>")) {
            issues.add(error("POM-PARENT-VERSION-MISMATCH", pom.getFilePath(),
                    "Expected parent version " + parentVersion));
        }
        if (pom.getContent().contains("${revision}")
                && (parentVersion == null || !parentVersion.contains("${revision}"))) {
            issues.add(error("POM-UNDEFINED-REVISION", pom.getFilePath(),
                    "Generated pom uses ${revision} although the reference project uses " + parentVersion));
        }
    }

    private String camelToSnake(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private String snakeToCamel(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char c : value.toCharArray()) {
            if (c == '_') upper = true;
            else if (upper) { result.append(Character.toUpperCase(c)); upper = false; }
            else result.append(c);
        }
        return result.toString();
    }

    private boolean hasExternalPrefix(String imported) {
        for (String prefix : EXTERNAL_PREFIXES) if (imported.startsWith(prefix)) return true;
        return false;
    }

    private String normalize(String path) {
        return path == null ? null : path.replace('\\', '/');
    }

    private Issue error(String rule, String file, String message) {
        return new Issue("ERROR", rule, file, message);
    }

    private record TypeShape(Set<String> directFields, String parentSimpleName) {
    }

    public record Issue(String severity, String rule, String filePath, String message) {
        public boolean isError() { return "ERROR".equalsIgnoreCase(severity); }
        @Override public String toString() { return severity + "[" + rule + "] " + filePath + ": " + message; }
    }
}
