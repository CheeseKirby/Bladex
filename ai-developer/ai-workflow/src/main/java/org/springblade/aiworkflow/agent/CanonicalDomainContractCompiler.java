package org.springblade.aiworkflow.agent;

import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles reviewed plan prose into one authoritative field and business-rule contract. */
final class CanonicalDomainContractCompiler {

    private static final Pattern REQUIRED_COLUMN_LIST = Pattern.compile(
            "(?i)(?:表必须包含字段|table\\s+must\\s+contain(?:\\s+columns?)?)\\s*[:：]\\s*([^。；;\\n]+)");
    private static final Pattern FIELD_LIST = Pattern.compile(
            "(?i)(?:字段|columns?)\\s*[:：]\\s*([^。；;\\n]+)");
    private static final Pattern FIELD_TOKEN = Pattern.compile("`?([a-z][a-z0-9_]*|[a-z][A-Za-z0-9]*)`?");
    private static final Pattern DERIVED_FIELD = Pattern.compile(
            "\\b([a-z][A-Za-z0-9]*(?:Desc|Label|Text))\\b");
    private static final Set<String> BASE_COLUMNS = Set.of(
            "id", "create_user", "create_dept", "create_time", "update_user", "update_time",
            "status", "is_deleted", "tenant_id");
    private static final Set<String> STOP_WORDS = Set.of(
            "create", "update", "delete", "select", "table", "index", "module", "entity", "service",
            "controller", "mapper", "wrapper", "excel", "feign", "java", "spring", "blade", "true", "false");

    Compilation compile(AiPlan plan, List<AiSubPlan> subPlans, GenerationIdentity identity) {
        List<PlanCompilationIssue> issues = new ArrayList<>();
        List<CanonicalDomainContract.DomainField> persistent = new ArrayList<>();
        List<CanonicalDomainContract.DomainField> derived = new ArrayList<>();
        List<String> businessRules = new ArrayList<>();

        List<SourceText> sources = new ArrayList<>();
        if (plan != null && hasText(plan.getMasterPlanContent())) {
            sources.add(new SourceText(null, "master plan", plan.getMasterPlanContent()));
        }
        for (AiSubPlan subPlan : subPlans == null ? List.<AiSubPlan>of() : subPlans) {
            if (hasText(subPlan.getPlanContent())) {
                sources.add(new SourceText(subPlan.getId(), safe(subPlan.getTitle()), subPlan.getPlanContent()));
            }
        }

        Map<String, FieldEvidence> authoritativeColumns = new LinkedHashMap<>();
        for (SourceText source : sources) {
            boolean ddlSource = source.title().toLowerCase(Locale.ROOT).contains("ddl")
                    || source.text().contains("建表") || source.text().contains("表必须包含字段");
            if (!ddlSource) continue;
            collectColumnLists(source, REQUIRED_COLUMN_LIST, authoritativeColumns);
        }
        if (authoritativeColumns.isEmpty()) {
            for (SourceText source : sources) collectColumnLists(source, FIELD_LIST, authoritativeColumns);
        }

        for (Map.Entry<String, FieldEvidence> entry : authoritativeColumns.entrySet()) {
            String column = entry.getKey();
            if (BASE_COLUMNS.contains(column)) continue;
            String field = snakeToCamel(column);
            boolean required = isRequired(field, column, sources);
            persistent.add(new CanonicalDomainContract.DomainField(
                    field, column, inferJavaType(field, column), required,
                    CanonicalDomainContract.FieldRole.PERSISTENT,
                    entry.getValue().subPlanId() == null ? Set.of() : Set.of(entry.getValue().subPlanId()),
                    entry.getValue().evidence()));
        }

        for (SourceText source : sources) {
            Matcher matcher = DERIVED_FIELD.matcher(source.text());
            while (matcher.find()) {
                String name = matcher.group(1);
                if (STOP_WORDS.contains(name.toLowerCase(Locale.ROOT))) continue;
                derived.add(new CanonicalDomainContract.DomainField(
                        name, null, "String", false, CanonicalDomainContract.FieldRole.DERIVED,
                        source.subPlanId() == null ? Set.of() : Set.of(source.subPlanId()),
                        "Derived field explicitly reviewed in " + source.title()));
            }
            collectBusinessRules(source, businessRules);
        }

        List<CanonicalDomainContract.DomainField> canonicalPersistent = CanonicalDomainContract.deduplicate(persistent);
        List<CanonicalDomainContract.DomainField> canonicalDerived = CanonicalDomainContract.deduplicate(derived);
        if (canonicalPersistent.isEmpty()) {
            issues.add(new PlanCompilationIssue(null, "WARN", "DOMAIN-CONTRACT-FIELDS-UNRESOLVED", null,
                    "No authoritative persistent field list was compiled; semantic field aliases cannot be rejected safely"));
        }
        validateNoRoleConflicts(canonicalPersistent, canonicalDerived, issues);

        CanonicalDomainContract contract = new CanonicalDomainContract(
                identity, canonicalPersistent, canonicalDerived, businessRules.stream().distinct().limit(20).toList());
        return new Compilation(contract, issues);
    }

    private void collectColumnLists(SourceText source, Pattern pattern, Map<String, FieldEvidence> columns) {
        Matcher listMatcher = pattern.matcher(source.text());
        while (listMatcher.find()) {
            String list = listMatcher.group(1);
            Matcher token = FIELD_TOKEN.matcher(list);
            while (token.find()) {
                String raw = token.group(1);
                String column = raw.contains("_") ? raw.toLowerCase(Locale.ROOT) : camelToSnake(raw);
                if (!isLikelyColumn(column)) continue;
                columns.putIfAbsent(column, new FieldEvidence(source.subPlanId(),
                        "Explicit field list in " + source.title() + ": " + raw));
            }
        }
    }

    private boolean isRequired(String field, String column, List<SourceText> sources) {
        for (SourceText source : sources) {
            String text = source.text();
            for (String token : List.of(field, column)) {
                int from = 0;
                while ((from = text.indexOf(token, from)) >= 0) {
                    int end = Math.min(text.length(), from + token.length() + 40);
                    int start = Math.max(0, from - 15);
                    String nearby = text.substring(start, end);
                    if (nearby.contains("必填") || nearby.toUpperCase(Locale.ROOT).contains("NOT NULL")) return true;
                    from += token.length();
                }
            }
        }
        return "periodName".equals(field);
    }

    private void collectBusinessRules(SourceText source, List<String> rules) {
        for (String line : source.text().split("\\R")) {
            String trimmed = line.replaceFirst("^\\s*[-*0-9.]+\\s*", "").trim();
            if (trimmed.isBlank() || trimmed.length() > 240) continue;
            if (trimmed.contains("必填") || trimmed.contains("唯一") || trimmed.contains("不允许")
                    || trimmed.contains("状态机") || trimmed.contains("跨天") || trimmed.contains("升级")) {
                rules.add(trimmed);
            }
        }
    }

    private void validateNoRoleConflicts(List<CanonicalDomainContract.DomainField> persistent,
                                         List<CanonicalDomainContract.DomainField> derived,
                                         List<PlanCompilationIssue> issues) {
        Set<String> persistentNames = new LinkedHashSet<>();
        for (CanonicalDomainContract.DomainField field : persistent) persistentNames.add(field.name());
        for (CanonicalDomainContract.DomainField field : derived) {
            if (persistentNames.contains(field.name())) {
                issues.add(PlanCompilationIssue.error(null, "DOMAIN-CONTRACT-ROLE-CONFLICT", field.name(),
                        "Field is declared as both persistent and derived: " + field.name()));
            }
        }
    }

    private boolean isLikelyColumn(String column) {
        if (column == null || !column.matches("[a-z][a-z0-9_]*")) return false;
        if (STOP_WORDS.contains(column)) return false;
        return column.contains("_") || BASE_COLUMNS.contains(column)
                || Set.of("id", "remark", "status").contains(column);
    }

    private String inferJavaType(String field, String column) {
        String lower = column.toLowerCase(Locale.ROOT);
        if ("id".equals(lower) || lower.endsWith("_id") || lower.endsWith("_user") || lower.endsWith("_dept")) {
            return "Long";
        }
        if (lower.endsWith("_date") || lower.endsWith("_time") || lower.equals("create_time") || lower.equals("update_time")) {
            return "Date";
        }
        if (lower.startsWith("is_") || lower.endsWith("_type") || lower.endsWith("_status")
                || lower.endsWith("_level") || lower.equals("status") || lower.equals("cross_day")) {
            return "Integer";
        }
        if (field.endsWith("Count")) return "Long";
        return "String";
    }

    private String snakeToCamel(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char c : value.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            result.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return result.toString();
    }

    private String camelToSnake(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String safe(String value) { return value == null ? "sub-plan" : value; }

    record Compilation(CanonicalDomainContract contract, List<PlanCompilationIssue> issues) {
        Compilation {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    private record SourceText(Long subPlanId, String title, String text) { }
    private record FieldEvidence(Long subPlanId, String evidence) { }
}
