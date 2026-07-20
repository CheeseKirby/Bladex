package org.springblade.aiworkflow.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Enforces one owning sub-plan for each physical target path and expected Java FQCN. */
final class PlannedTaskRegistry {

    private final Map<String, Claim> byPath = new LinkedHashMap<>();
    private final Map<String, Claim> byFqcn = new LinkedHashMap<>();

    Registration claim(Long subPlanId, AtomicTask task) {
        String path = normalize(task == null ? null : task.getTargetPath());
        if (path == null || path.isBlank()) {
            return Registration.allow();
        }

        Claim pathOwner = byPath.get(path);
        if (pathOwner != null) {
            return Registration.reject("PLAN-DUPLICATE-TARGET-PATH", pathOwner);
        }

        String fqcn = expectedFqcn(path);
        Claim fqcnOwner = fqcn == null ? null : byFqcn.get(fqcn);
        if (fqcnOwner != null) {
            return Registration.reject("PLAN-DUPLICATE-TARGET-FQCN", fqcnOwner);
        }

        Claim claim = new Claim(subPlanId, path, fqcn);
        byPath.put(path, claim);
        if (fqcn != null) byFqcn.put(fqcn, claim);
        return Registration.allow();
    }

    static String expectedFqcn(String targetPath) {
        String path = normalize(targetPath);
        if (path == null || !path.endsWith(".java")) return null;
        String marker = "/src/main/java/";
        int markerIndex = path.indexOf(marker);
        if (markerIndex < 0) return null;
        String relative = path.substring(markerIndex + marker.length(), path.length() - ".java".length());
        return relative.isBlank() ? null : relative.replace('/', '.');
    }

    static String normalize(String path) {
        if (path == null) return null;
        String normalized = path.replace('\\', '/');
        while (normalized.contains("//")) normalized = normalized.replace("//", "/");
        return normalized;
    }

    record Claim(Long subPlanId, String targetPath, String expectedFqcn) {
    }

    record Registration(boolean accepted, String rule, Claim owner) {
        static Registration allow() {
            return new Registration(true, null, null);
        }

        static Registration reject(String rule, Claim owner) {
            return new Registration(false, rule, owner);
        }
    }
}
