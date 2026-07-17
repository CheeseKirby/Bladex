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
    private static final Pattern JAVA_FIELD = Pattern.compile(
            "(?m)^\\s*(?:private|protected|public)\\s+(?:static\\s+)?(?:final\\s+)?[A-Za-z0-9_<>?, .]+\\s+([a-z][A-Za-z0-9_]*)\\s*(?:[;=])");
    private static final Set<String> EXTERNAL_PREFIXES = Set.of(
            "java.", "javax.", "jakarta.", "lombok.", "org.springframework.", "com.baomidou.",
            "io.swagger.", "cn.hutool.", "com.fasterxml.", "org.apache.", "org.slf4j.",
            "org.springblade.core.", "org.springblade.common.", "org.springblade.modules.");

    public List<Issue> validate(List<GeneratedFile> files, List<ExpectedDeliverable> expected,
                                GenerationContext context, ReferenceProjectIndex referenceIndex) {
        List<Issue> issues = new ArrayList<>();
        Map<String, List<GeneratedFile>> byPath = new LinkedHashMap<>();
        for (GeneratedFile file : files) byPath.computeIfAbsent(normalize(file.getFilePath()), k -> new ArrayList<>()).add(file);

        for (ExpectedDeliverable deliverable : expected) {
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
        Map<String, Set<String>> fieldsBySimpleName = new HashMap<>();
        for (GeneratedFile file : files) {
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
            Set<String> javaFields = new HashSet<>();
            Matcher fieldMatcher = JAVA_FIELD.matcher(file.getContent());
            while (fieldMatcher.find()) javaFields.add(fieldMatcher.group(1));
            fieldsBySimpleName.put(type, javaFields);

            String module = BladeXModuleLayout.moduleOfPath(path);
            if (module != null && !module.equals(context.identity().moduleName())) {
                issues.add(error("MODULE-IDENTITY-MISMATCH", path,
                        "File belongs to module " + module + " but canonical module is " + context.identity().moduleName()));
            }
        }

        Set<String> referenceFqcns = new HashSet<>();
        Map<String, IndexedClassInfo> referencesBySimple = new HashMap<>();
        boolean referenceReady = referenceIndex != null && referenceIndex.isReady();
        if (referenceReady) {
            for (IndexedClassInfo info : referenceIndex.getCachedClasses()) {
                referenceFqcns.add(info.packageName() + "." + info.simpleName());
                referencesBySimple.putIfAbsent(info.simpleName(), info);
            }
        }
        GeneratedFile servicePom = files.stream()
                .filter(f -> normalize(f.getFilePath()).equals(BladeXModuleLayout.implPomPath(context)))
                .findFirst().orElse(null);
        for (GeneratedFile file : files) {
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

        validateSkeleton(files, context, issues);
        validateMapperXmlProperties(files, fieldsBySimpleName, issues);
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
            Matcher type = Pattern.compile("type=\"(?:[\\w.]+\\.)?([A-Z][A-Za-z0-9_]*)\"").matcher(file.getContent());
            if (!type.find()) continue;
            Set<String> fields = fieldsBySimple.get(type.group(1));
            if (fields == null) continue;
            Matcher properties = XML_PROPERTY.matcher(file.getContent());
            while (properties.find()) {
                String property = properties.group(1);
                if (!fields.contains(property)) {
                    issues.add(error("MAPPER-RESULT-PROPERTY-MISSING", path,
                            "resultMap property " + property + " is absent from " + type.group(1)));
                }
            }
        }
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

    public record Issue(String severity, String rule, String filePath, String message) {
        public boolean isError() { return "ERROR".equalsIgnoreCase(severity); }
        @Override public String toString() { return severity + "[" + rule + "] " + filePath + ": " + message; }
    }
}
