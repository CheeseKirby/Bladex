package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.enums.TaskType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanArtifactTaskValidatorTest {

    @Test
    void detectsMissingRequiredAndGeneratedProhibitedArtifacts() {
        PlannedArtifact required = new PlannedArtifact(1L, "RequiredDTO", ArtifactKind.DTO,
                ArtifactAction.CREATE, "demo", ModuleSide.API, null,
                "api/RequiredDTO.java", true, "test");
        PlannedArtifact prohibited = new PlannedArtifact(1L, "DemoExcel", ArtifactKind.EXCEL_MODEL,
                ArtifactAction.PROHIBIT, "demo", ModuleSide.IMPL, null,
                "impl/DemoExcel.java", false, "test");
        AtomicTask forbiddenTask = new AtomicTask();
        forbiddenTask.setType(TaskType.EXCEL_IMPORT_EXPORT);
        forbiddenTask.setTargetPath("impl/DemoExcel.java");

        List<PlanCompilationIssue> issues = new PlanArtifactTaskValidator()
                .validate(List.of(required, prohibited), List.of(forbiddenTask));

        assertTrue(issues.stream().anyMatch(issue -> "PLAN-DELIVERABLE-MISSING".equals(issue.rule())));
        assertTrue(issues.stream().anyMatch(issue -> "PLAN-PROHIBITED-DELIVERABLE".equals(issue.rule())));
    }
}
