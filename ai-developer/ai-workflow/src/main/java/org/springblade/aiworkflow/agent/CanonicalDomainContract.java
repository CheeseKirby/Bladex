package org.springblade.aiworkflow.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authoritative plan-wide domain contract. Generated persistence, API and service layers must use
 * these exact field names and types instead of independently inventing aliases.
 */
public record CanonicalDomainContract(
        GenerationIdentity identity,
        List<DomainField> persistentFields,
        List<DomainField> derivedFields,
        List<String> businessRules) {

    private static final Set<String> BASE_FIELDS = Set.of(
            "id", "createUser", "createDept", "createTime", "updateUser", "updateTime",
            "status", "isDeleted", "tenantId");

    public CanonicalDomainContract {
        persistentFields = persistentFields == null ? List.of() : List.copyOf(persistentFields);
        derivedFields = derivedFields == null ? List.of() : List.copyOf(derivedFields);
        businessRules = businessRules == null ? List.of() : List.copyOf(businessRules);
    }

    static CanonicalDomainContract empty(GenerationIdentity identity) {
        return new CanonicalDomainContract(identity, List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return persistentFields.isEmpty();
    }

    public Map<String, DomainField> persistentByName() {
        Map<String, DomainField> result = new LinkedHashMap<>();
        for (DomainField field : persistentFields) result.putIfAbsent(field.name(), field);
        return result;
    }

    public Set<String> persistentNames() {
        return new LinkedHashSet<>(persistentByName().keySet());
    }

    public Set<String> persistentColumns() {
        Set<String> result = new LinkedHashSet<>();
        for (DomainField field : persistentFields) result.add(field.columnName());
        return result;
    }

    public Set<String> derivedNames() {
        Set<String> result = new LinkedHashSet<>();
        for (DomainField field : derivedFields) result.add(field.name());
        return result;
    }

    public boolean isBaseField(String field) {
        return BASE_FIELDS.contains(field);
    }

    public String describeForPrompt() {
        if (isEmpty()) return "No explicit canonical field inventory was compiled; do not invent aliases across layers.";
        StringBuilder result = new StringBuilder();
        result.append("== AUTHORITATIVE PLAN-WIDE DOMAIN CONTRACT ==\n");
        result.append("Entity: ").append(identity.entityName())
                .append(", table: ").append(identity.tableName()).append('\n');
        result.append("Persistent business fields (exact Java name -> exact SQL column -> Java type):\n");
        for (DomainField field : persistentFields) {
            result.append("- ").append(field.name()).append(" -> ").append(field.columnName())
                    .append(" -> ").append(field.javaType())
                    .append(field.required() ? " [required]" : " [optional]").append('\n');
        }
        if (!derivedFields.isEmpty()) {
            result.append("Derived output-only fields (never DDL/Entity persistence columns):\n");
            for (DomainField field : derivedFields) {
                result.append("- ").append(field.name()).append(" -> ").append(field.javaType()).append('\n');
            }
        }
        result.append("Hard constraints:\n")
                .append("- DDL, Entity, Mapper Java/XML, Service and input/output models must use the exact persistent names above.\n")
                .append("- Do not replace one field with code/name/status aliases unless the contract explicitly lists them.\n")
                .append("- Entity contains persistent business fields only; derived fields belong in output VO types.\n")
                .append("- Every generated method call, generic parameter and return type must close against another generated declaration.\n");
        for (String rule : businessRules) result.append("- Business rule: ").append(rule).append('\n');
        return result.toString();
    }

    public record DomainField(
            String name,
            String columnName,
            String javaType,
            boolean required,
            FieldRole role,
            Set<Long> sourceSubPlanIds,
            String evidence) {
        public DomainField {
            sourceSubPlanIds = sourceSubPlanIds == null ? Set.of() : Set.copyOf(sourceSubPlanIds);
            role = role == null ? FieldRole.PERSISTENT : role;
        }

        DomainField withRequired(boolean value) {
            return new DomainField(name, columnName, javaType, value, role, sourceSubPlanIds, evidence);
        }
    }

    public enum FieldRole {
        PERSISTENT,
        DERIVED
    }

    static List<DomainField> deduplicate(List<DomainField> fields) {
        Map<String, DomainField> result = new LinkedHashMap<>();
        for (DomainField field : fields == null ? List.<DomainField>of() : fields) {
            DomainField existing = result.get(field.name());
            if (existing == null) {
                result.put(field.name(), field);
                continue;
            }
            Set<Long> sources = new LinkedHashSet<>(existing.sourceSubPlanIds());
            sources.addAll(field.sourceSubPlanIds());
            result.put(field.name(), new DomainField(existing.name(), existing.columnName(), existing.javaType(),
                    existing.required() || field.required(), existing.role(), sources,
                    existing.evidence() + " | " + field.evidence()));
        }
        return new ArrayList<>(result.values());
    }
}
