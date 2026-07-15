package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springblade.aiworkflow.config.AiWorkflowProperties;
import org.springblade.aiworkflow.entity.AiGeneratedFile;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.mapper.AiGeneratedFileMapper;
import org.springblade.aiworkflow.enums.TaskType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class GeneratedFileStoreTest {

    private AiGeneratedFileMapper mapper;
    private FileWriteExecutor fileWriteExecutor;
    private AiWorkflowProperties properties;
    private GeneratedFileStore store;

    @BeforeEach
    void setUp() {
        mapper = mock(AiGeneratedFileMapper.class);
        fileWriteExecutor = mock(FileWriteExecutor.class);
        properties = new AiWorkflowProperties();
        properties.setOutputRoot("output-root");
        properties.setTargetProjectRoot("target-root");
        store = new GeneratedFileStore(mapper, fileWriteExecutor, properties);
    }

    @Test
    void batchPersistenceCalculatesFileMetadata() {
        AiPlan plan = plan();
        AiSubPlan subPlan = new AiSubPlan();
        subPlan.setId(20L);
        GeneratedFile file = new GeneratedFile(TaskType.OTHER, "dir/Test.java", "line1\nline2", "CREATED");

        store.saveBatch(subPlan, plan, List.of(file), "CREATED");

        ArgumentCaptor<AiGeneratedFile> captor = ArgumentCaptor.forClass(AiGeneratedFile.class);
        verify(mapper).insert(captor.capture());
        AiGeneratedFile row = captor.getValue();
        assertEquals("Test.java", row.getFileName());
        assertEquals("java", row.getFileExtension());
        assertEquals(2, row.getLineCount());
        assertEquals(11, row.getSizeBytes());
    }

    @Test
    void repairUpdatesDatabaseOnlyAfterSuccessfulDiskWrite() {
        AiPlan plan = plan();
        GeneratedFile file = new GeneratedFile(TaskType.OTHER, "dir/Test.java", "fixed", "MODIFY");
        when(fileWriteExecutor.isTargetRootAvailable()).thenReturn(true);
        when(fileWriteExecutor.write(anyList(), eq("output-root")))
                .thenReturn(WriteResult.success(List.of("dir/Test.java")));

        assertTrue(store.persistRepair(plan, file));
        verify(mapper).update(eq(null), any());

        when(fileWriteExecutor.write(anyList(), eq("output-root")))
                .thenReturn(WriteResult.failure("disk failure"));
        assertFalse(store.persistRepair(plan, file));
        verify(mapper, times(1)).update(eq(null), any());
    }

    @Test
    void unavailableRootDoesNotTouchDatabase() {
        when(fileWriteExecutor.isTargetRootAvailable()).thenReturn(false);
        assertFalse(store.persistRepair(plan(),
                new GeneratedFile(TaskType.OTHER, "dir/Test.java", "fixed", "MODIFY")));
        verify(mapper, never()).update(any(), any());
    }

    private AiPlan plan() {
        AiPlan plan = new AiPlan();
        plan.setId(10L);
        plan.setWriteTarget("ISOLATED");
        return plan;
    }
}
