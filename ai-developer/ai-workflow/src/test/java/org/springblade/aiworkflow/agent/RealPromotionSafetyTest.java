package org.springblade.aiworkflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springblade.aiworkflow.config.AiWorkflowProperties;
import org.springblade.aiworkflow.entity.AiGeneratedFile;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.enums.TaskType;
import org.springblade.aiworkflow.mapper.AiExecutionLogMapper;
import org.springblade.aiworkflow.mapper.AiGeneratedFileMapper;
import org.springblade.aiworkflow.mapper.AiPlanMapper;
import org.springblade.aiworkflow.mapper.AiSubPlanMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RealPromotionSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void compileFailureRestoresEveryRealProjectFile() throws Exception {
        Path targetRoot = Files.createDirectories(tempDir.resolve("target-project"));
        String relativePath = "blade-service/blade-demo/src/main/java/org/springblade/demo/Demo.java";
        Path targetFile = targetRoot.resolve(relativePath);
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "old");

        BuildVerifier verifier = mock(BuildVerifier.class);
        when(verifier.verify(anyList())).thenReturn(BuildResult.failure(List.of(
                new BuildResult.BuildError(relativePath, 1, "compile failed"))));
        BladeXCodeAgent agent = agent(targetRoot, verifier, relativePath);
        AiPlan plan = plan();

        boolean passed = agent.promoteAndCompileRealPlan(plan,
                List.of(new GeneratedFile(TaskType.STANDARD_CRUD_ENTITY, relativePath, "new", "MODIFY")));

        assertFalse(passed);
        assertEquals("old", Files.readString(targetFile));
        assertEquals("FAILED_COMPILE_ROLLED_BACK", plan.getCompileVerificationStatus());
    }

    @Test
    void compileSuccessCommitsTheReversiblePromotion() throws Exception {
        Path targetRoot = Files.createDirectories(tempDir.resolve("target-project-success"));
        String relativePath = "blade-service/blade-demo/src/main/java/org/springblade/demo/Demo.java";
        BuildVerifier verifier = mock(BuildVerifier.class);
        when(verifier.verify(anyList())).thenReturn(BuildResult.success());
        BladeXCodeAgent agent = agent(targetRoot, verifier, relativePath);
        AiPlan plan = plan();

        boolean passed = agent.promoteAndCompileRealPlan(plan,
                List.of(new GeneratedFile(TaskType.STANDARD_CRUD_ENTITY, relativePath, "new", "CREATE")));

        assertTrue(passed);
        assertEquals("new", Files.readString(targetRoot.resolve(relativePath)));
        assertEquals("PASSED", plan.getCompileVerificationStatus());
        assertEquals(targetRoot.toString(), plan.getOutputDirectory());
    }

    private BladeXCodeAgent agent(Path targetRoot, BuildVerifier verifier, String relativePath) {
        AiWorkflowProperties properties = new AiWorkflowProperties();
        properties.setTargetProjectRoot(targetRoot.toString());
        properties.setOutputRoot(tempDir.resolve("staging").toString());
        AiGeneratedFileMapper generatedFileMapper = mock(AiGeneratedFileMapper.class);
        AiGeneratedFile row = new AiGeneratedFile();
        row.setFilePath(relativePath);
        when(generatedFileMapper.selectByPlanId(9L)).thenReturn(List.of(row));
        return new BladeXCodeAgent(
                mock(AiPlanMapper.class), mock(AiSubPlanMapper.class), mock(AiExecutionLogMapper.class),
                generatedFileMapper, null, new ConventionValidator(), null,
                new FileWriteExecutor(properties.getOutputRoot()), verifier, new ObjectMapper(),
                1, false, properties, null, null, mock(TopologySorter.class),
                mock(GeneratedFileStore.class), mock(org.springblade.aiworkflow.notification.WorkflowStatusNotifier.class));
    }

    private AiPlan plan() {
        AiPlan plan = new AiPlan();
        plan.setId(9L);
        plan.setReceptionId("rec-real");
        plan.setWriteTarget("REAL");
        plan.setOutputDirectory(tempDir.resolve("staging/rec-real").toString());
        return plan;
    }
}
