package org.springblade.aiworkflow.agent;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.config.AiWorkflowProperties;
import org.springblade.aiworkflow.entity.AiGeneratedFile;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.enums.WriteTarget;
import org.springblade.aiworkflow.mapper.AiGeneratedFileMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Owns generated-file persistence and repair write-back metadata. */
@Slf4j
@Component
public class GeneratedFileStore {

    private final AiGeneratedFileMapper generatedFileMapper;
    private final FileWriteExecutor fileWriteExecutor;
    private final AiWorkflowProperties properties;

    public GeneratedFileStore(AiGeneratedFileMapper generatedFileMapper,
                              FileWriteExecutor fileWriteExecutor,
                              AiWorkflowProperties properties) {
        this.generatedFileMapper = generatedFileMapper;
        this.fileWriteExecutor = fileWriteExecutor;
        this.properties = properties;
    }

    /** Saves generated output for UI inspection. Individual row failures do not abort the workflow. */
    public void saveBatch(AiSubPlan subPlan, AiPlan plan, List<GeneratedFile> files, String action) {
        if (files == null || files.isEmpty()) return;
        for (GeneratedFile file : files) {
            try {
                generatedFileMapper.insert(toEntity(subPlan, plan, file, action));
            } catch (Exception e) {
                log.warn("Generated-file persistence failed: subPlanId={}, path={}",
                        subPlan.getId(), file.getFilePath(), e);
            }
        }
    }

    public List<GeneratedFile> loadPlanFiles(AiPlan plan) {
        List<AiGeneratedFile> rows = generatedFileMapper.selectByPlanId(plan.getId());
        if (rows == null || rows.isEmpty()) return new ArrayList<>();
        List<GeneratedFile> files = new ArrayList<>();
        for (AiGeneratedFile row : rows) {
            if (row.getFilePath() == null || row.getContent() == null) continue;
            files.add(new GeneratedFile(null, row.getFilePath(), row.getContent(), row.getAction()));
        }
        return files;
    }

    /** Writes a repaired file and updates its database snapshot only when the disk write succeeds. */
    public boolean persistRepair(AiPlan plan, GeneratedFile file) {
        String filePath = file.getFilePath();
        String content = file.getContent();
        try {
            WriteTarget writeTarget = WriteTarget.parse(plan.getWriteTarget());
            String isolatedRoot = plan.getOutputDirectory() == null || plan.getOutputDirectory().isBlank()
                    ? properties.getOutputRoot() : plan.getOutputDirectory();
            String writeRoot = writeTarget.isReal() ? properties.getTargetProjectRoot() : isolatedRoot;
            boolean rootAvailable = writeTarget.isReal()
                    ? fileWriteExecutor.isRootAvailable(writeRoot)
                    : (plan.getOutputDirectory() != null && !plan.getOutputDirectory().isBlank())
                        || fileWriteExecutor.isTargetRootAvailable();
            if (!rootAvailable) {
                log.warn("Repair write skipped because root is unavailable: path={}, target={}",
                        filePath, plan.getWriteTarget());
                return false;
            }

            WriteResult result = fileWriteExecutor.write(
                    List.of(new FileWriteTask(filePath, content, "MODIFY")), writeRoot);
            if (!result.isSuccess()) {
                log.warn("Repair write failed; database snapshot remains unchanged: path={}, error={}",
                        filePath, result.getErrorMessage());
                return false;
            }
            updateContent(plan.getId(), filePath, content);
            return true;
        } catch (Exception e) {
            log.warn("Repair write failed; database snapshot remains unchanged: path={}, error={}",
                    filePath, e.getMessage());
            return false;
        }
    }

    private AiGeneratedFile toEntity(AiSubPlan subPlan, AiPlan plan, GeneratedFile file, String action) {
        AiGeneratedFile row = new AiGeneratedFile();
        row.setPlanId(plan.getId());
        row.setSubPlanId(subPlan.getId());
        row.setFileType(file.getType() != null ? file.getType().name() : "OTHER");
        row.setFilePath(file.getFilePath());
        row.setFileName(extractFileName(file.getFilePath()));
        row.setFileExtension(extractExtension(file.getFilePath()));
        row.setAction(action);
        String content = file.getContent() == null ? "" : file.getContent();
        row.setContent(content);
        row.setSizeBytes(content.getBytes(StandardCharsets.UTF_8).length);
        row.setLineCount((int) content.lines().count());
        row.setCreateTime(LocalDateTime.now());
        row.setIsDeleted(0);
        return row;
    }

    private void updateContent(Long planId, String filePath, String content) {
        int sizeBytes = content.getBytes(StandardCharsets.UTF_8).length;
        int lineCount = (int) content.lines().count();
        UpdateWrapper<AiGeneratedFile> update = new UpdateWrapper<>();
        update.eq("plan_id", planId)
                .eq("file_path", filePath)
                .set("content", content)
                .set("size_bytes", sizeBytes)
                .set("line_count", lineCount)
                .set("action", "MODIFY");
        generatedFileMapper.update(null, update);
    }

    private String extractFileName(String path) {
        if (path == null) return null;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private String extractExtension(String path) {
        String name = extractFileName(path);
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }
}
