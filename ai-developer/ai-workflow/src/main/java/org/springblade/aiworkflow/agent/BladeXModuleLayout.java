package org.springblade.aiworkflow.agent;

/**
 * Version-aware BladeX multi-module layout. Legacy overloads are retained for
 * old callers, while new workflow code must use GenerationContext.
 */
public final class BladeXModuleLayout {

    private BladeXModuleLayout() {
    }

    public static String ddlPath(GenerationContext context) {
        return "doc/sql/" + context.identity().moduleName() + "/migration.sql";
    }

    public static String entityPath(GenerationContext context, String entity) {
        return apiJavaBase(context) + packagePath(context.referenceProfile().entityPackageSuffix()) + "/" + entity + ".java";
    }

    public static String voPath(GenerationContext context, String entity, String suffix) {
        String className = entity + suffix;
        return apiJavaBase(context) + packagePath(context.referenceProfile().voPackageSuffix(className))
                + "/" + className + ".java";
    }

    public static String feignPath(GenerationContext context, String className) {
        return apiJavaBase(context) + packagePath(context.referenceProfile().feignPackageSuffix())
                + "/I" + className + "Client.java";
    }

    public static String apiPomPath(GenerationContext context) {
        return "blade-service-api/" + context.identity().apiModuleName() + "/pom.xml";
    }

    public static String controllerPath(GenerationContext context, String entity) {
        return implJavaBase(context) + packagePath(context.referenceProfile().controllerPackageSuffix())
                + "/" + entity + "Controller.java";
    }

    public static String serviceInterfacePath(GenerationContext context, String entity) {
        return implJavaBase(context) + packagePath(context.referenceProfile().servicePackageSuffix())
                + "/I" + entity + "Service.java";
    }

    public static String serviceImplPath(GenerationContext context, String entity) {
        return implJavaBase(context) + packagePath(context.referenceProfile().serviceImplPackageSuffix())
                + "/" + entity + "ServiceImpl.java";
    }

    public static String mapperJavaPath(GenerationContext context, String entity) {
        return implJavaBase(context) + packagePath(context.referenceProfile().mapperPackageSuffix())
                + "/" + entity + "Mapper.java";
    }

    public static String mapperXmlPath(GenerationContext context, String entity) {
        if (context.referenceProfile().mapperXmlInJava()) {
            return implJavaBase(context) + packagePath(context.referenceProfile().mapperPackageSuffix())
                    + "/" + entity + "Mapper.xml";
        }
        return "blade-service/" + context.identity().serviceModuleName()
                + "/src/main/resources/mapper/" + entity + "Mapper.xml";
    }

    public static String wrapperPath(GenerationContext context, String entity) {
        return implJavaBase(context) + packagePath(context.referenceProfile().wrapperPackageSuffix())
                + "/" + entity + "Wrapper.java";
    }

    public static String excelPath(GenerationContext context, String entity) {
        return implJavaBase(context) + packagePath(context.referenceProfile().excelPackageSuffix())
                + "/" + entity + "Excel.java";
    }

    public static String applicationPath(GenerationContext context) {
        return implJavaBase(context) + "/" + capitalize(context.identity().moduleName()) + "Application.java";
    }

    public static String implPomPath(GenerationContext context) {
        return "blade-service/" + context.identity().serviceModuleName() + "/pom.xml";
    }

    public static String bootstrapPath(GenerationContext context) {
        return implResourcesBase(context) + "/bootstrap.yml";
    }

    public static String appDevPath(GenerationContext context) {
        return implResourcesBase(context) + "/application-dev.yml";
    }

    public static String apiClassPath(GenerationContext context, String packageSuffix, String className) {
        return apiJavaBase(context) + packagePath(packageSuffix) + "/" + className + ".java";
    }

    public static String implClassPath(GenerationContext context, String packageSuffix, String className) {
        return implJavaBase(context) + packagePath(packageSuffix) + "/" + className + ".java";
    }

    public static String namedFeignPath(GenerationContext context, String className) {
        return apiClassPath(context, context.referenceProfile().feignPackageSuffix(), className);
    }

    public static String namedControllerPath(GenerationContext context, String className) {
        return implClassPath(context, context.referenceProfile().controllerPackageSuffix(), className);
    }

    public static String dtoPath(GenerationContext context, String className) {
        String voBase = context.referenceProfile().voPackageSuffix("VO");
        String dtoSuffix = voBase.endsWith(".vo") ? voBase.substring(0, voBase.length() - 3) + ".dto" : "dto";
        return apiJavaBase(context) + packagePath(dtoSuffix) + "/" + className + ".java";
    }

    private static String apiJavaBase(GenerationContext context) {
        return "blade-service-api/" + context.identity().apiModuleName() + "/src/main/java/"
                + context.identity().basePackage().replace('.', '/');
    }

    private static String implJavaBase(GenerationContext context) {
        return "blade-service/" + context.identity().serviceModuleName() + "/src/main/java/"
                + context.identity().basePackage().replace('.', '/');
    }

    private static String implResourcesBase(GenerationContext context) {
        return "blade-service/" + context.identity().serviceModuleName() + "/src/main/resources";
    }

    private static String packagePath(String suffix) {
        return suffix == null || suffix.isBlank() ? "" : "/" + suffix.replace('.', '/');
    }

    /** Reverse a generated path to its canonical module name. */
    public static String moduleOfPath(String relPath) {
        if (relPath == null) return null;
        if (relPath.startsWith("blade-service-api/blade-")) {
            return extractBetween(relPath, "blade-service-api/blade-", "-api/");
        }
        if (relPath.startsWith("blade-service/blade-")) {
            return extractBetween(relPath, "blade-service/blade-", "/");
        }
        if (relPath.startsWith("doc/sql/")) return extractBetween(relPath, "doc/sql/", "/");
        return null;
    }

    public static String sideOfPath(String relPath) {
        if (relPath == null) return "OTHER";
        if (relPath.startsWith("blade-service-api/")) return "API";
        if (relPath.startsWith("blade-service/")) return "IMPL";
        if (relPath.startsWith("doc/sql/")) return "DOC";
        return "OTHER";
    }

    private static String extractBetween(String value, String prefix, String suffix) {
        int start = prefix.length();
        int end = value.indexOf(suffix, start);
        return end > start ? value.substring(start, end) : null;
    }

    public static String capitalize(String module) {
        if (module == null || module.isEmpty()) return module;
        StringBuilder result = new StringBuilder();
        for (String part : module.split("_")) {
            if (!part.isEmpty()) result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    // Backward-compatible defaults for old tests and repair helpers.
    private static GenerationContext legacy(String module, String entity) {
        return new GenerationContext(GenerationIdentity.of(module, entity, "blade_" + module,
                "org.springblade." + module), ReferenceFrameworkProfile.defaults());
    }

    public static String ddlPath(String module) { return ddlPath(legacy(module, "Business")); }
    public static String entityPath(String module, String entity) { return entityPath(legacy(module, entity), entity); }
    public static String voPath(String module, String entity, String suffix) { return voPath(legacy(module, entity), entity, suffix); }
    public static String feignPath(String module, String entity) { return feignPath(legacy(module, entity), entity); }
    public static String apiPomPath(String module) { return apiPomPath(legacy(module, "Business")); }
    public static String controllerPath(String module, String entity) { return controllerPath(legacy(module, entity), entity); }
    public static String serviceInterfacePath(String module, String entity) { return serviceInterfacePath(legacy(module, entity), entity); }
    public static String serviceImplPath(String module, String entity) { return serviceImplPath(legacy(module, entity), entity); }
    public static String mapperJavaPath(String module, String entity) { return mapperJavaPath(legacy(module, entity), entity); }
    public static String mapperXmlPath(String module, String entity) { return mapperXmlPath(legacy(module, entity), entity); }
    public static String wrapperPath(String module, String entity) { return wrapperPath(legacy(module, entity), entity); }
    public static String excelPath(String module, String entity) { return excelPath(legacy(module, entity), entity); }
    public static String applicationPath(String module) { return applicationPath(legacy(module, "Business")); }
    public static String implPomPath(String module) { return implPomPath(legacy(module, "Business")); }
    public static String bootstrapPath(String module) { return bootstrapPath(legacy(module, "Business")); }
    public static String appDevPath(String module) { return appDevPath(legacy(module, "Business")); }
}
