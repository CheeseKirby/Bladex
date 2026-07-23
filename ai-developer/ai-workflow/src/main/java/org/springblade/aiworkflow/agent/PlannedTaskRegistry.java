package org.springblade.aiworkflow.agent;

import org.springblade.aiworkflow.enums.TaskType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates all reviewed sub-plan contributions for one physical target into a single generation task.
 * A physical Java type is generated once, but its prompt contains every compatible contribution.
 */
final class PlannedTaskRegistry {

    private final Map<String, Claim> byPath = new LinkedHashMap<>();
    private final Map<String, Claim> byFqcn = new LinkedHashMap<>();

    Registration claim(Long subPlanId, AtomicTask task) {
        String path = normalize(task == null ? null : task.getTargetPath());
        if (path == null || path.isBlank()) {
            return Registration.schedule(task);
        }

        Claim pathOwner = byPath.get(path);
        if (pathOwner != null) {
            String incompatibility = incompatibility(pathOwner.canonicalTask(), task);
            if (incompatibility != null) {
                return Registration.reject(incompatibility, pathOwner);
            }
            mergeContribution(pathOwner.canonicalTask(), task, subPlanId);
            pathOwner.addContributor(subPlanId);
            return Registration.merge("PLAN-MERGED-TARGET-PATH", pathOwner);
        }

        String fqcn = expectedFqcn(path);
        Claim fqcnOwner = fqcn == null ? null : byFqcn.get(fqcn);
        if (fqcnOwner != null) {
            return Registration.reject("PLAN-DUPLICATE-TARGET-FQCN", fqcnOwner);
        }

        Claim claim = new Claim(subPlanId, path, fqcn, task);
        byPath.put(path, claim);
        if (fqcn != null) byFqcn.put(fqcn, claim);
        return Registration.schedule(claim);
    }

    private static String incompatibility(AtomicTask existing, AtomicTask incoming) {
        if (existing == null || incoming == null) return "PLAN-CONFLICTING-TARGET-TASK";
        TaskType existingType = existing.getType();
        TaskType incomingType = incoming.getType();
        if (existingType != null && incomingType != null && existingType != incomingType
                && existingType != TaskType.OTHER && incomingType != TaskType.OTHER) {
            return "PLAN-CONFLICTING-TASK-TYPE";
        }
        if (differentNonBlank(existing.getExpectedClassName(), incoming.getExpectedClassName())) {
            return "PLAN-CONFLICTING-TARGET-CLASS";
        }
        if (differentNonBlank(existing.getModuleName(), incoming.getModuleName())) {
            return "PLAN-CONFLICTING-TARGET-MODULE";
        }
        return null;
    }

    private static boolean differentNonBlank(String left, String right) {
        return left != null && !left.isBlank() && right != null && !right.isBlank() && !left.equals(right);
    }

    private static void mergeContribution(AtomicTask target, AtomicTask contribution, Long subPlanId) {
        if ((target.getType() == null || target.getType() == TaskType.OTHER)
                && contribution.getType() != null) {
            target.setType(contribution.getType());
        }
        if (isBlank(target.getExpectedClassName())) target.setExpectedClassName(contribution.getExpectedClassName());
        if (isBlank(target.getEntityName())) target.setEntityName(contribution.getEntityName());
        if (isBlank(target.getModuleName())) target.setModuleName(contribution.getModuleName());
        if (target.getGenerationContext() == null) target.setGenerationContext(contribution.getGenerationContext());
        copyReferenceMetadataIfMissing(target, contribution);

        String contributionText = contribution.getTaskDescription();
        if (contributionText == null || contributionText.isBlank()) return;
        String current = target.getTaskDescription();
        if (current != null && current.contains(contributionText)) return;
        String heading = "== ADDITIONAL REVIEWED CONTRIBUTION FROM SUB-PLAN "
                + (subPlanId == null ? "UNKNOWN" : subPlanId) + " ==";
        target.setTaskDescription((current == null || current.isBlank() ? "" : current + "\n\n")
                + heading + "\n" + contributionText);
    }

    private static void copyReferenceMetadataIfMissing(AtomicTask target, AtomicTask source) {
        if (isBlank(target.getSelectedReferenceClass())) target.setSelectedReferenceClass(source.getSelectedReferenceClass());
        if (isBlank(target.getSelectedReferenceModule())) target.setSelectedReferenceModule(source.getSelectedReferenceModule());
        if (isBlank(target.getSelectedReferencePath())) target.setSelectedReferencePath(source.getSelectedReferencePath());
        if (target.getReferenceScore() == null) target.setReferenceScore(source.getReferenceScore());
        if (isBlank(target.getReferenceReason())) target.setReferenceReason(source.getReferenceReason());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    static final class Claim {
        private final Long subPlanId;
        private final String targetPath;
        private final String expectedFqcn;
        private final AtomicTask canonicalTask;
        private final LinkedHashSet<Long> contributorSubPlanIds = new LinkedHashSet<>();

        Claim(Long subPlanId, String targetPath, String expectedFqcn, AtomicTask canonicalTask) {
            this.subPlanId = subPlanId;
            this.targetPath = targetPath;
            this.expectedFqcn = expectedFqcn;
            this.canonicalTask = Objects.requireNonNull(canonicalTask, "canonicalTask");
            addContributor(subPlanId);
        }

        Long subPlanId() { return subPlanId; }
        String targetPath() { return targetPath; }
        String expectedFqcn() { return expectedFqcn; }
        AtomicTask canonicalTask() { return canonicalTask; }
        List<Long> contributorSubPlanIds() { return List.copyOf(contributorSubPlanIds); }

        void addContributor(Long contributor) {
            if (contributor != null) contributorSubPlanIds.add(contributor);
        }
    }

    record Registration(boolean accepted, boolean scheduled, boolean merged,
                        String rule, Claim owner, AtomicTask canonicalTask) {
        static Registration schedule(AtomicTask task) {
            return new Registration(true, true, false, null, null, task);
        }

        static Registration schedule(Claim claim) {
            return new Registration(true, true, false, null, claim, claim.canonicalTask());
        }

        static Registration merge(String rule, Claim owner) {
            return new Registration(true, false, true, rule, owner, owner.canonicalTask());
        }

        static Registration reject(String rule, Claim owner) {
            return new Registration(false, false, false, rule, owner, owner == null ? null : owner.canonicalTask());
        }
    }
}
