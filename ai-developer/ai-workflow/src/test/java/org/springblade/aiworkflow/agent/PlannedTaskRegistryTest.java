package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.enums.TaskType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannedTaskRegistryTest {

    @Test
    void rejectsAPathAlreadyOwnedByAnEarlierSubPlan() {
        PlannedTaskRegistry registry = new PlannedTaskRegistry();
        AtomicTask first = task("blade-service/blade-demo/src/main/java/org/springblade/demo/mapper/DemoMapper.java");
        AtomicTask duplicate = task("blade-service\\blade-demo\\src\\main\\java\\org\\springblade\\demo\\mapper\\DemoMapper.java");

        assertTrue(registry.claim(3L, first).accepted());
        PlannedTaskRegistry.Registration result = registry.claim(6L, duplicate);

        assertFalse(result.accepted());
        assertEquals("PLAN-DUPLICATE-TARGET-PATH", result.rule());
        assertEquals(3L, result.owner().subPlanId());
    }

    @Test
    void rejectsTheSameExpectedFqcnAtAnotherPhysicalPath() {
        PlannedTaskRegistry registry = new PlannedTaskRegistry();
        AtomicTask first = task("blade-service/blade-a/src/main/java/org/springblade/demo/Demo.java");
        AtomicTask duplicate = task("blade-service/blade-b/src/main/java/org/springblade/demo/Demo.java");

        assertTrue(registry.claim(1L, first).accepted());
        PlannedTaskRegistry.Registration result = registry.claim(2L, duplicate);

        assertFalse(result.accepted());
        assertEquals("PLAN-DUPLICATE-TARGET-FQCN", result.rule());
    }

    private AtomicTask task(String path) {
        AtomicTask task = new AtomicTask();
        task.setType(TaskType.OTHER);
        task.setTargetPath(path);
        return task;
    }
}
