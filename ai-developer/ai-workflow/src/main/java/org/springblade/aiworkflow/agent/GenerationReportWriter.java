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
        write(plan, files, expected, tasks, issues, List.of(), objectMapper);
    }

    public static void write(AiPlan plan, List<GeneratedFile> files, List<ExpectedDeliverable> expected,
                             List<AtomicTask> tasks, List<GeneratedProjectValidator.Issue> issues,
                             List<CrossFileValidator.ContractIssue> contractIssues, ObjectMapper objectMapper) {
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
            compile.put("reason", compileReason(plan.getCompileVerificationStatus()));
            CanonicalDomainContract domainContract = tasks.stream()
                    .map(AtomicTask::getGenerationContext).filter(java.util.Objects::nonNull)
                    .map(GenerationContext::domainContract).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            Map<String, Object> manifestReport = new LinkedHashMap<>();
            manifestReport.put("receptionId", plan.getReceptionId());
            manifestReport.put("outputDirectory", root.toString());
            manifestReport.put("files", manifest);
            manifestReport.put("expectedDeliverables", expected);
            manifestReport.put("domainContract", domainContract);
            writeJson(root.resolve("manifest.json"), manifestReport, objectMapper);
            if (domainContract != null) writeJson(root.resolve("domain-contract.json"), domainContract, objectMapper);
            writeJson(root.resolve("reference-report.json"), references, objectMapper);
            writeJson(root.resolve("validation-report.json"), mergeIssues(issues, contractIssues), objectMapper);
            writeJson(root.resolve("compile-report.json"), compile, objectMapper);
        } catch (Exception e) {
            log.warn("Failed to write generation reports for receptionId={}: {}",
                    plan.getReceptionId(), e.getMessage());
        }
    }

    private static List<GeneratedProjectValidator.Issue> mergeIssues(
            List<GeneratedProjectValidator.Issue> projectIssues,
            List<CrossFileValidator.ContractIssue> contractIssues) {
        Map<String, GeneratedProjectValidator.Issue> merged = new LinkedHashMap<>();
        for (GeneratedProjectValidator.Issue issue : projectIssues == null
                ? List.<GeneratedProjectValidator.Issue>of() : projectIssues) {
            merged.putIfAbsent(issue.rule() + "|" + issue.filePath() + "|" + issue.message(), issue);
        }
        for (CrossFileValidator.ContractIssue issue : contractIssues == null
                ? List.<CrossFileValidator.ContractIssue>of() : contractIssues) {
            GeneratedProjectValidator.Issue converted = new GeneratedProjectValidator.Issue(
                    issue.severity, issue.rule, issue.sourceFilePath, issue.message);
            merged.putIfAbsent(converted.rule() + "|" + converted.filePath() + "|" + converted.message(), converted);
        }
        return List.copyOf(merged.values());
    }

    private static String compileReason(String status) {
        if ("PASSED_SOURCE_GATE_DEPENDENCIES_UNVERIFIED".equals(status)
                || "SKIPPED_DEPENDENCIES_UNAVAILABLE".equals(status)) {
            return "Generated sources passed the dependency-independent gate, but private reference-project dependencies were not compiled";
        }
        if ("FAILED_SOURCE_GATE".equals(status)) {
            return "Generated sources failed syntax, generated-type closure, XML safety, or physical source-layout validation";
        }
        if ("NOT_RUN_PLAN_COMPILATION_FAILED".equals(status)) {
            return "Generation was blocked by plan compilation preflight errors";
        }
        return null;
    }

    private static void writeJson(Path path, Object value, ObjectMapper objectMapper) throws Exception {
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                StandardCharsets.UTF_8);
    }
}
