package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanPreflightGateTest {

    @Test
    void blocksGenerationWhenAnyCompilationErrorExists() {
        PlanPreflightGate.Result result = PlanPreflightGate.evaluate(List.of(
                PlanCompilationIssue.error(211L, "PLAN-DELIVERABLE-MISSING", "src/Missing.java", "missing"),
                new PlanCompilationIssue(212L, "WARN", "PLAN-ADVISORY", null, "advisory")));

        assertTrue(result.blocking());
        assertEquals(1, result.errors().size());
        assertEquals(List.of(211L), result.affectedSubPlanIds());
    }

    @Test
    void warningsDoNotBlockGeneration() {
        PlanPreflightGate.Result result = PlanPreflightGate.evaluate(List.of(
                new PlanCompilationIssue(212L, "WARN", "PLAN-ADVISORY", null, "advisory")));

        assertFalse(result.blocking());
        assertTrue(result.errors().isEmpty());
    }
}
