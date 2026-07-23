package org.springblade.aiworkflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.mockito.ArgumentCaptor;
import org.springblade.aiworkflow.agent.CanonicalPlanContractV2;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.mapper.AiExecutionLogMapper;
import org.springblade.aiworkflow.mapper.AiGeneratedFileMapper;
import org.springblade.aiworkflow.mapper.AiPlanMapper;
import org.springblade.aiworkflow.mapper.AiSubPlanMapper;
import org.springblade.aiworkflow.validation.CanonicalJsonHasher;
import org.springblade.aiworkflow.validation.PlanRequestValidator;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanExecutionServiceV2PersistenceTest {

    private static final String FIXTURE_SECRET = "fixture-bundle-secret";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void receivePersistsCanonicalContractReviewManifestAndSubPlanSlices() throws Exception {
        AiPlanMapper planMapper = mock(AiPlanMapper.class);
        AiSubPlanMapper subPlanMapper = mock(AiSubPlanMapper.class);
        when(planMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            AiPlan plan = invocation.getArgument(0);
            plan.setId(91L);
            return 1;
        }).when(planMapper).insert(any(AiPlan.class));
        doAnswer(invocation -> {
            AiSubPlan subPlan = invocation.getArgument(0);
            subPlan.setId(92L);
            return 1;
        }).when(subPlanMapper).insert(any(AiSubPlan.class));

        PlanExecutionServiceImpl service = new PlanExecutionServiceImpl(
                planMapper, subPlanMapper, mock(AiGeneratedFileMapper.class), mock(AiExecutionLogMapper.class),
                null, objectMapper, null, null, new PlanRequestValidator(FIXTURE_SECRET));
        PlanReceiveRequest request = canonicalRequest();

        service.receivePlan(request);

        ArgumentCaptor<AiPlan> planCaptor = ArgumentCaptor.forClass(AiPlan.class);
        verify(planMapper).insert(planCaptor.capture());
        AiPlan persistedPlan = planCaptor.getValue();
        assertNotNull(persistedPlan.getCanonicalContractJson());
        assertNotNull(persistedPlan.getReviewManifestJson());
        assertEquals(request.getBundleHash(), persistedPlan.getBundleHash());
        assertEquals(request.getBundleSignature(), persistedPlan.getBundleSignature());
        assertEquals(CanonicalJsonHasher.idempotencyKey(request), persistedPlan.getIdempotencyKey());
        assertEquals("2.0", objectMapper.readTree(persistedPlan.getCanonicalContractJson()).get("contractVersion").asText());
        assertEquals("review-master", objectMapper.readTree(persistedPlan.getReviewManifestJson()).get("masterReviewId").asText());

        ArgumentCaptor<AiSubPlan> subPlanCaptor = ArgumentCaptor.forClass(AiSubPlan.class);
        verify(subPlanMapper).insert(subPlanCaptor.capture());
        AiSubPlan persistedSubPlan = subPlanCaptor.getValue();
        assertEquals(request.getSubPlans().get(0).getContractHash(), persistedSubPlan.getContractHash());
        assertTrue(persistedSubPlan.getDeliverableIdsJson().contains("deliverable.entity.2"));
        assertTrue(persistedSubPlan.getReferencedElementIdsJson().contains("entity.ticket"));
        assertTrue(persistedSubPlan.getInputTypesJson().contains("Ticket"));
        assertTrue(persistedSubPlan.getOutputTypesJson().contains("TicketVO"));
    }

    @Test
    void concurrentDuplicateInsertReturnsTheDatabaseWinnerWithoutCreatingSubPlans() throws Exception {
        AiPlanMapper planMapper = mock(AiPlanMapper.class);
        AiSubPlanMapper subPlanMapper = mock(AiSubPlanMapper.class);
        AiPlan winner = new AiPlan();
        winner.setId(77L);
        winner.setReceptionId("rec-winner");
        winner.setStatus(org.springblade.aiworkflow.enums.PlanStatus.RECEIVED);
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(planMapper.selectByIdempotencyKeyForUpdate(any())).thenReturn(winner);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate idempotency key"))
                .when(planMapper).insert(any(AiPlan.class));
        when(subPlanMapper.selectByPlanId(77L)).thenReturn(List.of());

        PlanExecutionServiceImpl service = new PlanExecutionServiceImpl(
                planMapper, subPlanMapper, mock(AiGeneratedFileMapper.class), mock(AiExecutionLogMapper.class),
                null, objectMapper, null, null, new PlanRequestValidator(FIXTURE_SECRET));

        org.springblade.aiworkflow.vo.PlanReceiveResponse response = service.receivePlan(canonicalRequest());

        assertEquals("rec-winner", response.getReceptionId());
        assertEquals("RECEIVED", response.getStatus());
        assertFalse(response.getSubPlanStatuses() == null);
        verify(subPlanMapper, never()).insert(any(AiSubPlan.class));
    }

    private PlanReceiveRequest canonicalRequest() throws Exception {
        CanonicalPlanContractV2 contract;
        try (InputStream input = getClass().getResourceAsStream("/contracts/canonical-plan-contract-v2.json")) {
            contract = objectMapper.readValue(input, CanonicalPlanContractV2.class);
        }
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
        subPlan.setReferencedElementIds(List.of("entity.ticket", "aggregate.ticket"));
        subPlan.setInputTypes(List.of("Ticket"));
        subPlan.setOutputTypes(List.of("TicketVO"));
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
}
