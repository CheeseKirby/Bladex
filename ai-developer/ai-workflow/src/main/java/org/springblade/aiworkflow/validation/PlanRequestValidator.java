package org.springblade.aiworkflow.validation;

import org.springblade.aiworkflow.agent.CanonicalPlanContractV2;
import org.springblade.aiworkflow.enums.WriteTarget;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates cross-sub-plan invariants before any database writes occur. */
@Component
public class PlanRequestValidator {

    private final String bundleSigningSecret;
    private final boolean legacyIsolatedEnabled;

    public PlanRequestValidator() {
        this(resolveSigningSecretFromEnvironment(), false);
    }

    public PlanRequestValidator(String bundleSigningSecret) {
        this(bundleSigningSecret, false);
    }

    @Autowired
    public PlanRequestValidator(
            @Value("${ai-workflow.bundle-signing-secret:}") String bundleSigningSecret,
            @Value("${ai-workflow.legacy-isolated-enabled:false}") boolean legacyIsolatedEnabled) {
        this.bundleSigningSecret = bundleSigningSecret == null ? "" : bundleSigningSecret.trim();
        this.legacyIsolatedEnabled = legacyIsolatedEnabled;
    }

    public void validate(PlanReceiveRequest request) {
        if (request == null) throw new IllegalArgumentException("Plan request must not be null");
        if (request.getMasterPlan() == null) throw new IllegalArgumentException("Master plan is required");
        if (request.getSubPlans() == null || request.getSubPlans().isEmpty()) {
            throw new IllegalArgumentException("At least one sub-plan is required");
        }

        Map<String, PlanReceiveRequest.SubPlanVO> byId = new HashMap<>();
        Set<Integer> indexes = new HashSet<>();
        for (PlanReceiveRequest.SubPlanVO subPlan : request.getSubPlans()) {
            if (subPlan == null) throw new IllegalArgumentException("Sub-plan entries must not be null");
            String id = subPlan.getId();
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Sub-plan ID is required");
            if (subPlan.getIndex() == null) throw new IllegalArgumentException("Sub-plan index is required: " + id);
            if (byId.putIfAbsent(id, subPlan) != null) {
                throw new IllegalArgumentException("Duplicate sub-plan ID: " + id);
            }
            if (!indexes.add(subPlan.getIndex())) {
                throw new IllegalArgumentException("Duplicate sub-plan index: " + subPlan.getIndex());
            }
        }

        for (PlanReceiveRequest.SubPlanVO subPlan : request.getSubPlans()) {
            List<String> prerequisites = subPlan.getPrerequisites();
            if (prerequisites == null) continue;
            Set<String> unique = new HashSet<>();
            for (String prerequisite : prerequisites) {
                if (prerequisite == null || prerequisite.isBlank()) {
                    throw new IllegalArgumentException("Prerequisite IDs must not be blank in sub-plan " + subPlan.getId());
                }
                if (!unique.add(prerequisite)) {
                    throw new IllegalArgumentException("Duplicate prerequisite " + prerequisite
                            + " in sub-plan " + subPlan.getId());
                }
                if (prerequisite.equals(subPlan.getId())) {
                    throw new IllegalArgumentException("Sub-plan cannot depend on itself: " + subPlan.getId());
                }
                if (!byId.containsKey(prerequisite)) {
                    throw new IllegalArgumentException("Unknown prerequisite " + prerequisite
                            + " in sub-plan " + subPlan.getId());
                }
            }
        }

        detectCycles(byId);
        validateCanonicalBundle(request, byId);
    }

    private void validateCanonicalBundle(PlanReceiveRequest request,
                                         Map<String, PlanReceiveRequest.SubPlanVO> byId) {
        CanonicalPlanContractV2 contract = request.getCanonicalContract();
        boolean updatedPartA = request.getMetadata() != null
                && "ai-designer".equalsIgnoreCase(request.getMetadata().getSourceService());
        boolean realWrite = WriteTarget.parse(request.getWriteTarget()).isReal();
        if (contract == null) {
            if (updatedPartA || realWrite) {
                throw new IllegalArgumentException("canonicalContract v2 is required for updated Part A and REAL writes");
            }
            boolean legacyReplay = request.getMetadata() != null
                    && "legacy-replay".equalsIgnoreCase(request.getMetadata().getSourceService());
            if (!legacyIsolatedEnabled || !legacyReplay) {
                throw new IllegalArgumentException("Unsigned legacy isolated intake is disabled or not marked as legacy-replay");
            }
            return;
        }
        List<String> structureErrors = contract.validateStructure();
        if (!structureErrors.isEmpty()) {
            throw new IllegalArgumentException("Invalid canonical contract: " + String.join("; ", structureErrors));
        }
        if (updatedPartA && !"STRUCTURED".equals(contract.sourceMode())) {
            throw new IllegalArgumentException("Updated Part A requests must use a STRUCTURED canonicalContract v2");
        }
        String masterContentHash = CanonicalJsonHasher.contentHash(request.getMasterPlan().getContent());
        if (!masterContentHash.equals(contract.sourceHash())) {
            throw new IllegalArgumentException("canonicalContract.sourceHash does not match masterPlan.content");
        }
        if (request.getReviewManifest() == null) {
            throw new IllegalArgumentException("reviewManifest is required with canonicalContract v2");
        }
        String contractHash = CanonicalJsonHasher.contractHash(contract);
        if (!contractHash.equals(request.getReviewManifest().getContractHash())) {
            throw new IllegalArgumentException("reviewManifest.contractHash does not match canonicalContract");
        }
        if (!masterContentHash.equals(request.getReviewManifest().getMasterContentHash())) {
            throw new IllegalArgumentException("reviewManifest.masterContentHash does not match masterPlan.content");
        }
        if (!contract.rulesetVersion().equals(request.getReviewManifest().getRulesetVersion())) {
            throw new IllegalArgumentException("reviewManifest.rulesetVersion does not match canonicalContract");
        }
        if (!java.util.Objects.equals(request.getReviewManifest().getReferenceSnapshotId(), contract.referenceSnapshotId())) {
            throw new IllegalArgumentException("reviewManifest.referenceSnapshotId does not match canonicalContract");
        }
        if (request.getGenerationIdentity() == null) {
            throw new IllegalArgumentException("generationIdentity is required with canonicalContract v2");
        }
        {
            CanonicalPlanContractV2.Identity identity = contract.identity();
            if (!identity.moduleName().equals(request.getGenerationIdentity().getModuleName())
                    || !identity.entityName().equals(request.getGenerationIdentity().getEntityName())
                    || !identity.tableName().equals(request.getGenerationIdentity().getTableName())
                    || !identity.basePackage().equals(request.getGenerationIdentity().getBasePackage())
                    || !identity.apiModuleName().equals(request.getGenerationIdentity().getApiModuleName())
                    || !identity.serviceModuleName().equals(request.getGenerationIdentity().getServiceModuleName())
                    || !identity.serviceName().equals(request.getGenerationIdentity().getServiceName())) {
                throw new IllegalArgumentException("generationIdentity does not match canonicalContract.identity");
            }
        }

        Map<String, String> deliverableOwners = new HashMap<>();
        Set<String> knownDeliverables = new HashSet<>();
        for (CanonicalPlanContractV2.Deliverable deliverable : contract.deliverables()) {
            knownDeliverables.add(deliverable.id());
        }
        for (PlanReceiveRequest.SubPlanVO subPlan : request.getSubPlans()) {
            if (subPlan.getDeliverableIds() == null || subPlan.getDeliverableIds().isEmpty()) {
                throw new IllegalArgumentException("deliverableIds are required for sub-plan " + subPlan.getId());
            }
            if (!contractHash.equals(subPlan.getContractHash())) {
                throw new IllegalArgumentException("Sub-plan contractHash mismatch: " + subPlan.getId());
            }
            Set<String> localDeliverables = new HashSet<>();
            for (String deliverableId : subPlan.getDeliverableIds()) {
                if (!localDeliverables.add(deliverableId)) {
                    throw new IllegalArgumentException("Duplicate deliverable " + deliverableId
                            + " in sub-plan " + subPlan.getId());
                }
                if (!knownDeliverables.contains(deliverableId)) {
                    throw new IllegalArgumentException("Unknown deliverable " + deliverableId + " in sub-plan " + subPlan.getId());
                }
                CanonicalPlanContractV2.Deliverable assigned = contract.deliverables().stream()
                        .filter(item -> deliverableId.equals(item.id())).findFirst().orElseThrow();
                if ("PROHIBIT".equals(assigned.action())) {
                    throw new IllegalArgumentException("Prohibited deliverable is assigned: " + deliverableId);
                }
                String owner = deliverableOwners.putIfAbsent(deliverableId, subPlan.getId());
                if (owner != null && !owner.equals(subPlan.getId())) {
                    throw new IllegalArgumentException("Deliverable " + deliverableId + " has multiple owners");
                }
            }
        }
        for (CanonicalPlanContractV2.Deliverable deliverable : contract.deliverables()) {
            if (!"OTHER".equals(deliverable.kind()) && !"PROHIBIT".equals(deliverable.action())
                    && !deliverableOwners.containsKey(deliverable.id())) {
                throw new IllegalArgumentException("Required deliverable is not assigned: " + deliverable.id());
            }
        }
        Map<String, String> typeProviders = new HashMap<>();
        for (CanonicalPlanContractV2.Deliverable deliverable : contract.deliverables()) {
            if ("PROHIBIT".equals(deliverable.action())) continue;
            String owner = deliverableOwners.get(deliverable.id());
            if (owner != null) for (String type : deliverable.providesTypes()) typeProviders.put(type, owner);
        }
        Map<String, CanonicalPlanContractV2.Deliverable> deliverablesById = new HashMap<>();
        for (CanonicalPlanContractV2.Deliverable deliverable : contract.deliverables()) {
            deliverablesById.put(deliverable.id(), deliverable);
        }
        for (PlanReceiveRequest.SubPlanVO subPlan : request.getSubPlans()) {
            for (String deliverableId : subPlan.getDeliverableIds()) {
                CanonicalPlanContractV2.Deliverable deliverable = deliverablesById.get(deliverableId);
                for (String requiredType : deliverable.requiresTypes()) {
                    String provider = typeProviders.get(requiredType);
                    if (provider != null && !provider.equals(subPlan.getId())
                            && !dependsTransitively(subPlan.getId(), provider, byId, new HashSet<>())) {
                        throw new IllegalArgumentException("Sub-plan " + subPlan.getId() + " consumes "
                                + requiredType + " from " + provider + " without a prerequisite path");
                    }
                }
            }
        }

        Map<String, PlanReceiveRequest.SubPlanReviewVO> reviewBySubPlan = new HashMap<>();
        if (request.getReviewManifest().getSubPlanReviews() != null) {
            for (PlanReceiveRequest.SubPlanReviewVO review : request.getReviewManifest().getSubPlanReviews()) {
                if (review == null || review.getSubPlanId() == null || review.getSubPlanId().isBlank()) {
                    throw new IllegalArgumentException("Sub-plan review evidence contains a blank subject");
                }
                if (reviewBySubPlan.putIfAbsent(review.getSubPlanId(), review) != null) {
                    throw new IllegalArgumentException("Duplicate review evidence for sub-plan " + review.getSubPlanId());
                }
            }
        }
        if (!reviewBySubPlan.keySet().equals(byId.keySet())) {
            throw new IllegalArgumentException("Sub-plan review evidence subjects do not match the plan bundle");
        }
        if (request.getReviewManifest().getMasterReviewId() == null
                || request.getReviewManifest().getMasterReviewId().isBlank()) {
            throw new IllegalArgumentException("Master review evidence is required");
        }
        for (String subPlanId : byId.keySet()) {
            PlanReceiveRequest.SubPlanReviewVO review = reviewBySubPlan.get(subPlanId);
            if (review == null || review.getReviewId() == null || review.getReviewId().isBlank()) {
                throw new IllegalArgumentException("Review evidence is missing for sub-plan " + subPlanId);
            }
            String actualHash = CanonicalJsonHasher.contentHash(byId.get(subPlanId).getContent());
            if (!actualHash.equals(review.getContentHash())) {
                throw new IllegalArgumentException("Review content hash mismatch for sub-plan " + subPlanId);
            }
        }
        String bundleHash = CanonicalJsonHasher.bundleHash(request, contractHash);
        if (request.getBundleHash() == null || !bundleHash.equals(request.getBundleHash())) {
            throw new IllegalArgumentException("bundleHash does not match the reviewed plan bundle");
        }
        if (bundleSigningSecret.isEmpty()) {
            throw new IllegalStateException("Plan bundle signing secret is not configured; canonical v2 requests are fail-closed");
        }
        if (!CanonicalJsonHasher.verifyBundleSignature(request, bundleSigningSecret)) {
            throw new IllegalArgumentException("bundleSignature is missing or invalid");
        }
    }

    private static String resolveSigningSecretFromEnvironment() {
        String primary = System.getenv("PLAN_BUNDLE_SIGNING_SECRET");
        if (primary != null && !primary.isBlank()) return primary;
        String compatibility = System.getenv("AI_WORKFLOW_BUNDLE_SIGNING_SECRET");
        return compatibility == null ? "" : compatibility;
    }

    private boolean dependsTransitively(String current, String target,
                                         Map<String, PlanReceiveRequest.SubPlanVO> byId,
                                         Set<String> visited) {
        if (current.equals(target)) return true;
        if (!visited.add(current)) return false;
        List<String> prerequisites = byId.get(current).getPrerequisites();
        if (prerequisites == null) return false;
        for (String prerequisite : prerequisites) {
            if (dependsTransitively(prerequisite, target, byId, visited)) return true;
        }
        return false;
    }

    private void detectCycles(Map<String, PlanReceiveRequest.SubPlanVO> byId) {
        Map<String, Integer> state = new HashMap<>();
        for (String id : byId.keySet()) {
            visit(id, byId, state);
        }
    }

    private void visit(String id, Map<String, PlanReceiveRequest.SubPlanVO> byId, Map<String, Integer> state) {
        int currentState = state.getOrDefault(id, 0);
        if (currentState == 2) return;
        if (currentState == 1) throw new IllegalArgumentException("Cyclic sub-plan dependency involving: " + id);

        state.put(id, 1);
        List<String> prerequisites = byId.get(id).getPrerequisites();
        if (prerequisites != null) {
            for (String prerequisite : prerequisites) visit(prerequisite, byId, state);
        }
        state.put(id, 2);
    }
}
