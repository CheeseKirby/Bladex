package org.springblade.aiworkflow.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves one canonical generation identity for an entire received plan. */
public final class GenerationIdentityResolver {

    private static final String SEP = "\\s*[:\\x{FF1A}=]?\\s*`?";
    private static final Pattern MODULE = Pattern.compile(
            "(?:moduleName|module|module\\s*name|\\x{6A21}\\x{5757}\\x{540D}?)" + SEP
                    + "([a-z][a-z0-9_-]*)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PACKAGE = Pattern.compile(
            "(?:basePackage|package|package\\s*path|\\x{5305}\\x{8DEF}\\x{5F84})" + SEP
                    + "([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITY = Pattern.compile(
            "(?:entityName|entity\\s*name|class\\s*name|\\x{5B9E}\\x{4F53}(?:\\x{7C7B})?\\x{540D}|\\x{7C7B}\\x{540D})"
                    + SEP + "([A-Z][A-Za-z0-9]*)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITY_CLASS = Pattern.compile(
            "class\\s+([A-Z][A-Za-z0-9]*)\\s+extends\\s+(?:BaseEntity|TenantEntity|BladeEntity)");
    private static final Pattern TABLE = Pattern.compile(
            "(?:tableName|table\\s*name|\\x{8868}\\x{540D})" + SEP
                    + "([a-z][a-z0-9_]*)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLADE_TABLE = Pattern.compile("\\b(blade_[a-z][a-z0-9_]*)\\b");

    private GenerationIdentityResolver() {
    }

    public static GenerationIdentity resolve(PlanReceiveRequest request) {
        PlanReceiveRequest.GenerationIdentityVO supplied = request.getGenerationIdentity();
        String allContent = request.getMasterPlan() == null ? "" : nullToEmpty(request.getMasterPlan().getContent());
        if (request.getSubPlans() != null) {
            allContent += "\n" + request.getSubPlans().stream()
                    .map(PlanReceiveRequest.SubPlanVO::getContent)
                    .map(GenerationIdentityResolver::nullToEmpty)
                    .reduce("", (a, b) -> a + "\n" + b);
        }
        return resolveValues(
                supplied == null ? null : supplied.getModuleName(),
                supplied == null ? null : supplied.getEntityName(),
                supplied == null ? null : supplied.getTableName(),
                supplied == null ? null : supplied.getBasePackage(),
                supplied == null ? null : supplied.getApiModuleName(),
                supplied == null ? null : supplied.getServiceModuleName(),
                supplied == null ? null : supplied.getServiceName(),
                allContent,
                request.getProjectId());
    }

    public static GenerationIdentity resolve(AiPlan plan, List<AiSubPlan> subPlans, ObjectMapper objectMapper) {
        if (plan.getGenerationIdentityJson() != null && !plan.getGenerationIdentityJson().isBlank()) {
            try {
                return objectMapper.readValue(plan.getGenerationIdentityJson(), GenerationIdentity.class);
            } catch (JsonProcessingException ignored) {
                // Old or malformed persisted data falls back to a single deterministic resolution below.
            }
        }
        StringBuilder content = new StringBuilder(nullToEmpty(plan.getMasterPlanContent()));
        if (subPlans != null) {
            for (AiSubPlan subPlan : subPlans) content.append('\n').append(nullToEmpty(subPlan.getPlanContent()));
        }
        return resolveValues(null, null, null, null, null, null, null,
                content.toString(), plan.getProjectId());
    }

    private static GenerationIdentity resolveValues(
            String moduleName, String entityName, String tableName, String basePackage,
            String apiModuleName, String serviceModuleName, String serviceName,
            String content, String projectId) {
        String detectedPackage = first(PACKAGE, content);
        String detectedModule = first(MODULE, content);
        if (detectedModule == null && detectedPackage != null && detectedPackage.startsWith("org.springblade.")) {
            String[] parts = detectedPackage.split("\\.");
            if (parts.length >= 3) detectedModule = parts[2];
        }
        String detectedEntity = first(ENTITY, content);
        if (detectedEntity == null) detectedEntity = first(ENTITY_CLASS, content);
        String detectedTable = first(TABLE, content);
        if (detectedTable == null) detectedTable = first(BLADE_TABLE, content);

        String module = firstText(moduleName, detectedModule, safeProjectModule(projectId),
                detectedEntity == null ? null : detectedEntity.toLowerCase(), "business");
        try {
            module = GenerationIdentity.normalizeModule(module);
        } catch (IllegalArgumentException invalid) {
            String fallback = detectedEntity == null ? "business" : detectedEntity.toLowerCase();
            module = GenerationIdentity.normalizeModule(fallback);
        }
        String entity = firstText(entityName, detectedEntity, pascal(module), "Business");
        String table = firstText(tableName, detectedTable, "blade_" + module);
        String pkg = firstText(basePackage, detectedPackage, "org.springblade." + module);
        return new GenerationIdentity(module, entity, table, pkg,
                apiModuleName, serviceModuleName, serviceName);
    }

    private static String safeProjectModule(String projectId) {
        if (projectId == null) return null;
        String normalized = projectId.toLowerCase().replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return normalized.matches("[a-z][a-z0-9_]{1,49}") ? normalized : null;
    }

    private static String pascal(String module) {
        StringBuilder result = new StringBuilder();
        for (String part : module.split("_")) {
            if (!part.isBlank()) result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.length() < 2 ? "Business" : result.toString();
    }

    private static String first(Pattern pattern, String content) {
        if (content == null) return null;
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
