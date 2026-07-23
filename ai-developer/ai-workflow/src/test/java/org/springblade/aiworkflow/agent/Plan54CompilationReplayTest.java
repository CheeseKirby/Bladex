package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.entity.AiSubPlan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Plan54CompilationReplayTest {

    private final GenerationContext context = new GenerationContext(
            GenerationIdentity.of("specialperiod", "SpecialPeriod", "blade_special_period",
                    "org.springblade.specialperiod"),
            ReferenceFrameworkProfile.defaults());

    @Test
    void productionPlan54CompilesToAClosedMergedFileInventory() throws Exception {
        Map<Long, String> titles = new LinkedHashMap<>();
        titles.put(209L, "\u6570\u636e\u5e93 DDL \u5efa\u8868");
        titles.put(210L, "Entity \u5b9e\u4f53\u4e0e VO \u89c6\u56fe\u5bf9\u8c61\u5b9a\u4e49");
        titles.put(211L, "Mapper \u4e0e Service \u6570\u636e\u8bbf\u95ee\u4e0e\u4e1a\u52a1\u670d\u52a1");
        titles.put(212L, "Wrapper \u4e0e Controller \u8f6c\u6362\u4e0e REST \u7aef\u70b9");
        titles.put(213L, "Excel \u5bfc\u5165\u5bfc\u51fa\u5de5\u5177\u7c7b\u5b9e\u73b0");

        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, null, null, null, null, null, null, null, null, null,
                1, false, null, null, null, null, null, null);
        PlannedTaskRegistry registry = new PlannedTaskRegistry();
        List<PlannedArtifact> artifacts = new ArrayList<>();
        List<PlanCompilationIssue> issues = new ArrayList<>();
        List<AtomicTask> canonicalTasks = new ArrayList<>();

        for (Map.Entry<Long, String> entry : titles.entrySet()) {
            AiSubPlan subPlan = new AiSubPlan();
            subPlan.setId(entry.getKey());
            subPlan.setTitle(entry.getValue());
            subPlan.setPlanContent(resource("replays/plan54/subplan-" + entry.getKey() + ".md"));
            BladeXCodeAgent.SubPlanTaskCompilation compilation = agent.parseAtomicTasks(subPlan, context);
            artifacts.addAll(compilation.artifacts());
            issues.addAll(compilation.issues());
            for (AtomicTask task : compilation.tasks()) {
                task.setSourceSubPlanId(entry.getKey());
                PlannedTaskRegistry.Registration registration = registry.claim(entry.getKey(), task);
                if (!registration.accepted()) {
                    issues.add(PlanCompilationIssue.error(entry.getKey(), registration.rule(),
                            task.getTargetPath(), "replay task conflict"));
                } else if (registration.scheduled()) {
                    canonicalTasks.add(registration.canonicalTask());
                }
            }
        }

        issues.addAll(new PlanArtifactTaskValidator().validate(artifacts, canonicalTasks));
        PlanPreflightGate.Result preflight = PlanPreflightGate.evaluate(issues);

        assertFalse(preflight.blocking(), () -> "plan 54 preflight errors: " + preflight.errors());
        assertEquals(16, canonicalTasks.size(), () -> canonicalTasks.stream()
                .map(AtomicTask::getTargetPath).sorted().toList().toString());
        assertFalse(canonicalTasks.stream().anyMatch(task -> task.getTargetPath().contains("ai-generated/subplan-")));
        assertTrue(canonicalTasks.stream().anyMatch(task -> task.getTargetPath().endsWith("SpecialPeriodStatController.java")));
        assertTrue(canonicalTasks.stream().anyMatch(task -> task.getTargetPath().endsWith("HotworkMatchDTO.java")));
        AtomicTask service = canonicalTasks.stream()
                .filter(task -> task.getTargetPath().endsWith("/service/ISpecialPeriodService.java"))
                .findFirst().orElseThrow();
        assertTrue(service.getTaskDescription().contains("importSpecialPeriod"));
        assertTrue(service.getTaskDescription().contains("exportSpecialPeriod"));
        assertTrue(service.getTaskDescription().contains("ADDITIONAL REVIEWED CONTRIBUTION FROM SUB-PLAN 213"));
    }

    private String resource(String path) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing replay resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
