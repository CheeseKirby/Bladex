package org.springblade.aiworkflow.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned wire contract shared with Part A. Markdown is explanatory only when this contract is present. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CanonicalPlanContractV2(
        String contractVersion,
        String sourceHash,
        String sourceMode,
        String referenceSnapshotId,
        Map<String, Object> referenceProfile,
        String rulesetVersion,
        Identity identity,
        List<Field> fields,
        List<Map<String, Object>> domains,
        List<Map<String, Object>> modules,
        List<Map<String, Object>> aggregates,
        List<Map<String, Object>> entities,
        List<Map<String, Object>> states,
        List<Map<String, Object>> integrations,
        List<Deliverable> deliverables,
        List<Map<String, Object>> referenceBindings,
        List<Map<String, Object>> architectureDecisions) {

    public CanonicalPlanContractV2 {
        fields = copy(fields);
        domains = copy(domains);
        modules = copy(modules);
        aggregates = copy(aggregates);
        entities = copy(entities);
        states = copy(states);
        integrations = copy(integrations);
        deliverables = copy(deliverables);
        referenceProfile = referenceProfile == null ? null : Map.copyOf(referenceProfile);
        referenceBindings = copy(referenceBindings);
        architectureDecisions = copy(architectureDecisions);
    }

    @JsonIgnore
    public boolean isV2() {
        return "2.0".equals(contractVersion);
    }

    public GenerationIdentity generationIdentity() {
        if (identity == null) throw new IllegalArgumentException("canonicalContract.identity is required");
        return new GenerationIdentity(identity.moduleName(), identity.entityName(), identity.tableName(),
                identity.basePackage(), identity.apiModuleName(), identity.serviceModuleName(), identity.serviceName());
    }

    CanonicalDomainContract toDomainContract() {
        GenerationIdentity generationIdentity = generationIdentity();
        List<CanonicalDomainContract.DomainField> persistent = new ArrayList<>();
        List<CanonicalDomainContract.DomainField> derived = new ArrayList<>();
        String primaryEntityId = entities.stream()
                .filter(entity -> generationIdentity.entityName().equals(stringValue(entity.get("name"))))
                .map(CanonicalPlanContractV2::idOf).findFirst().orElse(null);
        for (Field field : fields) {
            if (primaryEntityId != null && !primaryEntityId.equals(field.entityId())) continue;
            CanonicalDomainContract.DomainField compiled = new CanonicalDomainContract.DomainField(
                    field.name(), field.columnName(), field.javaType(), field.required(),
                    "DERIVED".equals(field.role()) ? CanonicalDomainContract.FieldRole.DERIVED
                            : CanonicalDomainContract.FieldRole.PERSISTENT,
                    Set.of(), field.evidence() == null ? "canonical-plan-v2" : field.evidence());
            if (compiled.role() == CanonicalDomainContract.FieldRole.DERIVED) derived.add(compiled);
            else persistent.add(compiled);
        }
        List<String> rules = architectureDecisions.stream()
                .map(item -> String.valueOf(item.getOrDefault("decision", "")))
                .filter(item -> !item.isBlank()).toList();
        return new CanonicalDomainContract(generationIdentity,
                CanonicalDomainContract.deduplicate(persistent), CanonicalDomainContract.deduplicate(derived), rules);
    }

    public List<String> validateStructure() {
        List<String> errors = new ArrayList<>();
        if (!isV2()) errors.add("canonicalContract.contractVersion must be 2.0");
        if (!("STRUCTURED".equals(sourceMode) || "LEGACY_INFERRED".equals(sourceMode))) {
            errors.add("canonicalContract.sourceMode is invalid");
        }
        if ("STRUCTURED".equals(sourceMode)) {
            boolean hasSnapshot = referenceSnapshotId != null && !referenceSnapshotId.isBlank();
            boolean hasProfile = referenceProfile != null;
            if (hasSnapshot != hasProfile) {
                errors.add("structured canonicalContract must pin both referenceSnapshotId and referenceProfile, or neither when no reference project is set");
            }
        }
        try { generationIdentity(); } catch (RuntimeException error) { errors.add(error.getMessage()); }
        if (identity != null && (!hasText(identity.moduleName()) || !hasText(identity.entityName())
                || !hasText(identity.tableName()) || !hasText(identity.basePackage())
                || !hasText(identity.apiModuleName()) || !hasText(identity.serviceModuleName())
                || !hasText(identity.serviceName()))) {
            errors.add("canonicalContract.identity fields must be non-empty");
        }
        if (sourceHash == null || !sourceHash.matches("[a-f0-9]{64}")) errors.add("canonicalContract.sourceHash must be sha256");
        if (rulesetVersion == null || rulesetVersion.isBlank()) errors.add("canonicalContract.rulesetVersion is required");
        if (domains.isEmpty()) errors.add("canonicalContract.domains must not be empty");
        if (modules.isEmpty()) errors.add("canonicalContract.modules must not be empty");
        if (aggregates.isEmpty()) errors.add("canonicalContract.aggregates must not be empty");
        if (entities.isEmpty()) errors.add("canonicalContract.entities must not be empty");

        Set<String> allIds = new LinkedHashSet<>();
        Set<String> moduleIds = collectMapIds("module", modules, allIds, errors);
        Set<String> domainIds = collectMapIds("domain", domains, allIds, errors);
        Set<String> aggregateIds = collectMapIds("aggregate", aggregates, allIds, errors);
        Set<String> entityIds = collectMapIds("entity", entities, allIds, errors);
        collectMapIds("state", states, allIds, errors);
        collectMapIds("integration", integrations, allIds, errors);
        collectMapIds("referenceBinding", referenceBindings, allIds, errors);
        collectMapIds("architectureDecision", architectureDecisions, allIds, errors);

        Set<String> fieldIds = new LinkedHashSet<>();
        Set<String> fieldNames = new LinkedHashSet<>();
        for (Field field : fields) {
            if (field == null || !field.valid()) {
                errors.add("Invalid field contract: " + (field == null ? "(null)" : field.id()));
                continue;
            }
            if (!allIds.add(field.id())) errors.add("Duplicate contract id: " + field.id());
            fieldIds.add(field.id());
            if (!fieldNames.add(field.name())) errors.add("Duplicate field name: " + field.name());
            if (!entityIds.contains(field.entityId())) {
                errors.add("Field " + field.id() + " references unknown entity " + field.entityId());
            }
        }

        Set<String> deliverableIds = new LinkedHashSet<>();
        for (Deliverable deliverable : deliverables) {
            if (deliverable == null || !deliverable.valid()) {
                errors.add("Invalid deliverable contract: " + (deliverable == null ? "(null)" : deliverable.id()));
                continue;
            }
            if (!allIds.add(deliverable.id())) errors.add("Duplicate contract id: " + deliverable.id());
            deliverableIds.add(deliverable.id());
            if (deliverable.moduleId() != null && !moduleIds.contains(deliverable.moduleId())) {
                errors.add("Deliverable " + deliverable.id() + " references unknown module " + deliverable.moduleId());
            }
            if (new LinkedHashSet<>(deliverable.providesTypes()).size() != deliverable.providesTypes().size()) {
                errors.add("Deliverable " + deliverable.id() + " has duplicate providesTypes");
            }
            if (new LinkedHashSet<>(deliverable.requiresTypes()).size() != deliverable.requiresTypes().size()) {
                errors.add("Deliverable " + deliverable.id() + " has duplicate requiresTypes");
            }
            if (deliverable.providesTypes().stream().anyMatch(type -> !hasText(type))
                    || deliverable.requiresTypes().stream().anyMatch(type -> !hasText(type))) {
                errors.add("Deliverable " + deliverable.id() + " contains blank type symbols");
            }
            if (!"PROHIBIT".equals(deliverable.action()) && hasText(deliverable.className())
                    && Set.of("ENTITY", "VO", "MAPPER", "SERVICE", "WRAPPER", "CONTROLLER", "FEIGN", "EXCEL", "OTHER")
                    .contains(deliverable.kind())
                    && !deliverable.providesTypes().contains(deliverable.className())) {
                errors.add("Deliverable " + deliverable.id() + " className is absent from providesTypes: "
                        + deliverable.className());
            }
            if (!"PROHIBIT".equals(deliverable.action()) && !validProvidedTypeShape(deliverable)) {
                errors.add("Deliverable " + deliverable.id() + " providesTypes do not match kind "
                        + deliverable.kind());
            }
        }

        for (Map<String, Object> domain : domains) {
            for (String owner : stringList(domain.get("ownerModuleIds"))) {
                if (!moduleIds.contains(owner)) errors.add("Domain " + idOf(domain) + " references unknown module " + owner);
            }
        }
        for (Map<String, Object> aggregate : aggregates) {
            requireReference(aggregate, "domainId", domainIds, errors);
            requireReference(aggregate, "rootEntityId", entityIds, errors);
        }
        for (Map<String, Object> entity : entities) {
            requireOptionalReference(entity, "moduleId", moduleIds, errors);
            requireOptionalReference(entity, "aggregateId", aggregateIds, errors);
            for (String fieldId : stringList(entity.get("fieldIds"))) {
                if (!fieldIds.contains(fieldId)) errors.add("Entity " + idOf(entity) + " references unknown field " + fieldId);
            }
            for (String fieldName : stringList(entity.get("fields"))) {
                if (!fieldNames.contains(fieldName)) errors.add("Entity " + idOf(entity) + " references unknown field name " + fieldName);
            }
        }
        if (entities.size() != 1) {
            errors.add("canonicalContract supports exactly one generated entity, found " + entities.size());
        }
        Map<String, Object> identityEntity = entities.stream()
                .filter(entity -> identity != null && hasText(identity.entityName())
                        && identity.entityName().equals(stringValue(entity.get("name"))))
                .findFirst().orElse(null);
        if (identityEntity == null) {
            errors.add("No contract entity matches canonicalContract.identity.entityName");
        } else {
            String table = stringValue(identityEntity.get("table"));
            if (hasText(identity.tableName()) && table != null && !table.equals(identity.tableName())) {
                errors.add("Canonical entity table does not match canonicalContract.identity.tableName");
            }
            String moduleId = stringValue(identityEntity.get("moduleId"));
            Map<String, Object> owner = modules.stream().filter(module -> moduleId != null && moduleId.equals(idOf(module)))
                    .findFirst().orElse(null);
            if (owner != null && hasText(identity.moduleName())
                    && !identity.moduleName().equals(stringValue(owner.get("name")))) {
                errors.add("Canonical entity module does not match canonicalContract.identity.moduleName");
            }
        }
        Set<String> stateOwners = new LinkedHashSet<>(aggregateIds);
        stateOwners.addAll(entityIds);
        for (Map<String, Object> state : states) {
            requireOptionalReference(state, "ownerId", stateOwners, errors);
            Set<String> values = new LinkedHashSet<>(stringList(state.get("values")));
            Object transitions = state.get("transitions");
            if (transitions instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> transition) {
                        String from = stringValue(transition.get("from"));
                        String to = stringValue(transition.get("to"));
                        if (from != null && !values.contains(from)) errors.add("State " + idOf(state) + " has transition from unknown value " + from);
                        if (to != null && !values.contains(to)) errors.add("State " + idOf(state) + " has transition to unknown value " + to);
                    }
                }
            }
        }
        for (Map<String, Object> binding : referenceBindings) {
            requireReference(binding, "planElementId", allIds, errors);
            String decision = stringValue(binding.get("decision"));
            if (!Set.of("REUSE", "EXTEND").contains(decision)) {
                errors.add("Reference binding " + idOf(binding) + " has invalid decision");
            }
        }

        for (String required : List.of("DDL", "ENTITY", "VO", "MAPPER", "SERVICE", "CONTROLLER")) {
            boolean present = deliverables.stream().filter(java.util.Objects::nonNull).anyMatch(item -> required.equals(item.kind())
                    && !"PROHIBIT".equals(item.action()));
            if (!present) errors.add("Required deliverable is missing: " + required);
        }
        errors.addAll(validateTypeClosure());
        return errors;
    }

    private Set<String> collectMapIds(String label, List<Map<String, Object>> values,
                                      Set<String> allIds, List<String> errors) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, Object> value : values) {
            String id = idOf(value);
            if (!hasText(id)) {
                errors.add("Invalid " + label + " contract id");
            } else {
                if (!allIds.add(id)) errors.add("Duplicate contract id: " + id);
                result.add(id);
            }
        }
        return result;
    }

    private void requireReference(Map<String, Object> value, String key, Set<String> known, List<String> errors) {
        String target = stringValue(value.get(key));
        if (!hasText(target) || !known.contains(target)) {
            errors.add("Contract element " + idOf(value) + " references unknown " + key + " " + target);
        }
    }

    private void requireOptionalReference(Map<String, Object> value, String key, Set<String> known, List<String> errors) {
        String target = stringValue(value.get(key));
        if (target != null && !known.contains(target)) {
            errors.add("Contract element " + idOf(value) + " references unknown " + key + " " + target);
        }
    }

    private static String idOf(Map<String, Object> value) {
        return value == null ? null : stringValue(value.get("id"));
    }

    private static String stringValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> rows)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object row : rows) if (row instanceof String text && !text.isBlank()) result.add(text);
        return result;
    }

    private boolean validProvidedTypeShape(Deliverable deliverable) {
        List<String> provided = deliverable.providesTypes();
        return switch (deliverable.kind()) {
            case "DDL", "CONFIG" -> true;
            case "ENTITY" -> provided.size() == 1;
            case "VO" -> !provided.isEmpty() && provided.stream().allMatch(type -> type.endsWith("VO"));
            case "MAPPER" -> provided.size() == 1 && provided.get(0).endsWith("Mapper");
            case "SERVICE" -> provided.stream().anyMatch(type -> type.startsWith("I") && type.endsWith("Service"))
                    && provided.stream().anyMatch(type -> type.endsWith("ServiceImpl"));
            case "WRAPPER" -> provided.size() == 1 && provided.get(0).endsWith("Wrapper");
            case "CONTROLLER" -> provided.size() == 1 && provided.get(0).endsWith("Controller");
            case "FEIGN", "EXCEL" -> !provided.isEmpty();
            case "OTHER" -> !hasText(deliverable.className()) || provided.size() == 1;
            default -> false;
        };
    }

    public List<String> validateTypeClosure() {
        Set<String> provided = new LinkedHashSet<>();
        Set<String> duplicateProviders = new LinkedHashSet<>();
        for (Deliverable deliverable : deliverables) {
            if (deliverable == null || "PROHIBIT".equals(deliverable.action())) continue;
            for (String type : deliverable.providesTypes()) if (!provided.add(type)) duplicateProviders.add(type);
        }
        Set<String> referenceTypes = new LinkedHashSet<>();
        for (Map<String, Object> binding : referenceBindings) {
            Object symbol = binding.get("referenceSymbol");
            if (symbol != null) {
                String fqcn = String.valueOf(symbol);
                referenceTypes.add(fqcn.substring(fqcn.lastIndexOf('.') + 1));
            }
        }
        Set<String> missing = new LinkedHashSet<>();
        for (Deliverable deliverable : deliverables) {
            if (deliverable == null || "PROHIBIT".equals(deliverable.action())) continue;
            for (String required : deliverable.requiresTypes()) {
                if (!provided.contains(required) && !referenceTypes.contains(required) && !isFrameworkType(required)) {
                    missing.add(required);
                }
            }
        }
        List<String> errors = new ArrayList<>();
        for (String type : duplicateProviders) errors.add("Business type has multiple providers: " + type);
        for (String type : missing) errors.add("Business type has no provider: " + type);
        return errors;
    }

    private boolean isFrameworkType(String value) {
        return Set.of("String", "Long", "Integer", "Boolean", "Date", "LocalDateTime", "BigDecimal",
                "R", "IPage", "Query", "HttpServletResponse", "MultipartFile").contains(value);
    }

    private static <T> List<T> copy(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    public record Identity(String moduleName, String entityName, String tableName, String basePackage,
                           String apiModuleName, String serviceModuleName, String serviceName) { }

    public record Field(String id, String entityId, String name, String columnName, String javaType,
                        boolean required, String role, String evidence) {
        boolean valid() {
            return hasText(id) && hasText(entityId) && hasText(name) && hasText(columnName) && hasText(javaType)
                    && ("PERSISTENT".equals(role) || "DERIVED".equals(role));
        }
    }

    public record Deliverable(String id, String kind, String name, String moduleId, String className,
                              String moduleSide, String action, List<String> providesTypes,
                              List<String> requiresTypes) {
        public Deliverable {
            providesTypes = providesTypes == null ? List.of() : List.copyOf(providesTypes);
            requiresTypes = requiresTypes == null ? List.of() : List.copyOf(requiresTypes);
        }
        boolean valid() {
            return hasText(id) && hasText(kind) && hasText(name)
                    && Set.of("DDL", "ENTITY", "VO", "MAPPER", "SERVICE", "WRAPPER", "CONTROLLER", "FEIGN",
                    "EXCEL", "CONFIG", "OTHER").contains(kind)
                    && (action == null || Set.of("CREATE", "MODIFY", "EXTEND", "PROHIBIT").contains(action));
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
