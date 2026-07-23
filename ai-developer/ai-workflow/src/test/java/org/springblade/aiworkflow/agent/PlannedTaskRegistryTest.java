package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.enums.TaskType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannedTaskRegistryTest {

    @Test
    void mergesContributionsForTheSamePhysicalFileInsteadOfDroppingTheLaterRequirement() {
        PlannedTaskRegistry registry = new PlannedTaskRegistry();
        AtomicTask first = task(
                "blade-service/blade-demo/src/main/java/org/springblade/demo/service/IDemoService.java",
                "create CRUD service contract");
        AtomicTask contribution = task(
                "blade-service\\blade-demo\\src\\main\\java\\org\\springblade\\demo\\service\\IDemoService.java",
                "add importExcel and exportExcel methods");

        PlannedTaskRegistry.Registration firstResult = registry.claim(3L, first);
        PlannedTaskRegistry.Registration merged = registry.claim(6L, contribution);

        assertTrue(firstResult.accepted());
        assertTrue(firstResult.scheduled());
        assertTrue(merged.accepted());
        assertTrue(merged.merged());
        assertFalse(merged.scheduled());
        assertEquals("PLAN-MERGED-TARGET-PATH", merged.rule());
        assertSame(first, merged.canonicalTask());
        assertTrue(first.getTaskDescription().contains("create CRUD service contract"));
        assertTrue(first.getTaskDescription().contains("add importExcel and exportExcel methods"));
        assertEquals(java.util.List.of(3L, 6L), merged.owner().contributorSubPlanIds());
    }

    @Test
    void rejectsTheSameExpectedFqcnAtAnotherPhysicalPath() {
        PlannedTaskRegistry registry = new PlannedTaskRegistry();
        AtomicTask first = task("blade-service/blade-a/src/main/java/org/springblade/demo/Demo.java", "first");
        AtomicTask duplicate = task("blade-service/blade-b/src/main/java/org/springblade/demo/Demo.java", "second");

        assertTrue(registry.claim(1L, first).accepted());
        PlannedTaskRegistry.Registration result = registry.claim(2L, duplicate);

        assertFalse(result.accepted());
        assertEquals("PLAN-DUPLICATE-TARGET-FQCN", result.rule());
    }

    @Test
    void rejectsIncompatibleTaskKindsClaimingTheSameFile() {
        PlannedTaskRegistry registry = new PlannedTaskRegistry();
        AtomicTask controller = task("blade-service/blade-demo/src/main/java/org/springblade/demo/Demo.java", "controller");
        controller.setType(TaskType.STANDARD_CRUD_CONTROLLER);
        AtomicTask entity = task("blade-service/blade-demo/src/main/java/org/springblade/demo/Demo.java", "entity");
        entity.setType(TaskType.STANDARD_CRUD_ENTITY);

        assertTrue(registry.claim(1L, controller).accepted());
        PlannedTaskRegistry.Registration result = registry.claim(2L, entity);

        assertFalse(result.accepted());
        assertEquals("PLAN-CONFLICTING-TASK-TYPE", result.rule());
    }

    private AtomicTask task(String path, String description) {
        AtomicTask task = new AtomicTask();
        task.setType(TaskType.OTHER);
        task.setTargetPath(path);
        String normalized = path.replace('\\', '/');
        task.setExpectedClassName(normalized.substring(normalized.lastIndexOf('/') + 1).replace(".java", ""));
        task.setModuleName("demo");
        task.setEntityName("Demo");
        task.setTaskDescription(description);
        return task;
    }
}
