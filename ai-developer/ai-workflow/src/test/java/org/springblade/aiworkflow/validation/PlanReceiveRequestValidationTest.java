package org.springblade.aiworkflow.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanReceiveRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void masterPlanAndSubPlansAreRequired() {
        PlanReceiveRequest request = new PlanReceiveRequest();
        request.setProjectId("project");
        request.setProjectName("Project");

        var violations = validator.validate(request);

        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("masterPlan")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("subPlans")));
    }

    @Test
    void validMinimalRequestPassesBeanValidation() {
        PlanReceiveRequest request = validRequest();
        assertFalse(validator.validate(request).iterator().hasNext());
    }

    static PlanReceiveRequest validRequest() {
        PlanReceiveRequest request = new PlanReceiveRequest();
        request.setProjectId("project");
        request.setProjectName("Project");
        PlanReceiveRequest.MetadataVO metadata = new PlanReceiveRequest.MetadataVO();
        metadata.setSourceService("legacy-replay");
        request.setMetadata(metadata);
        PlanReceiveRequest.MasterPlanVO master = new PlanReceiveRequest.MasterPlanVO();
        master.setId("master-1");
        master.setVersion(1);
        master.setContent("plan content");
        request.setMasterPlan(master);
        PlanReceiveRequest.SubPlanVO sub = new PlanReceiveRequest.SubPlanVO();
        sub.setId("sub-1");
        sub.setIndex(1);
        sub.setTitle("Entity");
        sub.setContent("sub-plan content");
        request.setSubPlans(List.of(sub));
        return request;
    }
}
