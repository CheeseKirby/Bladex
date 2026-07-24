package org.springblade.aiworkflow.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.agent.CanonicalPlanContractV2;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalPlanContractV2Test {

    private static final String FIXTURE_SECRET = "fixture-bundle-secret";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void multipleContractBlocksHaveTheSameCrossLanguageContentHash() throws Exception {
        String content;
        String expected;
        try (InputStream input = getClass().getResourceAsStream("/contracts/multiple-plan-contract-blocks.md")) {
            content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (InputStream input = getClass().getResourceAsStream("/contracts/multiple-plan-contract-blocks.sha256")) {
            expected = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        String lfContent = content.replace("\r\n", "\n").replace('\r', '\n');
        String crlfContent = lfContent.replace("\n", "\r\n");
        assertEquals(expected, CanonicalJsonHasher.contentHash(lfContent));
        assertEquals(expected, CanonicalJsonHasher.contentHash(crlfContent));
    }

    @Test
    void crossLanguageFixtureHasTheSameStructuralHashAndClosedTypes() throws Exception {
        CanonicalPlanContractV2 contract = fixture();
        String expected;
        try (InputStream input = getClass().getResourceAsStream("/contracts/canonical-plan-contract-v2.sha256")) {
            expected = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        assertEquals(expected, CanonicalJsonHasher.contractHash(contract));
        assertTrue(contract.validateStructure().isEmpty(), () -> String.join("; ", contract.validateStructure()));
    }

    @Test
    void wrapperDeliverableIsAcceptedByTheWireContractValidator() throws Exception {
        CanonicalPlanContractV2 base = fixture();
        List<CanonicalPlanContractV2.Deliverable> deliverables = new ArrayList<>(base.deliverables());
        deliverables.add(new CanonicalPlanContractV2.Deliverable(
                "deliverable.wrapper.7", "WRAPPER", "TicketWrapper", "module.ticket",
                "TicketWrapper", "IMPL", "CREATE", List.of("TicketWrapper"),
                List.of("Ticket", "TicketVO")));
        CanonicalPlanContractV2 contract = copyContract(base, "STRUCTURED", deliverables);

        assertTrue(contract.validateStructure().isEmpty(), () -> String.join("; ", contract.validateStructure()));
    }

    @Test
    void reviewedV2BundleIsAcceptedAndTamperingIsRejected() throws Exception {
        CanonicalPlanContractV2 contract = fixture();
        PlanReceiveRequest request = request(contract);
        String expectedBundleHash;
        try (InputStream input = getClass().getResourceAsStream("/contracts/canonical-plan-bundle-v2.sha256")) {
            expectedBundleHash = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        assertEquals(expectedBundleHash, request.getBundleHash());
        String expectedSignature;
        try (InputStream input = getClass().getResourceAsStream("/contracts/canonical-plan-bundle-v2.hmac-sha256")) {
            expectedSignature = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        assertEquals(expectedSignature, request.getBundleSignature());
        PlanRequestValidator validator = new PlanRequestValidator(FIXTURE_SECRET);
        validator.validate(request);

        request.getSubPlans().get(0).setContent("tampered content");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(request));
    }


    @Test
    void crossSubPlanTypeProvidersRequireDirectOrTransitivePrerequisitePaths() throws Exception {
        CanonicalPlanContractV2 contract = fixture();
        PlanReceiveRequest request = request(contract);
        String contractHash = CanonicalJsonHasher.contractHash(contract);
        PlanReceiveRequest.SubPlanVO provider = subPlan("provider", 1, "Domain types",
                List.of(), List.of("deliverable.ddl.1", "deliverable.entity.2", "deliverable.vo.3"), contractHash);
        PlanReceiveRequest.SubPlanVO mapper = subPlan("mapper", 2, "Mapper",
                List.of(), List.of("deliverable.mapper.4"), contractHash);
        PlanReceiveRequest.SubPlanVO service = subPlan("service", 3, "Service",
                List.of("mapper"), List.of("deliverable.service.5"), contractHash);
        PlanReceiveRequest.SubPlanVO controller = subPlan("controller", 4, "Controller",
                List.of("service"), List.of("deliverable.controller.6"), contractHash);
        request.setSubPlans(List.of(provider, mapper, service, controller));
        refreshReviewEvidence(request);

        PlanRequestValidator validator = new PlanRequestValidator(FIXTURE_SECRET);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(request));

        mapper.setPrerequisites(List.of("provider"));
        refreshReviewEvidence(request);
        validator.validate(request);
    }

    @Test
    void legacyCompatibilityIsIsolatedOnlyAndUpdatedPartAIsFailClosed() {
        PlanReceiveRequest legacy = minimalLegacyRequest();
        PlanRequestValidator validator = new PlanRequestValidator("", true);
        validator.validate(legacy);
        assertThrows(IllegalArgumentException.class, () -> new PlanRequestValidator("", false).validate(legacy));

        legacy.setWriteTarget("REAL");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(legacy));

        legacy.setWriteTarget("ISOLATED");
        PlanReceiveRequest.MetadataVO metadata = new PlanReceiveRequest.MetadataVO();
        metadata.setSourceService("ai-designer");
        legacy.setMetadata(metadata);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(legacy));
    }

    @Test
    void deliverableTypeShapesRejectDtoAsVoAndNonControllerClassNames() throws Exception {
        CanonicalPlanContractV2 source = fixture();
        List<CanonicalPlanContractV2.Deliverable> deliverables = new ArrayList<>(source.deliverables());
        CanonicalPlanContractV2.Deliverable vo = deliverables.get(2);
        deliverables.set(2, new CanonicalPlanContractV2.Deliverable(vo.id(), vo.kind(), vo.name(),
                vo.moduleId(), "TicketDTO", vo.moduleSide(), vo.action(), List.of("TicketDTO"), vo.requiresTypes()));
        CanonicalPlanContractV2.Deliverable controller = deliverables.get(5);
        deliverables.set(5, new CanonicalPlanContractV2.Deliverable(controller.id(), controller.kind(),
                controller.name(), controller.moduleId(), "TicketApi", controller.moduleSide(), controller.action(),
                List.of("TicketApi"), controller.requiresTypes()));
        CanonicalPlanContractV2 broken = copyContract(source, source.sourceMode(), deliverables);

        List<String> errors = broken.validateStructure();
        assertTrue(errors.stream().anyMatch(message -> message.contains("deliverable.vo.3 providesTypes do not match kind VO")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("deliverable.controller.6 providesTypes do not match kind CONTROLLER")));
    }

    @Test
    void updatedPartACannotSendLegacyInferredContractAndMissingTypesBlockPreflight() throws Exception {
        CanonicalPlanContractV2 fixture = fixture();
        CanonicalPlanContractV2 legacyContract = copyContract(fixture, "LEGACY_INFERRED", fixture.deliverables());
        PlanReceiveRequest legacyV2Request = request(legacyContract);
        assertThrows(IllegalArgumentException.class, () -> new PlanRequestValidator(FIXTURE_SECRET).validate(legacyV2Request));

        List<CanonicalPlanContractV2.Deliverable> deliverables = new ArrayList<>(fixture.deliverables());
        CanonicalPlanContractV2.Deliverable original = deliverables.get(4);
        deliverables.set(4, new CanonicalPlanContractV2.Deliverable(original.id(), original.kind(), original.name(),
                original.moduleId(), original.className(), original.moduleSide(), original.action(),
                original.providesTypes(), List.of("MissingBusinessType")));
        CanonicalPlanContractV2 broken = copyContract(fixture, fixture.sourceMode(), deliverables);
        assertTrue(broken.validateStructure().stream()
                .anyMatch(message -> message.contains("Business type has no provider: MissingBusinessType")));
    }

    @Test
    void signedEnvelopeAuthenticatesWriteTargetAndGenerationIdentity() throws Exception {
        PlanRequestValidator validator = new PlanRequestValidator(FIXTURE_SECRET);
        PlanReceiveRequest writeTargetTamper = request(fixture());
        writeTargetTamper.setWriteTarget("REAL");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(writeTargetTamper));

        PlanReceiveRequest missingIdentity = request(fixture());
        missingIdentity.setGenerationIdentity(null);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missingIdentity));
    }

    @Test
    void forgedReviewEvidenceAndMissingSigningConfigurationAreRejected() throws Exception {
        PlanReceiveRequest request = request(fixture());
        request.getReviewManifest().getSubPlanReviews().get(0).setReviewId("forged-review");
        assertThrows(IllegalArgumentException.class,
                () -> new PlanRequestValidator(FIXTURE_SECRET).validate(request));

        PlanReceiveRequest unsigned = request(fixture());
        unsigned.setBundleSignature(null);
        assertThrows(IllegalArgumentException.class,
                () -> new PlanRequestValidator(FIXTURE_SECRET).validate(unsigned));

        PlanReceiveRequest unconfigured = request(fixture());
        assertThrows(IllegalStateException.class, () -> new PlanRequestValidator("").validate(unconfigured));
    }

    @Test
    void realWriteParsingCannotBypassTheCanonicalSignedBundleRequirement() {
        PlanReceiveRequest legacy = minimalLegacyRequest();
        legacy.setWriteTarget(" REAL ");
        assertThrows(IllegalArgumentException.class, () -> new PlanRequestValidator("").validate(legacy));
    }

    private PlanReceiveRequest.SubPlanVO subPlan(String id, int index, String title,
                                                    List<String> prerequisites, List<String> deliverableIds,
                                                    String contractHash) {
        PlanReceiveRequest.SubPlanVO subPlan = new PlanReceiveRequest.SubPlanVO();
        subPlan.setId(id);
        subPlan.setIndex(index);
        subPlan.setTitle(title);
        subPlan.setContent("Reviewed " + id);
        subPlan.setPrerequisites(prerequisites);
        subPlan.setDeliverableIds(deliverableIds);
        subPlan.setContractHash(contractHash);
        return subPlan;
    }

    private void refreshReviewEvidence(PlanReceiveRequest request) {
        List<PlanReceiveRequest.SubPlanReviewVO> reviews = new ArrayList<>();
        for (PlanReceiveRequest.SubPlanVO subPlan : request.getSubPlans()) {
            PlanReceiveRequest.SubPlanReviewVO review = new PlanReceiveRequest.SubPlanReviewVO();
            review.setSubPlanId(subPlan.getId());
            review.setReviewId("review-" + subPlan.getId());
            review.setContentHash(CanonicalJsonHasher.contentHash(subPlan.getContent()));
            reviews.add(review);
        }
        request.getReviewManifest().setSubPlanReviews(reviews);
        request.setBundleHash(CanonicalJsonHasher.bundleHash(request,
                CanonicalJsonHasher.contractHash(request.getCanonicalContract())));
        request.setBundleSignature(CanonicalJsonHasher.bundleSignature(request, FIXTURE_SECRET));
    }

    private PlanReceiveRequest minimalLegacyRequest() {
        PlanReceiveRequest request = new PlanReceiveRequest();
        request.setProjectId("legacy-project");
        request.setProjectName("Legacy");
        request.setWriteTarget("ISOLATED");
        PlanReceiveRequest.MetadataVO metadata = new PlanReceiveRequest.MetadataVO();
        metadata.setSourceService("legacy-replay");
        request.setMetadata(metadata);
        PlanReceiveRequest.MasterPlanVO master = new PlanReceiveRequest.MasterPlanVO();
        master.setId("legacy-master");
        master.setVersion(1);
        master.setContent("# Legacy master");
        request.setMasterPlan(master);
        PlanReceiveRequest.SubPlanVO subPlan = new PlanReceiveRequest.SubPlanVO();
        subPlan.setId("legacy-sub");
        subPlan.setIndex(1);
        subPlan.setTitle("Legacy sub-plan");
        subPlan.setContent("Legacy Markdown");
        subPlan.setPrerequisites(List.of());
        request.setSubPlans(List.of(subPlan));
        return request;
    }

    private CanonicalPlanContractV2 copyContract(CanonicalPlanContractV2 source, String sourceMode,
                                                  List<CanonicalPlanContractV2.Deliverable> deliverables) {
        return new CanonicalPlanContractV2(source.contractVersion(), source.sourceHash(), sourceMode,
                source.referenceSnapshotId(), source.rulesetVersion(), source.identity(), source.fields(),
                source.domains(), source.modules(), source.aggregates(), source.entities(), source.states(),
                source.integrations(), deliverables, source.referenceBindings(), source.architectureDecisions());
    }

    private PlanReceiveRequest request(CanonicalPlanContractV2 contract) {
        PlanReceiveRequest request = new PlanReceiveRequest();
        request.setProjectId("fixture-project");
        request.setProjectName("Fixture project");
        request.setWriteTarget("ISOLATED");
        PlanReceiveRequest.MetadataVO metadata = new PlanReceiveRequest.MetadataVO();
        metadata.setSourceService("ai-designer");
        request.setMetadata(metadata);
        PlanReceiveRequest.MasterPlanVO master = new PlanReceiveRequest.MasterPlanVO();
        master.setId("master-1");
        master.setVersion(1);
        master.setContent("# Fixture master plan");
        request.setMasterPlan(master);
        PlanReceiveRequest.SubPlanVO subPlan = new PlanReceiveRequest.SubPlanVO();
        subPlan.setId("sub-1");
        subPlan.setIndex(1);
        subPlan.setTitle("All canonical deliverables");
        subPlan.setContent("Reviewed sub-plan");
        subPlan.setPrerequisites(List.of());
        subPlan.setDeliverableIds(contract.deliverables().stream().map(CanonicalPlanContractV2.Deliverable::id).toList());
        String contractHash = CanonicalJsonHasher.contractHash(contract);
        subPlan.setContractHash(contractHash);
        request.setSubPlans(List.of(subPlan));
        request.setCanonicalContract(contract);
        PlanReceiveRequest.GenerationIdentityVO identity = new PlanReceiveRequest.GenerationIdentityVO();
        identity.setModuleName(contract.identity().moduleName());
        identity.setEntityName(contract.identity().entityName());
        identity.setTableName(contract.identity().tableName());
        identity.setBasePackage(contract.identity().basePackage());
        identity.setApiModuleName(contract.identity().apiModuleName());
        identity.setServiceModuleName(contract.identity().serviceModuleName());
        identity.setServiceName(contract.identity().serviceName());
        request.setGenerationIdentity(identity);
        PlanReceiveRequest.ReviewManifestVO manifest = new PlanReceiveRequest.ReviewManifestVO();
        manifest.setMasterReviewId("review-master");
        manifest.setMasterContentHash(CanonicalJsonHasher.contentHash(master.getContent()));
        manifest.setContractHash(contractHash);
        manifest.setRulesetVersion(contract.rulesetVersion());
        manifest.setReferenceSnapshotId(contract.referenceSnapshotId());
        PlanReceiveRequest.SubPlanReviewVO review = new PlanReceiveRequest.SubPlanReviewVO();
        review.setSubPlanId("sub-1");
        review.setReviewId("review-sub-1");
        review.setContentHash(CanonicalJsonHasher.contentHash(subPlan.getContent()));
        manifest.setSubPlanReviews(List.of(review));
        request.setReviewManifest(manifest);
        request.setBundleHash(CanonicalJsonHasher.bundleHash(request, contractHash));
        request.setBundleSignature(CanonicalJsonHasher.bundleSignature(request, FIXTURE_SECRET));
        return request;
    }

    private CanonicalPlanContractV2 fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/contracts/canonical-plan-contract-v2.json")) {
            return objectMapper.readValue(input, CanonicalPlanContractV2.class);
        }
    }
}
