package org.springblade.aiworkflow.validation;

import org.springblade.aiworkflow.vo.PlanReceiveRequest;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates cross-sub-plan invariants before any database writes occur. */
@Component
public class PlanRequestValidator {

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
