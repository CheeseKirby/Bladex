package org.springblade.aiworkflow.agent;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.config.AiWorkflowProperties;
import org.springblade.aiworkflow.entity.AiGeneratedFile;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.enums.TaskType;
import org.springblade.aiworkflow.mapper.AiGeneratedFileMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, AiGeneratedFile> existingByPath = indexFirstRowByPath(
                generatedFileMapper.selectByPlanId(plan.getId()));
        for (GeneratedFile file : files) {
            try {
                AiGeneratedFile row = toEntity(subPlan, plan, file, action);
                String path = normalize(file.getFilePath());
                AiGeneratedFile existing = existingByPath.get(path);
                if (existing == null) {
                    generatedFileMapper.insert(row);
                    existingByPath.put(path, row);
                } else {
                    row.setId(existing.getId());
                    row.setSubPlanId(existing.getSubPlanId());
                    row.setCreateTime(existing.getCreateTime());
                    generatedFileMapper.updateById(row);
                    log.info("Generated-file snapshot updated instead of duplicated: planId={}, ownerSubPlanId={}, path={}",
                            plan.getId(), existing.getSubPlanId(), file.getFilePath());
                }
            } catch (Exception e) {
                log.warn("Generated-file persistence failed: subPlanId={}, path={}",
                        subPlan.getId(), file.getFilePath(), e);
            }
        }
    }

    public List<GeneratedFile> loadPlanFiles(AiPlan plan) {
        Map<String, AiGeneratedFile> rowsByPath = indexFirstRowByPath(
                generatedFileMapper.selectByPlanId(plan.getId()));
        if (rowsByPath.isEmpty()) return new ArrayList<>();
        List<GeneratedFile> files = new ArrayList<>();
        for (AiGeneratedFile row : rowsByPath.values()) {
            if (row.getContent() == null) continue;
            files.add(new GeneratedFile(resolveTaskType(row), row.getFilePath(), row.getContent(), row.getAction()));
        }
        return files;
    }

    private Map<String, AiGeneratedFile> indexFirstRowByPath(List<AiGeneratedFile> rows) {
        Map<String, AiGeneratedFile> result = new LinkedHashMap<>();
        if (rows == null) return result;
        for (AiGeneratedFile row : rows) {
            String path = normalize(row.getFilePath());
            if (path == null || path.isBlank()) continue;
            AiGeneratedFile previous = result.putIfAbsent(path, row);
            if (previous != null) {
                log.warn("Duplicate generated-file rows detected; keeping the first owner snapshot: path={}, keptId={}, ignoredId={}",
                        path, previous.getId(), row.getId());
            }
        }
        return result;
    }

    private TaskType resolveTaskType(AiGeneratedFile row) {
        String fileType = row.getFileType();
        if (fileType == null || fileType.isBlank()) {
            log.warn("Generated file row has no task type; falling back to OTHER: id={}, path={}",
                    row.getId(), row.getFilePath());
            return TaskType.OTHER;
        }
        try {
            return TaskType.fromCode(fileType);
        } catch (IllegalArgumentException ex) {
            log.warn("Generated file row has unknown task type {}; falling back to OTHER: id={}, path={}",
                    fileType, row.getId(), row.getFilePath());
            return TaskType.OTHER;
        }
    }

    /** Writes a repaired file and updates its database snapshot only when the disk write succeeds. */
    public boolean persistRepair(AiPlan plan, GeneratedFile file) {
        String filePath = file.getFilePath();
        String content = file.getContent();
        try {
            String writeRoot = plan.getOutputDirectory() == null || plan.getOutputDirectory().isBlank()
                    ? properties.getOutputRoot() : plan.getOutputDirectory();

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

    private String normalize(String path) {
        return path == null ? null : path.replace('\\', '/');
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
