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
    private static final Pattern SINGLE_FENCED_DOCUMENT = Pattern.compile(
            "(?is)^\\s*```(?:java|xml|sql|yaml|yml|properties)?\\s*\\R(.*?)\\R?```\\s*$");
    private static final List<String> PROTECTED_IMPORT_PREFIXES = List.of(
            "org.springblade.core.",
            "org.springblade.common.",
            "org.springblade.modules.");

    private static final Set<String> LLM_REPAIRABLE_RULES = Set.of(
            "MAPPER-RESULT-PROPERTY-MISSING",
            "MAPPER-DDL-COLUMN-MISSING",
            "MAPPER-PARAM-PROPERTY-MISSING",
            "CANONICAL-ENTITY-FIELD-MISSING",
            "CANONICAL-ENTITY-FIELD-TYPE",
            "CANONICAL-ENTITY-FIELD-UNEXPECTED",
            "CANONICAL-DDL-COLUMN-MISSING",
            "CANONICAL-INPUT-FIELD-MISSING",
            "CANONICAL-INPUT-VALIDATION-MISSING",
            "CANONICAL-UVO-ID-MISSING",
            "CANONICAL-UVO-INHERITANCE",
            "CANONICAL-VO-DERIVED-FIELD-MISSING",
            "CANONICAL-VO-FIELD-UNEXPECTED",
            "CANONICAL-VO-FIELD-SHADOW",
            "UNRESOLVED-PROJECT-IMPORT",
            "JAVA-IMPORT-MISSING",
            "SOURCE-INCOMPLETE-PLACEHOLDER",
            "TYPE-METHOD-MISSING",
            "TYPE-METHOD-REFERENCE-MISSING",
            "TYPE-ARGUMENT-MISMATCH",
            "TYPE-RETURN-MISMATCH",
            "CONTROLLER-SERVICE-BUSINESS-GAP",
            "LIST-MAPPER-PAGE-INCONSISTENT",
            "REFERENCE-API-METHOD-MISSING",
            "CROSS-CONTROLLER-SERVICE-MISMATCH",
            "CROSS-SERVICE-IMPL-IFACE-MISMATCH",
            "MAPPER-RESULTMAP-TYPE-MISMATCH",
            "CROSS-MAPPER-XML-NAMESPACE",
            "CROSS-WRAPPER-ENTITY-IVO",
            "CROSS-WRAPPER-ENTITY-UVO",
            "CROSS-FEIGN-FALLBACK-MISSING",
            "CROSS-IMPORT-CLOSURE-MISSING",
            "JAVA-SYNTAX-INVALID",
            "JAVA-PACKAGE-PATH-MISMATCH",
            "JAVA-TOPLEVEL-TYPE-MISMATCH",
            "XML-SYNTAX-INVALID",
            "XML-UNSAFE-DOCTYPE"
    );

    private final GeneratedProjectValidator validator;
    private final CrossFileValidator crossFileValidator;
    private final ConventionValidator conventionValidator;
    private final GeneratedSourceGate sourceGate;
    private final DeterministicContractRepairer deterministicContractRepairer = new DeterministicContractRepairer();

    public ProjectQualityRepairer(GeneratedProjectValidator validator, CrossFileValidator crossFileValidator,
                                  ConventionValidator conventionValidator) {
        this(validator, crossFileValidator, conventionValidator, new GeneratedSourceGate());
    }

    ProjectQualityRepairer(GeneratedProjectValidator validator, CrossFileValidator crossFileValidator,
                           ConventionValidator conventionValidator, GeneratedSourceGate sourceGate) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.crossFileValidator = Objects.requireNonNull(crossFileValidator, "crossFileValidator");
        this.conventionValidator = Objects.requireNonNull(conventionValidator, "conventionValidator");
        this.sourceGate = Objects.requireNonNull(sourceGate, "sourceGate");
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
        ValidationState state = inspect(files, expectedDeliverables, context, referenceIndex);
        int attempts = 0;

        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            attempts = attempt;
            boolean progressed = false;

            Set<String> activeRules = state.problems().stream()
                    .map(RepairProblem::rule)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            DeterministicContractRepairer.RepairBatch contractRepair =
                    deterministicContractRepairer.repair(files, context, activeRules);
            if (!contractRepair.changedPaths().isEmpty()) {
                ValidationState candidateState = inspect(
                        contractRepair.files(), expectedDeliverables, context, referenceIndex);
                if (isMonotonicImprovement(state, candidateState, true)) {
                    if (persistTransaction(files, contractRepair.files(), contractRepair.changedPaths(), persister)) {
                        files = contractRepair.files();
                        state = candidateState;
                        addBatchEvents(events, attempt, contractRepair.changedPaths(),
                                "DETERMINISTIC_CONTRACT_GROUP", true, "");
                        progressed = true;
                    } else {
                        addBatchEvents(events, attempt, contractRepair.changedPaths(),
                                "DETERMINISTIC_CONTRACT_GROUP", false, "PERSISTENCE_TRANSACTION_FAILED: ");
                    }
                } else {
                    addBatchEvents(events, attempt, contractRepair.changedPaths(),
                            "DETERMINISTIC_CONTRACT_GROUP", false,
                            "FAILED_REVALIDATION: contract-group candidate introduced a new error or did not improve quality; ");
                }
            }

            CandidateBatch cleanup = buildDeterministicSourceCleanupCandidate(files, attempt, events);
            if (!cleanup.changedPaths().isEmpty()) {
                ValidationState candidateState = inspect(cleanup.files(), expectedDeliverables, context, referenceIndex);
                if (isMonotonicImprovement(state, candidateState, true)) {
                    if (persistTransaction(files, cleanup.files(), cleanup.changedPaths(), persister)) {
                        files = cleanup.files();
                        state = candidateState;
                        addBatchEvents(events, attempt, cleanup.changedPaths(),
                                "DETERMINISTIC_SOURCE_CLEANUP", true, "");
                        progressed = true;
                    } else {
                        addBatchEvents(events, attempt, cleanup.changedPaths(),
                                "DETERMINISTIC_SOURCE_CLEANUP", false, "PERSISTENCE_TRANSACTION_FAILED: ");
                    }
                } else {
                    addBatchEvents(events, attempt, cleanup.changedPaths(),
                            "DETERMINISTIC_SOURCE_CLEANUP", false,
                            "FAILED_REVALIDATION: candidate introduced a new error or worsened project quality; ");
                }
            }

            CandidateBatch deterministic = buildDeterministicImportCandidate(files, attempt, events);
            if (!deterministic.changedPaths().isEmpty()) {
                ValidationState candidateState = inspect(deterministic.files(), expectedDeliverables, context, referenceIndex);
                if (isMonotonicImprovement(state, candidateState, true)) {
                    if (persistTransaction(files, deterministic.files(), deterministic.changedPaths(), persister)) {
                        files = deterministic.files();
                        state = candidateState;
                        addBatchEvents(events, attempt, deterministic.changedPaths(),
                                "DETERMINISTIC_IMPORT", true, "");
                        progressed = true;
                    } else {
                        addBatchEvents(events, attempt, deterministic.changedPaths(),
                                "DETERMINISTIC_IMPORT", false, "PERSISTENCE_TRANSACTION_FAILED: ");
                    }
                } else {
                    addBatchEvents(events, attempt, deterministic.changedPaths(),
                            "DETERMINISTIC_IMPORT", false,
                            "FAILED_REVALIDATION: candidate introduced a new error or worsened project quality; ");
                }
            }

            if (state.problems().isEmpty()) break;

            Map<String, List<RepairProblem>> byFile = new LinkedHashMap<>();
            for (RepairProblem problem : state.problems()) {
                if (problem.filePath() == null || !LLM_REPAIRABLE_RULES.contains(problem.rule())) continue;
                byFile.computeIfAbsent(normalize(problem.filePath()), ignored -> new ArrayList<>()).add(problem);
            }

            CandidateBatch llmCandidate = buildLlmCandidate(files, byFile, normalizedTasks, context,
                    fixer, attempt, events);
            if (!llmCandidate.changedPaths().isEmpty()) {
                ValidationState candidateState = inspect(llmCandidate.files(), expectedDeliverables, context, referenceIndex);
                if (isMonotonicImprovement(state, candidateState, true)) {
                    if (persistTransaction(files, llmCandidate.files(), llmCandidate.changedPaths(), persister)) {
                        files = llmCandidate.files();
                        state = candidateState;
                        addBatchEvents(events, attempt, llmCandidate.changedPaths(),
                                "PROJECT_LLM", true, "");
                        progressed = true;
                    } else {
                        addBatchEvents(events, attempt, llmCandidate.changedPaths(),
                                "PROJECT_LLM", false, "PERSISTENCE_TRANSACTION_FAILED: ");
                    }
                } else {
                    addBatchEvents(events, attempt, llmCandidate.changedPaths(),
                            "PROJECT_LLM", false,
                            "FAILED_REVALIDATION: candidate did not strictly reduce errors or introduced a new error; ");
                }
            }

            if (!progressed) break;
        }

        return new RepairResult(files, state.projectIssues(),
                finalizeEventsAfterRevalidation(events, state.projectIssues(), state.problems()), attempts);
    }

    private static void addBatchEvents(List<RepairEvent> events, int attempt,
                                       Map<String, String> changedPaths, String strategy,
                                       boolean success, String detailPrefix) {
        for (Map.Entry<String, String> entry : changedPaths.entrySet()) {
            String detail = detailPrefix + entry.getValue();
            events.add(success
                    ? RepairEvent.success(attempt, entry.getKey(), strategy, detail)
                    : RepairEvent.failed(attempt, entry.getKey(), strategy, detail));
        }
    }

    private CandidateBatch buildDeterministicSourceCleanupCandidate(List<GeneratedFile> files, int attempt,
                                                                      List<RepairEvent> events) {
        List<GeneratedFile> candidate = copyFiles(files);
        Map<String, String> changedPaths = new LinkedHashMap<>();
        for (GeneratedFile snapshot : new ArrayList<>(candidate)) {
            if (snapshot.getContent() == null) continue;
            Matcher matcher = SINGLE_FENCED_DOCUMENT.matcher(snapshot.getContent());
            if (!matcher.matches()) continue;
            GeneratedFile repaired = GeneratedFile.modify(snapshot.getType(), snapshot.getFilePath(),
                    matcher.group(1).strip());
            String path = normalize(snapshot.getFilePath());
            if (!passesConvention(repaired)) {
                events.add(RepairEvent.failed(attempt, path, "DETERMINISTIC_SOURCE_CLEANUP",
                        "Markdown fence removal was rejected by layer-one validation"));
                continue;
            }
            replace(candidate, path, repaired);
            changedPaths.put(path, "Removed a whole-document Markdown code fence");
        }
        return new CandidateBatch(candidate, changedPaths);
    }

    private CandidateBatch buildDeterministicImportCandidate(List<GeneratedFile> files, int attempt,
                                                              List<RepairEvent> events) {
        List<GeneratedFile> candidate = copyFiles(files);
        Map<String, String> changedPaths = new LinkedHashMap<>();
        for (GeneratedFile snapshot : new ArrayList<>(candidate)) {
            GeneratedFile repaired = repairGeneratedImports(snapshot, candidate);
            if (repaired == null || Objects.equals(snapshot.getContent(), repaired.getContent())) continue;
            String path = normalize(snapshot.getFilePath());
            if (!passesConvention(repaired)) {
                events.add(RepairEvent.failed(attempt, path, "DETERMINISTIC_IMPORT",
                        "Deterministic import rewrite was rejected by layer-one validation"));
                continue;
            }
            replace(candidate, path, repaired);
            changedPaths.put(path, "Aligned imports with generated project FQCNs");
        }
        return new CandidateBatch(candidate, changedPaths);
    }

    private CandidateBatch buildLlmCandidate(List<GeneratedFile> files,
                                             Map<String, List<RepairProblem>> byFile,
                                             Map<String, AtomicTask> normalizedTasks,
                                             GenerationContext context,
                                             ProjectFileFixer fixer,
                                             int attempt,
                                             List<RepairEvent> events) {
        List<GeneratedFile> candidateFiles = copyFiles(files);
        Map<String, String> changedPaths = new LinkedHashMap<>();
        for (Map.Entry<String, List<RepairProblem>> entry : byFile.entrySet()) {
            String path = entry.getKey();
            GeneratedFile current = findByPath(candidateFiles, path);
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
            String projectContext = buildProjectContext(current, candidateFiles, context);
            GenerationResult result = fixer.fix(current, projectContext, task, issueDescription);
            if (result == null || !result.isSuccess() || result.getGeneratedFiles().isEmpty()) {
                String error = result == null ? "Project repair returned no result" : result.getErrorMessage();
                events.add(RepairEvent.failed(attempt, path, "PROJECT_LLM", error));
                continue;
            }
            GeneratedFile generated = result.getGeneratedFiles().get(0);
            GeneratedFile repaired = GeneratedFile.modify(current.getType(), current.getFilePath(), generated.getContent());
            if (!passesConvention(repaired)) {
                events.add(RepairEvent.failed(attempt, path, "PROJECT_LLM",
                        "Project-level repair introduced a layer-one convention error"));
                continue;
            }
            replace(candidateFiles, path, repaired);
            changedPaths.put(path, issueDescription);
        }
        return new CandidateBatch(candidateFiles, changedPaths);
    }

    private ValidationState inspect(List<GeneratedFile> files,
                                    List<ExpectedDeliverable> expectedDeliverables,
                                    GenerationContext context,
                                    ReferenceProjectIndex referenceIndex) {
        List<GeneratedProjectValidator.Issue> projectIssues = new ArrayList<>(validate(
                files, expectedDeliverables, context, referenceIndex));
        appendUniqueIssues(projectIssues, sourceGate.validate(files));
        return new ValidationState(projectIssues, collectProblems(files, projectIssues));
    }

    static boolean isMonotonicImprovement(ValidationState before, ValidationState after,
                                          boolean requireStrictImprovement) {
        Set<String> beforeErrors = problemFingerprints(before.problems());
        Set<String> afterErrors = problemFingerprints(after.problems());
        if (!beforeErrors.containsAll(afterErrors)) return false;

        int beforeErrorCount = before.problems().size();
        int afterErrorCount = after.problems().size();
        long beforeWarnings = warningCount(before.projectIssues());
        long afterWarnings = warningCount(after.projectIssues());
        boolean nonWorsening = afterErrorCount <= beforeErrorCount && afterWarnings <= beforeWarnings;
        if (!nonWorsening) return false;
        return !requireStrictImprovement
                || afterErrorCount < beforeErrorCount
                || (afterErrorCount == beforeErrorCount && afterWarnings < beforeWarnings);
    }

    private static Set<String> problemFingerprints(List<RepairProblem> problems) {
        Set<String> fingerprints = new LinkedHashSet<>();
        for (RepairProblem problem : problems == null ? List.<RepairProblem>of() : problems) {
            fingerprints.add(problem.rule() + "|" + normalize(problem.filePath()));
        }
        return fingerprints;
    }

    private static long warningCount(List<GeneratedProjectValidator.Issue> issues) {
        return (issues == null ? List.<GeneratedProjectValidator.Issue>of() : issues).stream()
                .filter(issue -> !issue.isError()).count();
    }

    private boolean persistTransaction(List<GeneratedFile> beforeFiles,
                                       List<GeneratedFile> candidateFiles,
                                       Map<String, String> changedPaths,
                                       RepairPersister persister) {
        List<GeneratedFile> persistedOriginals = new ArrayList<>();
        for (String path : changedPaths.keySet()) {
            GeneratedFile candidate = findByPath(candidateFiles, path);
            GeneratedFile original = findByPath(beforeFiles, path);
            if (candidate == null || !persister.persist(candidate)) {
                for (int i = persistedOriginals.size() - 1; i >= 0; i--) {
                    GeneratedFile rollback = persistedOriginals.get(i);
                    persister.persist(GeneratedFile.modify(rollback.getType(), rollback.getFilePath(), rollback.getContent()));
                }
                return false;
            }
            if (original != null) persistedOriginals.add(original);
        }
        return true;
    }

    record ValidationState(List<GeneratedProjectValidator.Issue> projectIssues,
                           List<RepairProblem> problems) {
        ValidationState {
            projectIssues = projectIssues == null ? List.of() : List.copyOf(projectIssues);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    private record CandidateBatch(List<GeneratedFile> files, Map<String, String> changedPaths) {
    }

    static List<RepairEvent> finalizeEventsAfterRevalidation(
            List<RepairEvent> events, List<GeneratedProjectValidator.Issue> issues) {
        return finalizeEventsAfterRevalidation(events, issues, List.of());
    }

    private static List<RepairEvent> finalizeEventsAfterRevalidation(
            List<RepairEvent> events, List<GeneratedProjectValidator.Issue> issues,
            List<RepairProblem> problems) {
        Set<String> unresolvedPaths = new LinkedHashSet<>();
        for (GeneratedProjectValidator.Issue issue : issues == null
                ? List.<GeneratedProjectValidator.Issue>of() : issues) {
            if (issue.isError() && issue.filePath() != null && !issue.filePath().isBlank()) {
                unresolvedPaths.add(normalize(issue.filePath()));
            }
        }
        for (RepairProblem problem : problems == null ? List.<RepairProblem>of() : problems) {
            if (problem.filePath() != null && !problem.filePath().isBlank()) {
                unresolvedPaths.add(normalize(problem.filePath()));
            }
        }
        List<RepairEvent> finalized = new ArrayList<>();
        for (RepairEvent event : events == null ? List.<RepairEvent>of() : events) {
            if (event.success() && unresolvedPaths.contains(normalize(event.filePath()))) {
                finalized.add(RepairEvent.failed(event.attempt(), event.filePath(), event.strategy(),
                        "FAILED_REVALIDATION: " + event.detail()));
            } else {
                finalized.add(event);
            }
        }
        return finalized;
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
            if (isProtectedImport(imported)) {
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
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

    private static boolean isProtectedImport(String imported) {
        return PROTECTED_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith);
    }

    private List<GeneratedProjectValidator.Issue> validate(List<GeneratedFile> files,
                                                            List<ExpectedDeliverable> expected,
                                                            GenerationContext context,
                                                            ReferenceProjectIndex referenceIndex) {
        return validator.validate(files, expected == null ? List.of() : expected, context, referenceIndex);
    }

    private void appendUniqueIssues(List<GeneratedProjectValidator.Issue> target,
                                    List<GeneratedProjectValidator.Issue> additions) {
        Set<String> fingerprints = new LinkedHashSet<>();
        for (GeneratedProjectValidator.Issue issue : target) {
            fingerprints.add(issue.rule() + "|" + normalize(issue.filePath()) + "|" + issue.message());
        }
        for (GeneratedProjectValidator.Issue issue : additions == null
                ? List.<GeneratedProjectValidator.Issue>of() : additions) {
            String fingerprint = issue.rule() + "|" + normalize(issue.filePath()) + "|" + issue.message();
            if (fingerprints.add(fingerprint)) target.add(issue);
        }
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
        prompt.append(context.domainContract().describeForPrompt()).append("\n");
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
