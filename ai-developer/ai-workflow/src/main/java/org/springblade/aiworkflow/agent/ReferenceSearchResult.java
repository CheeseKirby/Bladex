package org.springblade.aiworkflow.agent;

import java.util.List;
import java.util.Map;

/**
 * A bounded, evidence-bearing view of the reference project for one business intent.
 */
public record ReferenceSearchResult(
        String snapshotId,
        ReferenceFrameworkProfile profile,
        String intent,
        List<Symbol> symbols,
        List<Relation> relations,
        List<Anomaly> anomalies,
        List<AccessDecision> decisions
) {
    public ReferenceSearchResult {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
        relations = relations == null ? List.of() : List.copyOf(relations);
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public record Symbol(
            int score,
            boolean relationExpanded,
            String simpleName,
            String packageName,
            String type,
            String module,
            String side,
            String mavenModulePath,
            String relativePath,
            String tableName,
            List<String> publicMethodSignatures,
            Map<String, String> fields
    ) {
        public Symbol {
            simpleName = requiredWireString(simpleName, "simpleName");
            packageName = nullableWireString(packageName);
            type = requiredWireString(type, "type");
            module = nullableWireString(module);
            side = nullableWireString(side);
            relativePath = requiredWireString(relativePath, "relativePath");
            publicMethodSignatures = publicMethodSignatures == null ? List.of() : List.copyOf(publicMethodSignatures);
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }

    public record Relation(String source, String target, String type, String evidence) {}

    public record Anomaly(String code, String severity, String message, String evidencePath) {}

    private static String nullableWireString(String value) {
        return value == null ? "" : value;
    }

    private static String requiredWireString(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public record AccessDecision(
            String capability,
            String decision,
            String targetModule,
            double confidence,
            String reason,
            List<String> evidenceSymbols
    ) {
        public AccessDecision {
            evidenceSymbols = evidenceSymbols == null ? List.of() : List.copyOf(evidenceSymbols);
        }
    }
}
