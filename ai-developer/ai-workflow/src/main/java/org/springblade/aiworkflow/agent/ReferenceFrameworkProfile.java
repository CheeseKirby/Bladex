package org.springblade.aiworkflow.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Structured conventions detected from the selected reference project. */
public record ReferenceFrameworkProfile(
        String bladeXVersion,
        String javaVersion,
        String parentGroupId,
        String apiParentArtifactId,
        String serviceParentArtifactId,
        String apiParentVersion,
        String serviceParentVersion,
        String internalDependencyVersion,
        String validationNamespace,
        String swaggerGeneration,
        String entityPackageSuffix,
        Map<String, String> voPackageSuffixes,
        String controllerPackageSuffix,
        String servicePackageSuffix,
        String serviceImplPackageSuffix,
        String mapperPackageSuffix,
        String wrapperPackageSuffix,
        String feignPackageSuffix,
        String excelPackageSuffix,
        boolean mapperXmlInJava,
        String applicationStyle,
        String nacosNamespace,
        String profileStyle,
        String sourceProjectRoot) {

    public ReferenceFrameworkProfile {
        bladeXVersion = textOr(bladeXVersion, "UNKNOWN");
        javaVersion = textOr(javaVersion, "UNKNOWN");
        parentGroupId = textOr(parentGroupId, "org.springblade");
        apiParentArtifactId = textOr(apiParentArtifactId, "blade-service-api");
        serviceParentArtifactId = textOr(serviceParentArtifactId, "blade-service");
        apiParentVersion = textOr(apiParentVersion, bladeXVersion);
        serviceParentVersion = textOr(serviceParentVersion, bladeXVersion);
        internalDependencyVersion = textOr(internalDependencyVersion, bladeXVersion);
        validationNamespace = textOr(validationNamespace, "javax");
        swaggerGeneration = textOr(swaggerGeneration, "v2");
        entityPackageSuffix = normalizeSuffix(entityPackageSuffix, "pojo.entity");
        voPackageSuffixes = immutableVoPackages(voPackageSuffixes);
        controllerPackageSuffix = normalizeSuffix(controllerPackageSuffix, "controller");
        servicePackageSuffix = normalizeSuffix(servicePackageSuffix, "service");
        serviceImplPackageSuffix = normalizeSuffix(serviceImplPackageSuffix, "service.impl");
        mapperPackageSuffix = normalizeSuffix(mapperPackageSuffix, "mapper");
        wrapperPackageSuffix = normalizeSuffix(wrapperPackageSuffix, "wrapper");
        feignPackageSuffix = normalizeSuffix(feignPackageSuffix, "feign");
        excelPackageSuffix = normalizeSuffix(excelPackageSuffix, "excel");
        applicationStyle = textOr(applicationStyle, "BLADE_CLOUD_APPLICATION");
        nacosNamespace = textOr(nacosNamespace, "blade");
        profileStyle = textOr(profileStyle, "SPRING_CONFIG_ACTIVATE");
    }

    public static ReferenceFrameworkProfile defaults() {
        return new ReferenceFrameworkProfile(
                "UNKNOWN", "17", "org.springblade", "blade-service-api", "blade-service",
                "${revision}", "${revision}", "${bladex.project.version}", "jakarta", "v3",
                "pojo.entity", Map.of("VO", "pojo.vo", "QVO", "pojo.vo", "IVO", "pojo.vo",
                "UVO", "pojo.vo", "EVO", "pojo.vo"),
                "controller", "service", "service.impl", "mapper", "wrapper", "feign", "excel",
                true, "BLADE_CLOUD_APPLICATION", "blade", "SPRING_CONFIG_ACTIVATE", null);
    }

    public String voPackageSuffix(String className) {
        if (className != null) {
            for (String suffix : new String[]{"QVO", "IVO", "UVO", "EVO", "VO"}) {
                if (className.endsWith(suffix)) return voPackageSuffixes.getOrDefault(suffix, voPackageSuffixes.get("VO"));
            }
        }
        return voPackageSuffixes.get("VO");
    }

    public boolean usesJavax() {
        return "javax".equalsIgnoreCase(validationNamespace);
    }

    public boolean usesSwaggerV2() {
        return "v2".equalsIgnoreCase(swaggerGeneration);
    }

    public String describeForPrompt() {
        return "Reference profile: BladeX=" + bladeXVersion + ", Java=" + javaVersion
                + ", validation=" + validationNamespace + ", swagger=" + swaggerGeneration
                + ", entityPackage=" + entityPackageSuffix + ", voPackages=" + voPackageSuffixes
                + ", applicationStyle=" + applicationStyle + ", nacosNamespace=" + nacosNamespace;
    }

    private static Map<String, String> immutableVoPackages(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        String defaultVo = "pojo.vo";
        if (source != null && source.get("VO") != null) defaultVo = normalizeSuffix(source.get("VO"), defaultVo);
        for (String suffix : new String[]{"VO", "QVO", "IVO", "UVO", "EVO"}) {
            String value = source == null ? null : source.get(suffix);
            result.put(suffix, normalizeSuffix(value, defaultVo));
        }
        return Map.copyOf(result);
    }

    private static String normalizeSuffix(String value, String fallback) {
        String normalized = textOr(value, fallback).trim().replace('/', '.').replaceAll("^\\.+|\\.+$", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value) ? fallback : value.trim();
    }
}
