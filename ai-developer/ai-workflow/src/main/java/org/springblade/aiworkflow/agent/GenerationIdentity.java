package org.springblade.aiworkflow.agent;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical identity shared by the master plan, every sub-plan and every atomic task.
 * Module identity is decided once at reception time and must never be inferred again
 * from individual sub-plan prose.
 */
public record GenerationIdentity(
        String moduleName,
        String entityName,
        String tableName,
        String basePackage,
        String apiModuleName,
        String serviceModuleName,
        String serviceName) {

    private static final Pattern MODULE_PATTERN = Pattern.compile("[a-z][a-z0-9_]{1,49}");
    private static final Pattern ENTITY_PATTERN = Pattern.compile("[A-Z][A-Za-z0-9]{1,99}");
    private static final Pattern TABLE_PATTERN = Pattern.compile("[a-z][a-z0-9_]{1,99}");
    private static final Set<String> RESERVED_MODULE_NAMES = Set.of(
            "pom", "parent", "entity", "vo", "dto", "mapper", "service", "controller",
            "wrapper", "excel", "feign", "api", "impl", "config", "sql", "database", "core");

    public GenerationIdentity {
        moduleName = normalizeModule(moduleName);
        entityName = normalizeEntity(entityName);
        tableName = normalizeTable(tableName, moduleName);
        basePackage = normalizeBasePackage(basePackage, moduleName);
        apiModuleName = hasText(apiModuleName) ? apiModuleName.trim() : "blade-" + moduleName + "-api";
        serviceModuleName = hasText(serviceModuleName) ? serviceModuleName.trim() : "blade-" + moduleName;
        serviceName = hasText(serviceName) ? serviceName.trim() : "blade-" + moduleName;
    }

    public static GenerationIdentity of(String moduleName, String entityName, String tableName, String basePackage) {
        return new GenerationIdentity(moduleName, entityName, tableName, basePackage, null, null, null);
    }

    public static String normalizeModule(String raw) {
        String value = hasText(raw) ? raw.trim().toLowerCase(Locale.ROOT) : "business";
        value = value.replace('-', '_').replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
        value = value.replaceAll("^_+|_+$", "");
        if (value.startsWith("blade_")) value = value.substring("blade_".length());
        if (value.endsWith("_api")) value = value.substring(0, value.length() - 4);
        if (value.endsWith("_service")) value = value.substring(0, value.length() - 8);
        if (!MODULE_PATTERN.matcher(value).matches() || RESERVED_MODULE_NAMES.contains(value)) {
            throw new IllegalArgumentException("Invalid or reserved module name: " + raw);
        }
        return value;
    }

    public static String normalizeEntity(String raw) {
        String value = hasText(raw) ? raw.trim().replaceAll("[^A-Za-z0-9_]", "") : "Business";
        if (!value.isEmpty() && Character.isLowerCase(value.charAt(0))) {
            value = Character.toUpperCase(value.charAt(0)) + value.substring(1);
        }
        if (!ENTITY_PATTERN.matcher(value).matches() || "Entity".equals(value) || "BaseEntity".equals(value)) {
            throw new IllegalArgumentException("Invalid entity name: " + raw);
        }
        return value;
    }

    private static String normalizeTable(String raw, String moduleName) {
        String value = hasText(raw) ? raw.trim().toLowerCase(Locale.ROOT) : "blade_" + moduleName;
        value = value.replace('-', '_').replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
        if (!TABLE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid table name: " + raw);
        }
        return value;
    }

    private static String normalizeBasePackage(String raw, String moduleName) {
        String value = hasText(raw) ? raw.trim() : "org.springblade." + moduleName;
        if (!value.matches("[a-zA-Z_][\\w]*(\\.[a-zA-Z_][\\w]*)+")) {
            throw new IllegalArgumentException("Invalid base package: " + raw);
        }
        // The canonical module segment is authoritative. A conflicting package is not allowed
        // to create a second logical module.
        if (!value.equals("org.springblade." + moduleName)
                && !value.startsWith("org.springblade." + moduleName + ".")) {
            value = "org.springblade." + moduleName;
        }
        return value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
