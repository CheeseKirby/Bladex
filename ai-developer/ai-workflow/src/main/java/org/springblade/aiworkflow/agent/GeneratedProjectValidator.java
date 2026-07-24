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
    private static final Pattern XML_COLUMN = Pattern.compile("column=\"([a-z][a-z0-9_]*)\"");
    private static final Pattern XML_MAPPING_TAG = Pattern.compile("(?is)<(?:id|result)\\b[^>]*>");
    private static final Pattern QVO_PROPERTY_REFERENCE = Pattern.compile("\\bqvo\\.([a-z][A-Za-z0-9_]*)\\b");
    private static final Pattern BLADEX2_LONG_SELECT_COUNT = Pattern.compile(
            "(?m)\\b(?:Long|long)\\s+([a-z][A-Za-z0-9_]*)\\s*=\\s*(?:this\\.)?"
                    + "(?:baseMapper|[a-z][A-Za-z0-9_]*Mapper)\\.selectCount\\s*\\(");
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
            if (module != null && !belongsToCanonicalPhysicalModule(path, context.identity())) {
                issues.add(error("MODULE-IDENTITY-MISMATCH", path,
                        "File belongs to physical module " + module + " but canonical modules are "
                                + context.identity().apiModuleName() + " / " + context.identity().serviceModuleName()));
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

        validateSkeleton(canonicalFiles, context, referenceIndex, issues);
        validateMapperXmlProperties(canonicalFiles, fieldsBySimpleName, issues);
        validateDatabaseContract(canonicalFiles, context, fieldsBySimpleName, issues);
        validateCanonicalDomainContract(canonicalFiles, context, fieldsBySimpleName, issues);
        validateCanonicalApiModels(canonicalFiles, context, fieldsBySimpleName, issues);
        validateControllerServiceClosure(canonicalFiles, context, issues);
        validateFrameworkCompatibility(canonicalFiles, context, issues);
        validatePomModel(canonicalFiles, context, issues);
        return issues;
    }

    private void validateFrameworkCompatibility(List<GeneratedFile> files, GenerationContext context,
                                                List<Issue> issues) {
        ReferenceFrameworkProfile profile = context == null ? null : context.referenceProfile();
        if (profile == null) return;
        String frameworkVersion = profile.bladeXVersion();
        for (GeneratedFile file : files) {
            String path = normalize(file.getFilePath());
            String content = file.getContent();
            if (path == null || content == null || !path.endsWith(".java")) continue;
            if (profile.usesJavax() && content.contains("import jakarta.validation.")) {
                issues.add(error("FRAMEWORK-VALIDATION-NAMESPACE-MISMATCH", path,
                        "Reference profile requires javax.validation imports"));
            } else if (!profile.usesJavax() && content.contains("import javax.validation.")) {
                issues.add(error("FRAMEWORK-VALIDATION-NAMESPACE-MISMATCH", path,
                        "Reference profile requires jakarta.validation imports"));
            }
            if (profile.usesSwaggerV2() && content.contains("import io.swagger.v3.oas.annotations.")) {
                issues.add(error("FRAMEWORK-SWAGGER-GENERATION-MISMATCH", path,
                        "Reference profile requires Swagger v2 annotations"));
            } else if (!profile.usesSwaggerV2() && content.contains("import io.swagger.annotations.")) {
                issues.add(error("FRAMEWORK-SWAGGER-GENERATION-MISMATCH", path,
                        "Reference profile requires OpenAPI v3 annotations"));
            }
            if (frameworkVersion != null && frameworkVersion.matches("^2(?:\\..*)?$")) {
                Matcher matcher = BLADEX2_LONG_SELECT_COUNT.matcher(content);
                while (matcher.find()) {
                    issues.add(error("FRAMEWORK-SELECTCOUNT-TYPE-MISMATCH", path,
                            "BladeX " + frameworkVersion + " / MyBatis-Plus 3.1 selectCount returns Integer; variable "
                                    + matcher.group(1) + " cannot be declared as Long"));
                }
            }
        }
    }

    private boolean belongsToCanonicalPhysicalModule(String path, GenerationIdentity identity) {
        String normalized = normalize(path);
        if (normalized == null) return true;
        if (normalized.startsWith("blade-service-api/")) {
            return normalized.startsWith("blade-service-api/" + identity.apiModuleName() + "/");
        }
        if (normalized.startsWith("blade-service/")) {
            return normalized.startsWith("blade-service/" + identity.serviceModuleName() + "/");
        }
        return true;
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

    private void validateSkeleton(List<GeneratedFile> files, GenerationContext context,
                                  ReferenceProjectIndex referenceIndex, List<Issue> issues) {
        Set<String> paths = new HashSet<>();
        for (GeneratedFile file : files) paths.add(normalize(file.getFilePath()));
        boolean hasApi = paths.stream().anyMatch(path -> path.startsWith("blade-service-api/"));
        boolean hasImpl = paths.stream().anyMatch(path -> path.startsWith("blade-service/"));
        String apiPom = BladeXModuleLayout.apiPomPath(context);
        if (hasApi && !pathAvailable(paths, apiPom, referenceIndex)) {
            issues.add(error("API-POM-MISSING", apiPom,
                    "API module has no generated or reference-project pom.xml"));
        }
        if (hasImpl) {
            for (String required : List.of(BladeXModuleLayout.implPomPath(context),
                    BladeXModuleLayout.applicationPath(context), BladeXModuleLayout.bootstrapPath(context))) {
                if (!pathAvailable(paths, required, referenceIndex)) issues.add(error("SERVICE-SKELETON-MISSING", required,
                        "Service module skeleton is absent from both generated output and the reference project"));
            }
        }
    }

    private boolean pathAvailable(Set<String> generatedPaths, String required, ReferenceProjectIndex referenceIndex) {
        return generatedPaths.contains(required) || (referenceIndex != null && referenceIndex.pathExists(required));
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
        String entityPath = files.stream().map(GeneratedFile::getFilePath).map(this::normalize)
                .filter(path -> path != null && path.endsWith("/entity/" + context.identity().entityName() + ".java"))
                .findFirst().orElse(BladeXModuleLayout.entityPath(context, context.identity().entityName()));
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
        String ddlPath = files.stream().map(GeneratedFile::getFilePath).map(this::normalize)
                .filter(path -> path != null && path.endsWith(".sql"))
                .findFirst().orElse(BladeXModuleLayout.ddlPath(context));
        for (String field : entityFields) {
            if ("serialVersionUID".equals(field)) continue;
            String dbColumn = camelToSnake(field);
            if (!ddlColumns.contains(dbColumn) && !baseColumns.contains(dbColumn)) {
                issues.add(error("ENTITY-DDL-COLUMN-MISSING", entityPath,
                        "Entity field " + field + " has no DDL column " + dbColumn));
            }
        }
        for (String dbColumn : ddlColumns) {
            if (baseColumns.contains(dbColumn)) continue;
            String field = snakeToCamel(dbColumn);
            if (!entityFields.contains(field)) {
                issues.add(error("DDL-ENTITY-FIELD-MISSING", entityPath,
                        "DDL column " + dbColumn + " has no field " + field + " in " + context.identity().entityName()));
            }
        }

        Set<String> requiredMappedColumns = new LinkedHashSet<>();
        Set<String> qvoFields = fieldsBySimpleName.getOrDefault(context.identity().entityName() + "QVO", Set.of());
        for (GeneratedFile file : files) {
            String path = normalize(file.getFilePath());
            if (path == null || !path.endsWith("Mapper.xml") || file.getContent() == null) continue;
            Matcher mappingTags = XML_MAPPING_TAG.matcher(file.getContent());
            while (mappingTags.find()) {
                Matcher columnAttr = XML_COLUMN.matcher(mappingTags.group());
                Matcher propertyAttr = XML_PROPERTY.matcher(mappingTags.group());
                if (!columnAttr.find() || !propertyAttr.find()) continue;
                String property = propertyAttr.group(1);
                if (entityFields.contains(property) || BASE_ENTITY_FIELDS.contains(property)) {
                    requiredMappedColumns.add(columnAttr.group(1).toLowerCase(Locale.ROOT));
                }
            }
            Set<String> missingQvoProperties = new LinkedHashSet<>();
            Matcher qvoReferences = QVO_PROPERTY_REFERENCE.matcher(file.getContent());
            while (qvoReferences.find()) {
                String property = qvoReferences.group(1);
                if (!qvoFields.contains(property)) missingQvoProperties.add(property);
            }
            for (String property : missingQvoProperties) {
                issues.add(error("MAPPER-PARAM-PROPERTY-MISSING", path,
                        "Mapper XML references qvo." + property + " but "
                                + context.identity().entityName() + "QVO does not declare that property"));
            }
        }
        for (String mappedColumn : requiredMappedColumns) {
            if (!ddlColumns.contains(mappedColumn)) {
                issues.add(error("MAPPER-DDL-COLUMN-MISSING", ddlPath,
                        "Mapper result mapping requires column " + mappedColumn + " absent from generated DDL"));
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

    private void validateCanonicalDomainContract(List<GeneratedFile> files, GenerationContext context,
                                                 Map<String, Set<String>> fieldsBySimpleName,
                                                 List<Issue> issues) {
        CanonicalDomainContract contract = context.domainContract();
        if (contract == null || contract.isEmpty()) return;
        String entityName = context.identity().entityName();
        String entityPath = files.stream().map(GeneratedFile::getFilePath).map(this::normalize)
                .filter(path -> path != null && path.endsWith("/entity/" + entityName + ".java"))
                .findFirst().orElse(BladeXModuleLayout.entityPath(context, entityName));
        GeneratedFile entityFile = files.stream()
                .filter(file -> entityPath.equals(normalize(file.getFilePath())))
                .findFirst().orElse(null);
        Set<String> entityFields = fieldsBySimpleName.getOrDefault(entityName, Set.of());
        Map<String, String> entityTypes = new LinkedHashMap<>();
        if (entityFile != null && entityFile.getContent() != null) {
            Matcher matcher = FIELD.matcher(entityFile.getContent());
            while (matcher.find()) entityTypes.put(matcher.group(2), matcher.group(1).replaceAll("\s+", ""));
        }
        for (CanonicalDomainContract.DomainField field : contract.persistentFields()) {
            if (!entityFields.contains(field.name())) {
                issues.add(error("CANONICAL-ENTITY-FIELD-MISSING", entityPath,
                        "Canonical field " + field.name() + " (" + field.javaType() + ") is absent from " + entityName));
            } else if (entityTypes.containsKey(field.name())
                    && !simpleType(entityTypes.get(field.name())).equals(simpleType(field.javaType()))) {
                issues.add(error("CANONICAL-ENTITY-FIELD-TYPE", entityPath,
                        "Canonical field " + field.name() + " must use " + field.javaType()
                                + " but Entity declares " + entityTypes.get(field.name())));
            }
        }
        for (String field : entityFields) {
            if ("serialVersionUID".equals(field) || contract.isBaseField(field)) continue;
            if (!contract.persistentNames().contains(field)) {
                issues.add(error("CANONICAL-ENTITY-FIELD-UNEXPECTED", entityPath,
                        "Entity field " + field + " is not part of the authoritative persistent contract"));
            }
        }

        String ddlPath = files.stream().map(GeneratedFile::getFilePath).map(this::normalize)
                .filter(path -> path != null && path.endsWith(".sql"))
                .findFirst().orElse(BladeXModuleLayout.ddlPath(context));
        GeneratedFile ddlFile = files.stream().filter(file -> ddlPath.equals(normalize(file.getFilePath())))
                .findFirst().orElse(null);
        Set<String> tableColumns = ddlFile == null ? Set.of()
                : extractPrimaryTableColumns(ddlFile.getContent(), context.identity().tableName());
        for (CanonicalDomainContract.DomainField field : contract.persistentFields()) {
            if (!tableColumns.contains(field.columnName())) {
                issues.add(error("CANONICAL-DDL-COLUMN-MISSING", ddlPath,
                        "Canonical column " + field.columnName() + " for field " + field.name()
                                + " is absent from table " + context.identity().tableName()));
            }
        }
    }

    private Set<String> extractPrimaryTableColumns(String sql, String tableName) {
        if (sql == null) return Set.of();
        Pattern table = Pattern.compile("(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?"
                + Pattern.quote(tableName) + "`?\\s*\\((.*?)\\)\\s*ENGINE");
        Matcher tableMatcher = table.matcher(sql);
        if (!tableMatcher.find()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        Matcher column = Pattern.compile("(?m)^\\s*`([a-z][a-z0-9_]*)`\\s+").matcher(tableMatcher.group(1));
        while (column.find()) result.add(column.group(1));
        return result;
    }

    private String simpleType(String type) {
        if (type == null) return "";
        String value = type.replaceAll("<.*>", "").trim();
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private void validateCanonicalApiModels(List<GeneratedFile> files, GenerationContext context,
                                            Map<String, Set<String>> fieldsBySimpleName,
                                            List<Issue> issues) {
        CanonicalDomainContract contract = context.domainContract();
        if (contract == null || contract.isEmpty()) return;
        String entity = context.identity().entityName();
        validateInputModel(files, fieldsBySimpleName, contract, entity + "IVO", false, issues);
        validateInputModel(files, fieldsBySimpleName, contract, entity + "UVO", true, issues);
        GeneratedFile vo = findJavaType(files, entity + "VO");
        if (vo != null) {
            Set<String> fields = fieldsBySimpleName.getOrDefault(entity + "VO", Set.of());
            for (String derived : contract.derivedNames()) {
                if (!fields.contains(derived)) {
                    issues.add(error("CANONICAL-VO-DERIVED-FIELD-MISSING", normalize(vo.getFilePath()),
                            entity + "VO must expose reviewed derived field " + derived));
                }
            }
            Set<String> allowed = new LinkedHashSet<>(contract.persistentNames());
            allowed.addAll(contract.derivedNames());
            boolean extendsEntity = Pattern.compile("\\bextends\\s+" + Pattern.quote(entity) + "\\b")
                    .matcher(vo.getContent()).find();
            if (extendsEntity) {
                Matcher directFields = FIELD.matcher(vo.getContent());
                while (directFields.find()) {
                    String field = directFields.group(2);
                    if ("serialVersionUID".equals(field)) continue;
                    if (contract.isBaseField(field) || contract.persistentNames().contains(field)) {
                        issues.add(error("CANONICAL-VO-FIELD-SHADOW", normalize(vo.getFilePath()),
                                entity + "VO redeclares inherited canonical field " + field));
                    }
                }
            }
            for (String field : fields) {
                if ("serialVersionUID".equals(field) || contract.isBaseField(field) || allowed.contains(field)) continue;
                issues.add(error("CANONICAL-VO-FIELD-UNEXPECTED", normalize(vo.getFilePath()),
                        entity + "VO field " + field + " is absent from the authoritative persistent/derived contract"));
            }
        }
    }

    private void validateInputModel(List<GeneratedFile> files, Map<String, Set<String>> fieldsBySimpleName,
                                    CanonicalDomainContract contract, String typeName,
                                    boolean updateModel, List<Issue> issues) {
        GeneratedFile file = findJavaType(files, typeName);
        if (file == null || file.getContent() == null) return;
        String path = normalize(file.getFilePath());
        Set<String> fields = fieldsBySimpleName.getOrDefault(typeName, Set.of());
        String validationSource = file.getContent();
        if (updateModel) {
            GeneratedFile createModel = findJavaType(files, contract.identity().entityName() + "IVO");
            if (createModel != null && createModel.getContent() != null) {
                validationSource = createModel.getContent() + "\n" + validationSource;
            }
        }
        for (CanonicalDomainContract.DomainField field : contract.persistentFields()) {
            if (!fields.contains(field.name())) {
                issues.add(error("CANONICAL-INPUT-FIELD-MISSING", path,
                        typeName + " is missing canonical input field " + field.name()));
            }
            if (field.required() && !hasValidationAnnotation(validationSource, field.name())) {
                issues.add(error("CANONICAL-INPUT-VALIDATION-MISSING", path,
                        typeName + " field " + field.name() + " is required but has no validation annotation"));
            }
        }
        if (updateModel) {
            if (!fields.contains("id")) {
                issues.add(error("CANONICAL-UVO-ID-MISSING", path, typeName + " must declare or inherit id"));
            }
            if (!file.getContent().matches("(?s).*class\s+" + Pattern.quote(typeName)
                    + "\s+extends\s+" + Pattern.quote(contract.identity().entityName() + "IVO") + ".*")) {
                issues.add(error("CANONICAL-UVO-INHERITANCE", path,
                        typeName + " must extend " + contract.identity().entityName() + "IVO"));
            }
        }
    }

    private boolean hasValidationAnnotation(String content, String fieldName) {
        Pattern declaration = Pattern.compile("(?s)(@(?:NotNull|NotBlank|NotEmpty)[^;]{0,300})"
                + "\\b" + Pattern.quote(fieldName) + "\\s*;");
        return declaration.matcher(content).find();
    }

    private GeneratedFile findJavaType(List<GeneratedFile> files, String typeName) {
        return files.stream().filter(file -> {
            String path = normalize(file.getFilePath());
            return path != null && path.endsWith("/" + typeName + ".java");
        }).findFirst().orElse(null);
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
