package org.springblade.aiworkflow.agent;

import org.springblade.aiworkflow.enums.TaskType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Closes the project-level quality loop after all sub-plans have generated their files.
 *
 * <p>Deterministic repairs run first for changes that do not require model judgment (currently generated-type
 * import paths). Remaining source-local quality errors are grouped by file and repaired once with the complete
 * relevant project contract, then the strict project validator is run again. A repair is accepted only when it
 * still passes the layer-one convention validator and the caller confirms that disk/database persistence
 * succeeded.</p>
 */
public final class ProjectQualityRepairer {

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Z][A-Za-z0-9_]*)");
    private static final Pattern IMPORT = Pattern.compile("(?m)^(\\s*import\\s+)(org\\.springblade\\.[\\w.]+)(\\s*;\\s*)$");

    private static final Set<String> LLM_REPAIRABLE_RULES = Set.of(
            "MAPPER-RESULT-PROPERTY-MISSING",
            "MAPPER-DDL-COLUMN-MISSING",
            "CONTROLLER-SERVICE-BUSINESS-GAP",
            "REFERENCE-API-METHOD-MISSING",
            "CROSS-CONTROLLER-SERVICE-MISMATCH",
            "CROSS-SERVICE-IMPL-IFACE-MISMATCH",
            "MAPPER-RESULTMAP-TYPE-MISMATCH",
            "CROSS-MAPPER-XML-NAMESPACE",
            "CROSS-WRAPPER-ENTITY-IVO",
            "CROSS-WRAPPER-ENTITY-UVO",
            "CROSS-FEIGN-FALLBACK-MISSING",
            "CROSS-IMPORT-CLOSURE-MISSING"
    );

    private final GeneratedProjectValidator validator;
    private final CrossFileValidator crossFileValidator;
    private final ConventionValidator conventionValidator;

    public ProjectQualityRepairer(GeneratedProjectValidator validator, CrossFileValidator crossFileValidator,
                                  ConventionValidator conventionValidator) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.crossFileValidator = Objects.requireNonNull(crossFileValidator, "crossFileValidator");
        this.conventionValidator = Objects.requireNonNull(conventionValidator, "conventionValidator");
    }

    public RepairResult repair(List<GeneratedFile> sourceFiles,
                               List<ExpectedDeliverable> expectedDeliverables,
                               GenerationContext context,
                               ReferenceProjectIndex referenceIndex,
                               Map<String, AtomicTask> tasksByPath,
                               int maxAttempts,
                               ProjectFileFixer fixer,
                               RepairPersister persister) {
        List<GeneratedFile> files = copyFiles(sourceFiles);
        Map<String, AtomicTask> normalizedTasks = new LinkedHashMap<>();
        if (tasksByPath != null) {
            tasksByPath.forEach((path, task) -> normalizedTasks.put(normalize(path), task));
        }
        List<RepairEvent> events = new ArrayList<>();
        List<GeneratedProjectValidator.Issue> issues = validate(files, expectedDeliverables, context, referenceIndex);
        List<RepairProblem> problems = collectProblems(files, issues);
        int attempts = 0;

        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            attempts = attempt;
            boolean progressed = false;

            // Generated imports are a deterministic project contract. Normalize every Java file even when the
            // reference project is unavailable and the strict import resolver cannot classify the issue yet.
            for (GeneratedFile snapshot : new ArrayList<>(files)) {
                GeneratedFile repaired = repairGeneratedImports(snapshot, files);
                if (repaired == null || Objects.equals(snapshot.getContent(), repaired.getContent())) continue;
                String path = normalize(snapshot.getFilePath());
                if (!passesConvention(repaired)) {
                    events.add(RepairEvent.failed(attempt, path, "DETERMINISTIC_IMPORT",
                            "Deterministic import rewrite was rejected by layer-one validation"));
                    continue;
                }
                if (!persister.persist(repaired)) {
                    events.add(RepairEvent.failed(attempt, path, "DETERMINISTIC_IMPORT",
                            "Deterministic import rewrite could not be persisted"));
                    continue;
                }
                replace(files, path, repaired);
                events.add(RepairEvent.success(attempt, path, "DETERMINISTIC_IMPORT",
                        "Aligned imports with generated project FQCNs"));
                progressed = true;
            }

            issues = validate(files, expectedDeliverables, context, referenceIndex);
            problems = collectProblems(files, issues);
            if (problems.isEmpty()) break;

            Map<String, List<RepairProblem>> byFile = new LinkedHashMap<>();
            for (RepairProblem problem : problems) {
                if (problem.filePath() == null || !LLM_REPAIRABLE_RULES.contains(problem.rule())) continue;
                byFile.computeIfAbsent(normalize(problem.filePath()), ignored -> new ArrayList<>()).add(problem);
            }

            for (Map.Entry<String, List<RepairProblem>> entry : byFile.entrySet()) {
                String path = entry.getKey();
                GeneratedFile current = findByPath(files, path);
                AtomicTask task = normalizedTasks.get(path);
                if (current == null || task == null) {
                    events.add(RepairEvent.failed(attempt, path, "PROJECT_LLM",
                            "No generated source or atomic task was available for project-level repair"));
                    continue;
                }
                String issueDescription = entry.getValue().stream()
                        .map(problem -> problem.rule() + ": " + problem.message())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
                String projectContext = buildProjectContext(current, files, context);
                GenerationResult result = fixer.fix(current, projectContext, task, issueDescription);
                if (result == null || !result.isSuccess() || result.getGeneratedFiles().isEmpty()) {
                    String error = result == null ? "Project repair returned no result" : result.getErrorMessage();
                    events.add(RepairEvent.failed(attempt, path, "PROJECT_LLM", error));
                    continue;
                }
                GeneratedFile candidate = result.getGeneratedFiles().get(0);
                GeneratedFile repaired = GeneratedFile.modify(current.getType(), current.getFilePath(), candidate.getContent());
                if (!passesConvention(repaired)) {
                    events.add(RepairEvent.failed(attempt, path, "PROJECT_LLM",
                            "Project-level repair introduced a layer-one convention error"));
                    continue;
                }
                if (!persister.persist(repaired)) {
                    events.add(RepairEvent.failed(attempt, path, "PROJECT_LLM",
                            "Project-level repair could not be persisted"));
                    continue;
                }
                replace(files, path, repaired);
                events.add(RepairEvent.success(attempt, path, "PROJECT_LLM", issueDescription));
                progressed = true;
            }

            issues = validate(files, expectedDeliverables, context, referenceIndex);
            problems = collectProblems(files, issues);
            if (!progressed) break;
        }

        return new RepairResult(files, issues, events, attempts);
    }

    static GeneratedFile repairGeneratedImports(GeneratedFile source, List<GeneratedFile> allFiles) {
        if (source == null || source.getContent() == null || !normalize(source.getFilePath()).endsWith(".java")) {
            return source;
        }
        Map<String, Set<String>> fqcnBySimpleName = new LinkedHashMap<>();
        for (GeneratedFile file : allFiles) {
            if (file == null || file.getContent() == null || !normalize(file.getFilePath()).endsWith(".java")) continue;
            Matcher pkg = PACKAGE.matcher(file.getContent());
            Matcher type = TYPE.matcher(file.getContent());
            if (!pkg.find() || !type.find()) continue;
            fqcnBySimpleName.computeIfAbsent(type.group(1), ignored -> new LinkedHashSet<>())
                    .add(pkg.group(1) + "." + type.group(1));
        }

        Matcher matcher = IMPORT.matcher(source.getContent());
        StringBuffer rewritten = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String imported = matcher.group(2);
            String simpleName = imported.substring(imported.lastIndexOf('.') + 1);
            Set<String> candidates = fqcnBySimpleName.getOrDefault(simpleName, Set.of());
            if (candidates.size() == 1) {
                String actual = candidates.iterator().next();
                if (!actual.equals(imported)) {
                    matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(1) + actual + matcher.group(3)));
                    changed = true;
                    continue;
                }
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(0)));
        }
        matcher.appendTail(rewritten);
        return changed
                ? GeneratedFile.modify(source.getType(), source.getFilePath(), rewritten.toString())
                : source;
    }

    private List<GeneratedProjectValidator.Issue> validate(List<GeneratedFile> files,
                                                            List<ExpectedDeliverable> expected,
                                                            GenerationContext context,
                                                            ReferenceProjectIndex referenceIndex) {
        return validator.validate(files, expected == null ? List.of() : expected, context, referenceIndex);
    }

    private List<RepairProblem> collectProblems(List<GeneratedFile> files,
                                                        List<GeneratedProjectValidator.Issue> projectIssues) {
        Map<String, RepairProblem> unique = new LinkedHashMap<>();
        for (GeneratedProjectValidator.Issue issue : projectIssues) {
            if (!issue.isError()) continue;
            RepairProblem problem = new RepairProblem(issue.rule(), issue.filePath(), issue.message());
            unique.put(problem.rule() + "|" + normalize(problem.filePath()) + "|" + problem.message(), problem);
        }
        for (CrossFileValidator.ContractIssue issue : crossFileValidator.validate(files, true)) {
            if (!issue.isError()) continue;
            RepairProblem problem = new RepairProblem(issue.rule, issue.sourceFilePath, issue.message);
            unique.put(problem.rule() + "|" + normalize(problem.filePath()) + "|" + problem.message(), problem);
        }
        return new ArrayList<>(unique.values());
    }

    private boolean passesConvention(GeneratedFile file) {
        ValidationResult result = conventionValidator.validate(file);
        return result != null && result.isPasses();
    }

    private String buildProjectContext(GeneratedFile source, List<GeneratedFile> files, GenerationContext context) {
        String path = normalize(source.getFilePath());
        StringBuilder prompt = new StringBuilder();
        prompt.append("Canonical identity: ").append(context.identity()).append('\n');
        prompt.append("Reference conventions: ").append(context.referenceProfile().describeForPrompt()).append("\n\n");
        prompt.append("Generated file inventory (physical paths are authoritative):\n");
        files.stream().map(GeneratedFile::getFilePath).filter(Objects::nonNull).sorted()
                .forEach(item -> prompt.append("- ").append(normalize(item)).append('\n'));
        prompt.append('\n');

        Predicate<String> relevant = relevantContractPredicate(path);
        int included = 0;
        for (GeneratedFile file : files) {
            String candidatePath = normalize(file.getFilePath());
            if (candidatePath == null || candidatePath.equals(path) || file.getContent() == null || !relevant.test(candidatePath)) {
                continue;
            }
            prompt.append("== Contract file: ").append(candidatePath).append(" ==\n");
            prompt.append(file.getContent()).append("\n\n");
            included++;
            if (included >= 12) break;
        }
        return prompt.toString();
    }

    private Predicate<String> relevantContractPredicate(String sourcePath) {
        if (sourcePath != null && sourcePath.endsWith("Mapper.xml")) {
            return path -> path.endsWith(".sql") || path.endsWith("Mapper.java")
                    || path.contains("/entity/") || path.contains("/vo/qvo/");
        }
        if (sourcePath != null && sourcePath.endsWith("Controller.java")) {
            return path -> path.contains("/entity/") || path.contains("/vo/")
                    || path.endsWith("Service.java") || path.contains("/feign/") || path.endsWith("Mapper.java");
        }
        return path -> path.endsWith(".sql") || path.contains("/entity/") || path.contains("/vo/")
                || path.endsWith("Service.java") || path.endsWith("Mapper.java");
    }

    private List<GeneratedFile> copyFiles(List<GeneratedFile> files) {
        List<GeneratedFile> copy = new ArrayList<>();
        if (files == null) return copy;
        for (GeneratedFile file : files) {
            copy.add(new GeneratedFile(file.getType(), file.getFilePath(), file.getContent(), file.getAction()));
        }
        return copy;
    }

    private static GeneratedFile findByPath(List<GeneratedFile> files, String path) {
        String normalized = normalize(path);
        return files.stream().filter(file -> normalized.equals(normalize(file.getFilePath()))).findFirst().orElse(null);
    }

    private static void replace(List<GeneratedFile> files, String path, GeneratedFile replacement) {
        String normalized = normalize(path);
        for (int i = 0; i < files.size(); i++) {
            if (normalized.equals(normalize(files.get(i).getFilePath()))) {
                files.set(i, replacement);
                return;
            }
        }
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private record RepairProblem(String rule, String filePath, String message) {
    }

    @FunctionalInterface
    public interface ProjectFileFixer {
        GenerationResult fix(GeneratedFile source, String projectContext, AtomicTask task, String issueDescription);
    }

    @FunctionalInterface
    public interface RepairPersister {
        boolean persist(GeneratedFile file);
    }

    public record RepairEvent(int attempt, String filePath, String strategy, boolean success, String detail) {
        static RepairEvent success(int attempt, String filePath, String strategy, String detail) {
            return new RepairEvent(attempt, filePath, strategy, true, detail);
        }

        static RepairEvent failed(int attempt, String filePath, String strategy, String detail) {
            return new RepairEvent(attempt, filePath, strategy, false, detail);
        }
    }

    public record RepairResult(List<GeneratedFile> files,
                               List<GeneratedProjectValidator.Issue> issues,
                               List<RepairEvent> events,
                               int attempts) {
    }
}
