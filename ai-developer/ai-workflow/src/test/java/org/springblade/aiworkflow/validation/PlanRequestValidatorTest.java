package org.springblade.aiworkflow.validation;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlanRequestValidatorTest {

    private final PlanRequestValidator validator = new PlanRequestValidator("", true);

    @Test
    void validDependencyGraphPasses() {
        PlanReceiveRequest request = PlanReceiveRequestValidationTest.validRequest();
        PlanReceiveRequest.SubPlanVO second = subPlan("sub-2", 2, List.of("sub-1"));
        request.setSubPlans(List.of(request.getSubPlans().get(0), second));
        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    void duplicateIdsAndUnknownDependenciesAreRejected() {
        PlanReceiveRequest request = PlanReceiveRequestValidationTest.validRequest();
        request.setSubPlans(List.of(request.getSubPlans().get(0), subPlan("sub-1", 2, null)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(request));

        request.setSubPlans(List.of(request.getSubPlans().get(0), subPlan("sub-2", 2, List.of("missing"))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(request));
    }

    @Test
    void cyclicDependenciesAreRejectedBeforePersistence() {
        PlanReceiveRequest request = PlanReceiveRequestValidationTest.validRequest();
        PlanReceiveRequest.SubPlanVO first = request.getSubPlans().get(0);
        first.setPrerequisites(List.of("sub-2"));
        request.setSubPlans(List.of(first, subPlan("sub-2", 2, List.of("sub-1"))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(request));
    }


    @Test
    void missingPlanStructureIsRejectedForDirectServiceCalls() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(null));
        PlanReceiveRequest request = PlanReceiveRequestValidationTest.validRequest();
        request.setSubPlans(null);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(request));
    }

    private PlanReceiveRequest.SubPlanVO subPlan(String id, int index, List<String> prerequisites) {
        PlanReceiveRequest.SubPlanVO sub = new PlanReceiveRequest.SubPlanVO();
        sub.setId(id);
        sub.setIndex(index);
        sub.setTitle("Task " + index);
        sub.setContent("content");
        sub.setPrerequisites(prerequisites);
        return sub;
    }
}
