package org.springblade.aiworkflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.enums.WriteTarget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes per-reception manifest and quality reports beside isolated generated output. */
@Slf4j
public final class GenerationReportWriter {

    private GenerationReportWriter() {
    }

    public static void write(AiPlan plan, List<GeneratedFile> files, List<ExpectedDeliverable> expected,
                             List<AtomicTask> tasks, List<GeneratedProjectValidator.Issue> issues,
                             ObjectMapper objectMapper) {
        if (plan == null || WriteTarget.parse(plan.getWriteTarget()).isReal()
                || plan.getOutputDirectory() == null || plan.getOutputDirectory().isBlank()) return;
        try {
            Path root = Path.of(plan.getOutputDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            List<Map<String, Object>> manifest = files.stream().map(file -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", file.getFilePath());
                row.put("type", file.getType() == null ? "OTHER" : file.getType().name());
                row.put("action", file.getAction());
                row.put("sizeBytes", file.getContent() == null ? 0 : file.getContent().getBytes(StandardCharsets.UTF_8).length);
                return row;
            }).toList();
            List<Map<String, Object>> references = tasks.stream()
                    .filter(task -> task.getSelectedReferenceClass() != null)
                    .map(task -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("targetPath", task.getTargetPath());
                        row.put("referenceClass", task.getSelectedReferenceClass());
                        row.put("referenceModule", task.getSelectedReferenceModule());
                        row.put("referencePath", task.getSelectedReferencePath());
                        row.put("score", task.getReferenceScore());
                        row.put("reason", task.getReferenceReason());
                        return row;
                    }).toList();
            Map<String, Object> compile = new LinkedHashMap<>();
            compile.put("status", plan.getCompileVerificationStatus());
            compile.put("reason", "SKIPPED_DEPENDENCIES_UNAVAILABLE".equals(plan.getCompileVerificationStatus())
                    ? "Private reference-project dependencies are unavailable in the current environment" : null);
            writeJson(root.resolve("manifest.json"), Map.of("receptionId", plan.getReceptionId(),
                    "outputDirectory", root.toString(), "files", manifest, "expectedDeliverables", expected), objectMapper);
            writeJson(root.resolve("reference-report.json"), references, objectMapper);
            writeJson(root.resolve("validation-report.json"), issues, objectMapper);
            writeJson(root.resolve("compile-report.json"), compile, objectMapper);
        } catch (Exception e) {
            log.warn("Failed to write generation reports for receptionId={}: {}",
                    plan.getReceptionId(), e.getMessage());
        }
    }

    private static void writeJson(Path path, Object value, ObjectMapper objectMapper) throws Exception {
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                StandardCharsets.UTF_8);
    }
}
