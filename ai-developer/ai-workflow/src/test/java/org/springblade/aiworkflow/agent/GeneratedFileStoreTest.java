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
    void batchPersistenceUpdatesTheExistingOwnerSnapshotInsteadOfInsertingADuplicate() {
        AiGeneratedFile existing = new AiGeneratedFile();
        existing.setId(31L);
        existing.setPlanId(10L);
        existing.setSubPlanId(20L);
        existing.setFileType(TaskType.CUSTOM_MAPPER.getCode());
        existing.setFilePath("dir/TestMapper.java");
        existing.setContent("old");
        when(mapper.selectByPlanId(10L)).thenReturn(List.of(existing));

        AiSubPlan laterSubPlan = new AiSubPlan();
        laterSubPlan.setId(60L);
        store.saveBatch(laterSubPlan, plan(), List.of(
                new GeneratedFile(TaskType.CUSTOM_MAPPER, "dir\\TestMapper.java", "new", "CREATE")), "CREATED");

        ArgumentCaptor<AiGeneratedFile> captor = ArgumentCaptor.forClass(AiGeneratedFile.class);
        verify(mapper).updateById(captor.capture());
        verify(mapper, never()).insert(any());
        assertEquals(31L, captor.getValue().getId());
        assertEquals(20L, captor.getValue().getSubPlanId());
        assertEquals("new", captor.getValue().getContent());
    }

    @Test
    void loadPlanFilesCollapsesHistoricalDuplicateRowsToTheFirstOwnerSnapshot() {
        AiGeneratedFile first = generatedRow(31L, "dir/TestMapper.java", "first");
        AiGeneratedFile duplicate = generatedRow(32L, "dir\\TestMapper.java", "second");
        when(mapper.selectByPlanId(10L)).thenReturn(List.of(first, duplicate));

        List<GeneratedFile> files = store.loadPlanFiles(plan());

        assertEquals(1, files.size());
        assertEquals("first", files.get(0).getContent());
    }

    @Test
    void loadPlanFilesRestoresPersistedTaskType() {
        AiGeneratedFile row = new AiGeneratedFile();
        row.setId(30L);
        row.setFileType(TaskType.STANDARD_CRUD_CONTROLLER.getCode());
        row.setFilePath("src/TestController.java");
        row.setContent("class TestController {}");
        row.setAction("CREATE");
        when(mapper.selectByPlanId(10L)).thenReturn(List.of(row));

        List<GeneratedFile> files = store.loadPlanFiles(plan());

        assertEquals(1, files.size());
        assertEquals(TaskType.STANDARD_CRUD_CONTROLLER, files.get(0).getType());
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

    private AiGeneratedFile generatedRow(Long id, String path, String content) {
        AiGeneratedFile row = new AiGeneratedFile();
        row.setId(id);
        row.setPlanId(10L);
        row.setSubPlanId(20L);
        row.setFileType(TaskType.CUSTOM_MAPPER.getCode());
        row.setFilePath(path);
        row.setContent(content);
        row.setAction("CREATE");
        return row;
    }

    private AiPlan plan() {
        AiPlan plan = new AiPlan();
        plan.setId(10L);
        plan.setWriteTarget("ISOLATED");
        return plan;
    }
}
