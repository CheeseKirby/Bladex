package org.springblade.aiworkflow.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.entity.AiExecutionLog;
import org.springblade.aiworkflow.entity.AiGeneratedFile;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.enums.PlanStatus;
import org.springblade.aiworkflow.enums.SubPlanStatus;
import org.springblade.aiworkflow.enums.TaskType;
import org.springblade.aiworkflow.enums.WriteTarget;
import org.springblade.aiworkflow.enums.ClassType;
import org.springblade.aiworkflow.config.AiWorkflowProperties;
import org.springblade.aiworkflow.mapper.AiExecutionLogMapper;
import org.springblade.aiworkflow.mapper.AiGeneratedFileMapper;
import org.springblade.aiworkflow.mapper.AiPlanMapper;
import org.springblade.aiworkflow.mapper.AiSubPlanMapper;
import org.springblade.aiworkflow.notification.WorkflowStatusNotifier;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BladeX代码Agent — 核心编排器
 *
 * <p>执行完整的代码生成工作流：
 * 加载方案 → 构建DAG → 拓扑执行 → 规范校验 → 文件写入 → 编译验证 → 代码审查 → Git提交
 *
 * @author AI Developer
 */
@Slf4j
public class BladeXCodeAgent {

    private final AiPlanMapper planMapper;
    private final AiSubPlanMapper subPlanMapper;
    private final AiExecutionLogMapper executionLogMapper;
    private final AiGeneratedFileMapper generatedFileMapper;
    private final BladeCodeGenRouter codeGenRouter;
    private final ConventionValidator conventionValidator;
    private final ChangeEvaluator changeEvaluator;
    private final FileWriteExecutor fileWriteExecutor;
    private final BuildVerifier buildVerifier;
    private final ObjectMapper objectMapper;
    private final int maxReviewRetries;
    private final boolean autoCommit;
    private final WorkflowStatusNotifier statusNotifier;
    /** 阶段2: 配置(取 targetProjectRoot/outputRoot 决定写盘 root) */
    private final AiWorkflowProperties properties;
    /** 阶段2: 已有项目索引(REAL 模式查重用) */
    private final ConflictDetector conflictDetector;
    /** 阶段2增强: 参考项目索引(REAL 模式生成时参考现有风格) */
    private final ReferenceProjectIndex referenceProjectIndex;

    /** 跨文件契约校验器 — 无状态工具, 直接持有实例 */
    private final CrossFileValidator crossFileValidator = new CrossFileValidator();
    private final GeneratedProjectValidator generatedProjectValidator = new GeneratedProjectValidator();
    private final GeneratedSourceGate generatedSourceGate = new GeneratedSourceGate();
    private final ProjectQualityRepairer projectQualityRepairer;
    private final PlanArtifactCompiler planArtifactCompiler = new PlanArtifactCompiler();
    private final CanonicalDomainContractCompiler domainContractCompiler = new CanonicalDomainContractCompiler();
    private final ReferenceArtifactBinder referenceArtifactBinder = new ReferenceArtifactBinder();
    private final PlanArtifactTaskValidator planArtifactTaskValidator = new PlanArtifactTaskValidator();

    /** 拓扑排序器(H8 拆出)- 子方案 DAG 排序 + 依赖解析 */
    private final TopologySorter topologySorter;
    private final GeneratedFileStore generatedFileStore;

    public BladeXCodeAgent(AiPlanMapper planMapper, AiSubPlanMapper subPlanMapper,
                           AiExecutionLogMapper executionLogMapper,
                           AiGeneratedFileMapper generatedFileMapper,
                           BladeCodeGenRouter codeGenRouter,
                           ConventionValidator conventionValidator,
                           ChangeEvaluator changeEvaluator,
                           FileWriteExecutor fileWriteExecutor,
                           BuildVerifier buildVerifier,
                           ObjectMapper objectMapper,
                           int maxReviewRetries, boolean autoCommit,
                           AiWorkflowProperties properties,
                           ConflictDetector conflictDetector,
                           ReferenceProjectIndex referenceProjectIndex,
                           TopologySorter topologySorter,
                           GeneratedFileStore generatedFileStore,
                           WorkflowStatusNotifier statusNotifier) {
        this.planMapper = planMapper;
        this.subPlanMapper = subPlanMapper;
        this.executionLogMapper = executionLogMapper;
        this.generatedFileMapper = generatedFileMapper;
        this.codeGenRouter = codeGenRouter;
        this.conventionValidator = conventionValidator;
        this.changeEvaluator = changeEvaluator;
        this.fileWriteExecutor = fileWriteExecutor;
        this.buildVerifier = buildVerifier;
        this.objectMapper = objectMapper;
        this.maxReviewRetries = maxReviewRetries;
        this.autoCommit = autoCommit;
        this.properties = properties;
        this.conflictDetector = conflictDetector;
        this.referenceProjectIndex = referenceProjectIndex;
        this.topologySorter = topologySorter;
        this.generatedFileStore = generatedFileStore;
        this.statusNotifier = statusNotifier;
        this.projectQualityRepairer = new ProjectQualityRepairer(generatedProjectValidator, crossFileValidator,
                conventionValidator != null ? conventionValidator : new ConventionValidator());
    }

    /**
     * 执行工作流
     */
    public void executeWorkflow(String receptionId) {
        log.info("开始执行工作流: receptionId={}", receptionId);

        // 骨架去重集合 — per-plan 独立，避免单例 agent 跨 plan 并发污染
        Set<String> ensuredSkeletonKeys = new HashSet<>();

        // 1. 加载方案
        AiPlan plan = planMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiPlan>()
                        .eq(AiPlan::getReceptionId, receptionId));
        if (plan == null) {
            log.error("方案不存在: receptionId={}", receptionId);
            return;
        }

        // 更新方案状态为执行中
        plan.setStatus(PlanStatus.EXECUTING);
        planMapper.updateById(plan);

        // 加载子方案
        List<AiSubPlan> subPlans = subPlanMapper.selectByPlanId(plan.getId());
        log.info("加载方案完成: planId={}, subPlanCount={}", plan.getId(), subPlans.size());

        GenerationIdentity generationIdentity = GenerationIdentityResolver.resolve(plan, subPlans, objectMapper);
        ReferenceFrameworkProfile frameworkProfile = referenceProjectIndex != null
                ? referenceProjectIndex.getFrameworkProfile() : ReferenceFrameworkProfile.defaults();
        CanonicalPlanContractV2 wireContract = null;
        if (plan.getCanonicalContractJson() != null && !plan.getCanonicalContractJson().isBlank()) {
            try {
                wireContract = objectMapper.readValue(plan.getCanonicalContractJson(), CanonicalPlanContractV2.class);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Unable to deserialize canonicalContract v2", error);
            }
        }
        CanonicalDomainContractCompiler.Compilation domainCompilation;
        if (wireContract != null) {
            List<PlanCompilationIssue> contractIssues = wireContract.validateStructure().stream()
                    .map(message -> PlanCompilationIssue.error(null, "CANONICAL-CONTRACT-V2", "canonicalContract", message))
                    .toList();
            domainCompilation = new CanonicalDomainContractCompiler.Compilation(
                    wireContract.toDomainContract(), contractIssues);
        } else {
            domainCompilation = domainContractCompiler.compile(plan, subPlans, generationIdentity);
        }
        GenerationContext generationContext = new GenerationContext(
                generationIdentity, frameworkProfile, domainCompilation.contract(), wireContract);
        try {
            plan.setGenerationIdentityJson(objectMapper.writeValueAsString(generationIdentity));
            plan.setReferenceProfileJson(objectMapper.writeValueAsString(frameworkProfile));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to persist generation context", e);
        }
        boolean realWrite = WriteTarget.parse(plan.getWriteTarget()).isReal();
        plan.setOutputDirectory(realWrite
                ? Paths.get(properties.getOutputRoot(), receptionId, "real-staging").toString()
                : Paths.get(properties.getOutputRoot(), receptionId).toString());
        plan.setCompileVerificationStatus("NOT_RUN");
        planMapper.updateById(plan);
        log.info("Generation context locked: identity={}, profile={}, domainFields={}",
                generationIdentity, frameworkProfile.describeForPrompt(),
                generationContext.domainContract().persistentNames());
        logPlanExecution(plan, "DOMAIN_CONTRACT_COMPILE", "", domainCompilation.issues().stream()
                        .anyMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())) ? "FAILED" : "SUCCESS",
                "Compiled " + generationContext.domainContract().persistentFields().size()
                        + " persistent fields and " + generationContext.domainContract().derivedFields().size()
                        + " derived fields", generationContext.domainContract().describeForPrompt());

        // 2. 构建DAG并验证无环
        List<AiSubPlan> executionOrder = topologySorter.buildExecutionOrder(subPlans);
        if (executionOrder == null) {
            log.error("子方案依赖关系中存在循环");
            plan.setStatus(PlanStatus.FAILED);
            planMapper.updateById(plan);
            return;
        }

        // 3. 按拓扑顺序执行
        //    子方案失败不立刻中断整条流水线: 仅跳过依赖该失败子方案的下游(直接/传递)子方案,
        //    无依赖关系的并列子方案继续执行,让 Part A 拿到尽可能多的部分结果。
        Map<Long, List<AtomicTask>> plannedTasks = new LinkedHashMap<>();
        List<ExpectedDeliverable> expectedDeliverables = new ArrayList<>();
        List<PlanCompilationIssue> planCompilationIssues = new ArrayList<>(domainCompilation.issues());
        List<PlannedArtifact> plannedArtifacts = new ArrayList<>();
        List<AtomicTask> acceptedPlanTasks = new ArrayList<>();
        PlannedTaskRegistry taskRegistry = new PlannedTaskRegistry();
        for (AiSubPlan plannedSubPlan : executionOrder) {
            SubPlanTaskCompilation compilation = parseAtomicTasks(plannedSubPlan, generationContext);
            planCompilationIssues.addAll(compilation.issues());
            plannedArtifacts.addAll(compilation.artifacts());
            List<AtomicTask> acceptedTasks = new ArrayList<>();
            for (AtomicTask task : compilation.tasks()) {
                task.setSourceSubPlanId(plannedSubPlan.getId());
                PlannedTaskRegistry.Registration registration = taskRegistry.claim(plannedSubPlan.getId(), task);
                if (!registration.accepted()) {
                    PlannedTaskRegistry.Claim owner = registration.owner();
                    String ownerDetail = owner == null ? "none"
                            : "ownerSubPlanId=" + owner.subPlanId() + ", ownerPath=" + owner.targetPath();
                    String reason = "rule=" + registration.rule() + ", " + ownerDetail;
                    planCompilationIssues.add(PlanCompilationIssue.error(plannedSubPlan.getId(),
                            registration.rule(), task.getTargetPath(), reason));
                    log.warn("Plan task conflict: subPlanId={}, path={}, {}",
                            plannedSubPlan.getId(), task.getTargetPath(), reason);
                    logExecution(plannedSubPlan.getId(), "PLAN_TASK_CONFLICT", task.getTargetPath(),
                            "FAILED", reason, null);
                    continue;
                }
                if (registration.merged()) {
                    PlannedTaskRegistry.Claim owner = registration.owner();
                    String reason = "Merged reviewed contribution into ownerSubPlanId=" + owner.subPlanId()
                            + ", contributors=" + owner.contributorSubPlanIds();
                    log.info("Plan task contribution merged: subPlanId={}, path={}, {}",
                            plannedSubPlan.getId(), task.getTargetPath(), reason);
                    logExecution(plannedSubPlan.getId(), "PLAN_TASK_MERGE", task.getTargetPath(),
                            "MERGED", reason, null);
                    continue;
                }
                AtomicTask canonicalTask = registration.canonicalTask();
                acceptedTasks.add(canonicalTask);
                acceptedPlanTasks.add(canonicalTask);
                expectedDeliverables.add(ExpectedDeliverable.from(plannedSubPlan.getId(), canonicalTask));
            }
            plannedTasks.put(plannedSubPlan.getId(), acceptedTasks);
        }
        planCompilationIssues.addAll(planArtifactTaskValidator.validate(plannedArtifacts, acceptedPlanTasks));
        for (PlanCompilationIssue issue : planCompilationIssues) {
            logExecution(issue.subPlanId(), "PLAN_COMPILATION", issue.filePath(), "FAILED",
                    issue.rule() + ": " + issue.message(), null);
        }

        boolean expectsApi = expectedDeliverables.stream()
                .anyMatch(item -> "API".equals(BladeXModuleLayout.sideOfPath(item.targetPath())));
        boolean expectsImpl = expectedDeliverables.stream()
                .anyMatch(item -> "IMPL".equals(BladeXModuleLayout.sideOfPath(item.targetPath())));
        if (expectsApi) {
            String apiPomPath = BladeXModuleLayout.apiPomPath(generationContext);
            if (referenceProjectIndex == null || !referenceProjectIndex.pathExists(apiPomPath)) {
                expectedDeliverables.add(new ExpectedDeliverable(null, TaskType.OTHER,
                        apiPomPath, generationIdentity.entityName(), generationIdentity.moduleName(), true));
                if (referenceProjectIndex != null
                        && referenceProjectIndex.readSourceContent("blade-service-api/pom.xml") != null) {
                    expectedDeliverables.add(new ExpectedDeliverable(null, TaskType.OTHER,
                            "blade-service-api/pom.xml", generationIdentity.entityName(), generationIdentity.moduleName(), true));
                }
            }
        }
        if (expectsImpl) {
            boolean existingServiceModule = referenceProjectIndex != null
                    && referenceProjectIndex.pathExists(BladeXModuleLayout.implPomPath(generationContext));
            for (String path : List.of(BladeXModuleLayout.implPomPath(generationContext),
                    BladeXModuleLayout.applicationPath(generationContext),
                    BladeXModuleLayout.bootstrapPath(generationContext), BladeXModuleLayout.appDevPath(generationContext))) {
                if (referenceProjectIndex == null || !referenceProjectIndex.pathExists(path)) {
                    expectedDeliverables.add(new ExpectedDeliverable(null, TaskType.OTHER, path,
                            generationIdentity.entityName(), generationIdentity.moduleName(), true));
                }
            }
            if (!existingServiceModule && referenceProjectIndex != null
                    && referenceProjectIndex.readSourceContent("blade-service/pom.xml") != null) {
                expectedDeliverables.add(new ExpectedDeliverable(null, TaskType.OTHER,
                        "blade-service/pom.xml", generationIdentity.entityName(), generationIdentity.moduleName(), true));
            }
        }

        PlanPreflightGate.Result preflight = PlanPreflightGate.evaluate(planCompilationIssues);
        if (preflight.blocking()) {
            failPlanPreflight(plan, executionOrder, expectedDeliverables, acceptedPlanTasks,
                    planCompilationIssues, preflight);
            return;
        }

        boolean allSuccess = true;
        Set<Long> failedIds = new HashSet<>();
        Map<Long, List<String>> reverseDeps = topologySorter.buildReverseDependencies(executionOrder);
        for (AiSubPlan subPlan : executionOrder) {
            // 判断当前子方案是否依赖某个已失败的子方案 -> 直接 SKIPPED
            if (topologySorter.dependsOnFailed(subPlan, failedIds)) {
                subPlan.setStatus(SubPlanStatus.SKIPPED);
                subPlan.setErrorMessage("前置依赖失败,跳过执行");
                subPlan.setCompletedAt(LocalDateTime.now());
                subPlanMapper.updateById(subPlan);
                failedIds.add(subPlan.getId());
                allSuccess = false;
                statusNotifier.notifySubPlan(subPlan);
                continue;
            }
            try {
                boolean subPlanSuccess = executeSubPlan(subPlan, plan, ensuredSkeletonKeys, generationContext, plannedTasks.getOrDefault(subPlan.getId(), List.of()));
                if (!subPlanSuccess) {
                    allSuccess = false;
                    failedIds.add(subPlan.getId());
                }
            } catch (Exception e) {
                log.error("子方案执行异常: subPlanId={}", subPlan.getId(), e);
                subPlan.setStatus(SubPlanStatus.FAILED);
                subPlan.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                subPlan.setCompletedAt(LocalDateTime.now());
                subPlanMapper.updateById(subPlan);
                failedIds.add(subPlan.getId());
                allSuccess = false;
                statusNotifier.notifySubPlan(subPlan);
            }
        }
        // 静默使用避免 unused 警告 — reverseDeps 留作未来按反向依赖快速跳过整条分支
        if (!reverseDeps.isEmpty()) {
            log.debug("反向依赖图大小: {}", reverseDeps.size());
        }

        // 3.5 plan 级 Entity↔DDL 自动修复 — entity↔DDL 不一致在子方案级检不出(DDL/Entity 跨子方案),
        //     以 DDL 为源头重生成 Entity, 写盘 + 更新 DB。修复失败不阻断主流程。
        //     先修复再校验,确保 execution_log 的 PLAN_CROSS_VALIDATION 反映修复后状态(非过期快照)。
        retryPlanWideEntityDdlMismatches(plan, generationContext);

        // 3.5b plan 级 VO/IVO/UVO <-> Entity 字段一致性修复(B1/B2/B3) - Entity 与 VO 常分属不同子方案,
        //     子方案级检不出; 以 Entity 为源头重生成 VO/IVO/UVO, 写盘 + 更新 DB。修复失败不阻断主流程。
        retryPlanWideVoEntityMismatches(plan, generationContext);
        repairPackageDeclarations(plan);

        // 3.6 Strict project-quality repair loop. Deterministic fixes run first; Mapper/Controller errors are
        //     regenerated with the complete relevant project contract, persisted, and revalidated before status.
        List<AtomicTask> allPlannedTasks = plannedTasks.values().stream().flatMap(Collection::stream).toList();
        List<GeneratedProjectValidator.Issue> projectIssues = new ArrayList<>(repairPlanWideProjectQuality(
                plan, expectedDeliverables, generationContext, allPlannedTasks));
        projectIssues.addAll(planCompilationIssues.stream().map(PlanCompilationIssue::toProjectIssue).toList());
        List<GeneratedFile> finalGeneratedFiles = generatedFileStore.loadPlanFiles(plan);
        appendUniqueProjectIssues(projectIssues, generatedSourceGate.validate(finalGeneratedFiles));
        markCompilationIssueSubPlans(executionOrder, planCompilationIssues);
        markProjectIssueSubPlans(executionOrder, projectIssues, expectedDeliverables);

        // 3.6b Final cross-file validation runs after project-quality repair so status reflects persisted files.
        Optional<List<CrossFileValidator.ContractIssue>> finalContractValidation = validatePlanWideContracts(plan);
        boolean finalValidationSucceeded = finalContractValidation.isPresent();
        long finalContractErrorCount = finalContractValidation
                .map(issues -> issues.stream().filter(CrossFileValidator.ContractIssue::isError).count())
                .orElse(0L);
        long finalContractWarningCount = finalContractValidation
                .map(issues -> issues.size() - issues.stream().filter(CrossFileValidator.ContractIssue::isError).count())
                .orElse(0L);

        long projectQualityErrorCount = projectIssues.stream().filter(GeneratedProjectValidator.Issue::isError).count();
        long projectQualityWarningCount = projectIssues.size() - projectQualityErrorCount;
        if (!projectIssues.isEmpty()) {
            String report;
            try {
                report = objectMapper.writeValueAsString(projectIssues);
            } catch (JsonProcessingException e) {
                report = projectIssues.toString();
            }
            logPlanExecution(plan, "PROJECT_QUALITY_VALIDATION", "",
                    projectQualityErrorCount > 0 ? "FAILED" : "SUCCESS",
                    "Project quality validation: " + projectQualityErrorCount + " ERROR / " + projectIssues.size() + " issues", report);
        } else {
            logPlanExecution(plan, "PROJECT_QUALITY_VALIDATION", "", "SUCCESS",
                    "Project quality validation passed", null);
        }

        // 子方案级校验发生在 plan 级修复之前。只有最终校验确实执行成功且已无 ERROR，才把此前的
        // COMPLETED_WITH_ERRORS 恢复成 COMPLETED；校验异常时保留原状态，不能把未知结果误报成功。
        if (finalValidationSucceeded && finalContractErrorCount == 0 && projectQualityErrorCount == 0) {
            reconcileRepairedSubPlanStatuses(executionOrder);
        }

        // Dependency-independent source validation always runs before a REAL project can be modified.
        boolean sourceGateFailed = projectIssues.stream()
                .anyMatch(issue -> issue.isError() && GeneratedSourceGate.isSourceGateRule(issue.rule()));
        boolean hasSubPlanErrors = executionOrder.stream()
                .anyMatch(sp -> sp.getStatus() == SubPlanStatus.COMPLETED_WITH_ERRORS);
        boolean qualityGateFailed = !allSuccess || hasSubPlanErrors
                || finalContractErrorCount + projectQualityErrorCount > 0;
        boolean compileFailed = false;
        boolean verificationFailed = sourceGateFailed || qualityGateFailed;
        if (sourceGateFailed) {
            plan.setCompileVerificationStatus("FAILED_SOURCE_GATE");
            logPlanExecution(plan, "SOURCE_GATE", "", "FAILED",
                    "Generated source failed dependency-independent syntax or XML validation", null);
        } else if (WriteTarget.parse(plan.getWriteTarget()).isReal()) {
            logPlanExecution(plan, "SOURCE_GATE", "", "SUCCESS",
                    "Generated source passed dependency-independent validation", null);
            if (qualityGateFailed) {
                plan.setCompileVerificationStatus("BLOCKED_QUALITY_GATE");
                logPlanExecution(plan, "REAL_PROMOTION", "", "SKIPPED",
                        "REAL project was not modified because final deterministic quality gates did not pass", null);
            } else {
                compileFailed = !promoteAndCompileRealPlan(plan, finalGeneratedFiles);
                verificationFailed = compileFailed;
            }
        } else {
            plan.setCompileVerificationStatus("PASSED_SOURCE_GATE_DEPENDENCIES_UNVERIFIED");
            logPlanExecution(plan, "SOURCE_GATE", "", "SUCCESS",
                    "Generated source passed syntax/XML validation; private reference dependencies were not compiled", null);
        }

        plan.setQualityErrorCount(Math.toIntExact(finalContractErrorCount + projectQualityErrorCount));
        plan.setQualityWarningCount(Math.toIntExact(finalContractWarningCount + projectQualityWarningCount));

        plan.setStatus(determineFinalPlanStatus(
                allSuccess, hasSubPlanErrors, finalContractErrorCount + projectQualityErrorCount, verificationFailed));
        GenerationReportWriter.write(plan, finalGeneratedFiles, expectedDeliverables,
                allPlannedTasks, projectIssues, finalContractValidation.orElse(List.of()), objectMapper);
        planMapper.updateById(plan);

        // 5. 回调Part A
        statusNotifier.notifyPlan(plan, executionOrder);

        log.info("工作流执行完成: receptionId={}, status={}", receptionId, plan.getStatus());
    }

    private void failPlanPreflight(AiPlan plan, List<AiSubPlan> executionOrder,
                                   List<ExpectedDeliverable> expectedDeliverables,
                                   List<AtomicTask> acceptedPlanTasks,
                                   List<PlanCompilationIssue> compilationIssues,
                                   PlanPreflightGate.Result preflight) {
        Set<Long> affected = new LinkedHashSet<>(preflight.affectedSubPlanIds());
        String summary = preflight.errors().stream()
                .map(issue -> issue.rule() + ": " + issue.message())
                .distinct().limit(8).reduce((left, right) -> left + " | " + right)
                .orElse("Plan preflight failed");
        LocalDateTime completedAt = LocalDateTime.now();
        for (AiSubPlan subPlan : executionOrder) {
            if (affected.contains(subPlan.getId())) {
                subPlan.setStatus(SubPlanStatus.FAILED);
                subPlan.setErrorMessage(summary);
            } else {
                subPlan.setStatus(SubPlanStatus.SKIPPED);
                subPlan.setErrorMessage("Plan preflight failed before generation; no files were generated");
            }
            subPlan.setCompletedAt(completedAt);
            subPlanMapper.updateById(subPlan);
            statusNotifier.notifySubPlan(subPlan);
        }

        long errorCount = compilationIssues.stream()
                .filter(issue -> "ERROR".equalsIgnoreCase(issue.severity())).count();
        long warningCount = compilationIssues.size() - errorCount;
        plan.setQualityErrorCount(Math.toIntExact(errorCount));
        plan.setQualityWarningCount(Math.toIntExact(warningCount));
        plan.setCompileVerificationStatus("NOT_RUN_PLAN_COMPILATION_FAILED");
        plan.setStatus(PlanStatus.FAILED);
        List<GeneratedProjectValidator.Issue> issues = compilationIssues.stream()
                .map(PlanCompilationIssue::toProjectIssue).toList();
        GenerationReportWriter.write(plan, List.of(), expectedDeliverables, acceptedPlanTasks, issues, objectMapper);
        planMapper.updateById(plan);
        statusNotifier.notifyPlan(plan, executionOrder);
        log.error("Plan preflight blocked generation: planId={}, errors={}, summary={}",
                plan.getId(), errorCount, summary);
    }

    private void appendUniqueProjectIssues(List<GeneratedProjectValidator.Issue> target,
                                           List<GeneratedProjectValidator.Issue> additions) {
        Set<String> fingerprints = new LinkedHashSet<>();
        for (GeneratedProjectValidator.Issue issue : target) {
            fingerprints.add(projectIssueFingerprint(issue));
        }
        for (GeneratedProjectValidator.Issue issue : additions == null
                ? List.<GeneratedProjectValidator.Issue>of() : additions) {
            if (fingerprints.add(projectIssueFingerprint(issue))) target.add(issue);
        }
    }

    private String projectIssueFingerprint(GeneratedProjectValidator.Issue issue) {
        return issue.rule() + "|" + normalizeIssuePath(issue.filePath()) + "|" + issue.message();
    }

    private String normalizeIssuePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    static PlanStatus determineFinalPlanStatus(boolean allSuccess,
                                               boolean hasSubPlanErrors,
                                               long finalContractErrorCount,
                                               boolean compileFailed) {
        if (!allSuccess) return PlanStatus.FAILED;
        return hasSubPlanErrors || finalContractErrorCount > 0 || compileFailed
                ? PlanStatus.COMPLETED_WITH_ERRORS
                : PlanStatus.COMPLETED;
    }

    /**
     * plan 级修复成功后清理子方案的过期错误状态。
     *
     * <p>子方案会在自身生成结束时先做一次局部跨文件校验；某些错误（如 VO↔Entity）随后由
     * plan 级修复闭环消除。若最终全局校验已无 ERROR，就应把这些子方案恢复为 COMPLETED，
     * 并补一条时间线记录说明错误已被自动修复。
     */
    void markCompilationIssueSubPlans(List<AiSubPlan> executionOrder,
                                          List<PlanCompilationIssue> compilationIssues) {
        Map<Long, List<PlanCompilationIssue>> errorsBySubPlan = new LinkedHashMap<>();
        for (PlanCompilationIssue issue : compilationIssues == null ? List.<PlanCompilationIssue>of() : compilationIssues) {
            if (issue.subPlanId() == null || !"ERROR".equalsIgnoreCase(issue.severity())) continue;
            errorsBySubPlan.computeIfAbsent(issue.subPlanId(), ignored -> new ArrayList<>()).add(issue);
        }
        if (errorsBySubPlan.isEmpty()) return;

        for (AiSubPlan subPlan : executionOrder == null ? List.<AiSubPlan>of() : executionOrder) {
            List<PlanCompilationIssue> errors = errorsBySubPlan.get(subPlan.getId());
            if (errors == null || errors.isEmpty()) continue;
            if (subPlan.getStatus() == SubPlanStatus.FAILED || subPlan.getStatus() == SubPlanStatus.SKIPPED) continue;
            String summary = errors.stream()
                    .map(issue -> issue.rule() + ": " + issue.message())
                    .distinct().limit(5).reduce((left, right) -> left + " | " + right).orElse("Plan compilation failed");
            subPlan.setStatus(SubPlanStatus.COMPLETED_WITH_ERRORS);
            subPlan.setErrorMessage(summary);
            if (subPlan.getCompletedAt() == null) subPlan.setCompletedAt(LocalDateTime.now());
            subPlanMapper.updateById(subPlan);
            statusNotifier.notifySubPlan(subPlan);
        }
    }

    void markProjectIssueSubPlans(List<AiSubPlan> executionOrder,
                                  List<GeneratedProjectValidator.Issue> projectIssues,
                                  List<ExpectedDeliverable> expectedDeliverables) {
        Map<String, Long> ownerByPath = new LinkedHashMap<>();
        for (ExpectedDeliverable deliverable : expectedDeliverables == null
                ? List.<ExpectedDeliverable>of() : expectedDeliverables) {
            if (deliverable.targetPath() != null && deliverable.subPlanId() != null) {
                ownerByPath.putIfAbsent(normalizeIssuePath(deliverable.targetPath()), deliverable.subPlanId());
            }
        }
        Map<Long, List<GeneratedProjectValidator.Issue>> errorsBySubPlan = new LinkedHashMap<>();
        for (GeneratedProjectValidator.Issue issue : projectIssues == null
                ? List.<GeneratedProjectValidator.Issue>of() : projectIssues) {
            if (!issue.isError() || issue.filePath() == null) continue;
            Long owner = ownerByPath.get(normalizeIssuePath(issue.filePath()));
            if (owner != null) errorsBySubPlan.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(issue);
        }
        if (errorsBySubPlan.isEmpty()) return;
        for (AiSubPlan subPlan : executionOrder == null ? List.<AiSubPlan>of() : executionOrder) {
            List<GeneratedProjectValidator.Issue> errors = errorsBySubPlan.get(subPlan.getId());
            if (errors == null || errors.isEmpty()) continue;
            if (subPlan.getStatus() == SubPlanStatus.FAILED || subPlan.getStatus() == SubPlanStatus.SKIPPED) continue;
            String summary = errors.stream().map(issue -> issue.rule() + ": " + issue.message())
                    .distinct().limit(5).reduce((left, right) -> left + " | " + right)
                    .orElse("Project quality validation failed");
            subPlan.setStatus(SubPlanStatus.COMPLETED_WITH_ERRORS);
            subPlan.setErrorMessage(summary);
            if (subPlan.getCompletedAt() == null) subPlan.setCompletedAt(LocalDateTime.now());
            subPlanMapper.updateById(subPlan);
            statusNotifier.notifySubPlan(subPlan);
        }
    }

    void reconcileRepairedSubPlanStatuses(List<AiSubPlan> executionOrder) {
        for (AiSubPlan subPlan : executionOrder) {
            if (subPlan.getStatus() != SubPlanStatus.COMPLETED_WITH_ERRORS) continue;
            subPlan.setStatus(SubPlanStatus.COMPLETED);
            subPlan.setErrorMessage(null);
            subPlanMapper.updateById(subPlan);
            logExecution(subPlan.getId(), "PLAN_CROSS_FIX", "", "SUCCESS",
                    "plan 级自动修复已消除该子方案的跨文件 ERROR，最终契约校验通过", null);
            statusNotifier.notifySubPlan(subPlan);
        }
    }

    /** REAL compile verification. Any verifier infrastructure exception fails closed. */
    private boolean runCompileVerification(AiPlan plan) {
        try {
            List<AiGeneratedFile> dbFiles = generatedFileMapper.selectByPlanId(plan.getId());
            if (dbFiles == null || dbFiles.isEmpty()) return true;
            Set<String> modules = new LinkedHashSet<>();
            for (AiGeneratedFile file : dbFiles) {
                String path = file.getFilePath();
                if (path == null) continue;
                for (String prefix : new String[]{"blade-service/", "blade-service-api/"}) {
                    if (path.startsWith(prefix)) {
                        String rest = path.substring(prefix.length());
                        int slash = rest.indexOf('/');
                        if (slash > 0) modules.add(prefix + rest.substring(0, slash));
                        break;
                    }
                }
            }
            if (modules.isEmpty()) return true;
            List<String> moduleList = new ArrayList<>(modules);
            BuildResult buildResult = buildVerifier.verify(moduleList);
            if (buildResult.isPasses()) {
                logPlanExecution(plan, "COMPILE_VERIFICATION", "", "SUCCESS",
                        "Compile verification passed: " + moduleList, null);
                return true;
            }
            String summary = "Compile verification failed: " + moduleList + "; "
                    + buildResult.getErrors().stream().map(Object::toString).limit(10)
                    .reduce((left, right) -> left + " | " + right).orElse("");
            logPlanExecution(plan, "COMPILE_VERIFICATION", "", "FAILED", summary, null);
            log.warn("Compile verification failed: plan={}, {}", plan.getReceptionId(), summary);
            return false;
        } catch (Exception error) {
            log.error("Compile verification infrastructure failure: plan={}", plan.getReceptionId(), error);
            logPlanExecution(plan, "COMPILE_VERIFICATION", "", "FAILED",
                    "Compile verification infrastructure failure: " + error.getMessage(), null);
            return false;
        }
    }

    boolean promoteAndCompileRealPlan(AiPlan plan, List<GeneratedFile> finalGeneratedFiles) {
        if (finalGeneratedFiles == null || finalGeneratedFiles.isEmpty()) {
            plan.setCompileVerificationStatus("FAILED_REAL_PROMOTION");
            logPlanExecution(plan, "REAL_PROMOTION", "", "FAILED",
                    "No generated files were available for promotion", null);
            return false;
        }
        List<FileWriteTask> writeTasks = finalGeneratedFiles.stream()
                .map(file -> new FileWriteTask(file.getFilePath(), file.getContent(), file.getAction()))
                .toList();
        FileWriteExecutor.PreparedWrite prepared;
        try {
            prepared = fileWriteExecutor.prepareTransactionalWrite(writeTasks, properties.getTargetProjectRoot());
        } catch (Exception error) {
            plan.setCompileVerificationStatus("FAILED_REAL_PROMOTION");
            logPlanExecution(plan, "REAL_PROMOTION", "", "FAILED",
                    "Unable to snapshot REAL project targets: " + error.getMessage(), null);
            return false;
        }

        WriteResult promotion = prepared.apply();
        if (!promotion.isSuccess()) {
            plan.setCompileVerificationStatus("FAILED_REAL_PROMOTION");
            logPlanExecution(plan, "REAL_PROMOTION", "", "ROLLED_BACK", promotion.getErrorMessage(), null);
            return false;
        }
        logPlanExecution(plan, "REAL_PROMOTION", "", "SUCCESS",
                "Promoted " + promotion.getWrittenFiles().size() + " staged files under a reversible snapshot", null);

        if (!runCompileVerification(plan)) {
            WriteResult rollback = prepared.rollback();
            plan.setCompileVerificationStatus(rollback.isSuccess()
                    ? "FAILED_COMPILE_ROLLED_BACK" : "FAILED_COMPILE_ROLLBACK");
            logPlanExecution(plan, "REAL_ROLLBACK", "", rollback.isSuccess() ? "ROLLED_BACK" : "FAILED",
                    rollback.isSuccess()
                            ? "Compile verification failed; every promoted file was restored"
                            : "Compile verification failed and rollback was incomplete: " + rollback.getErrorMessage(), null);
            return false;
        }

        prepared.commit();
        plan.setOutputDirectory(properties.getTargetProjectRoot());
        plan.setCompileVerificationStatus("PASSED");
        logPlanExecution(plan, "REAL_PROMOTION", "", "COMMITTED",
                "Compile verification passed; REAL project changes committed", null);
        return true;
    }

    /**
     * 执行单个子方案
     */
    private boolean executeSubPlan(AiSubPlan subPlan, AiPlan plan, Set<String> ensuredSkeletonKeys, GenerationContext generationContext, List<AtomicTask> tasks) {
        log.info("执行子方案: id={}, title={}", subPlan.getId(), subPlan.getTitle());

        // 更新状态
        subPlan.setStatus(SubPlanStatus.EXECUTING);
        subPlan.setStartedAt(LocalDateTime.now());
        subPlanMapper.updateById(subPlan);

        // 3a. 解析子方案为原子任务
        log.info("Atomic task count: {}", tasks.size());

        // 3b. 对每个原子任务执行生成→校验→修复→写入循环
        List<GeneratedFile> allGeneratedFiles = new ArrayList<>();
        // filePath -> 原子任务, 供跨文件修复时重建 Controller 的生成上下文(角色 prompt + 实体名/模块名)
        Map<String, AtomicTask> taskByPath = new HashMap<>();
        for (AtomicTask task : tasks) {
            // 评估改动必要性
            ChangeEvaluation eval = changeEvaluator.evaluate(task);
            logExecution(subPlan.getId(), "CHANGE_EVALUATION", task.getTargetPath(),
                    eval.getAction(), eval.getReason(), null);

            if ("SKIP".equals(eval.getAction())) {
                continue;
            }

            // 选择生成策略并生成 — 阶段2增强: REAL 模式注入参考项目适配摘要 + 同类代码
            GenerationResult genResult;
            String adaptationSummary = getAdaptationSummary(subPlan.getPlanId());
            String referenceSummary = findReferenceSummary(task);
            boolean hasRef = adaptationSummary != null || referenceSummary != null;
            genResult = hasRef
                    ? codeGenRouter.generateWithReference(task, adaptationSummary, referenceSummary)
                    : codeGenRouter.generate(task);
            if (!genResult.isSuccess()) {
                subPlan.setStatus(SubPlanStatus.FAILED);
                subPlan.setErrorMessage(genResult.getErrorMessage());
                subPlan.setCompletedAt(LocalDateTime.now());
                subPlanMapper.updateById(subPlan);
                statusNotifier.notifySubPlan(subPlan);
                return false;
            }

            GeneratedFile file = genResult.getGeneratedFiles().get(0);

            // 规范校验（最多重试maxReviewRetries次）— 仅 ERROR 级别阻塞,WARN 仅记录建议
            boolean validationPassed = false;
            for (int retry = 0; retry <= maxReviewRetries; retry++) {
                ValidationResult validation = conventionValidator.validate(file);
                if (validation.isPasses()) {
                    validationPassed = true;
                    String reason = validation.hasWarningsOnly()
                            ? "规范校验通过 (尝试" + (retry + 1) + "次, " + validation.getIssues().size() + " 个 WARN 建议)"
                            : "规范校验通过 (尝试" + (retry + 1) + "次)";
                    String warnJson = null;
                    if (validation.hasWarningsOnly()) {
                        try {
                            warnJson = objectMapper.writeValueAsString(validation);
                        } catch (JsonProcessingException e) {
                            warnJson = null;
                        }
                    }
                    logExecution(subPlan.getId(), "VALIDATION", file.getFilePath(),
                            "SUCCESS", reason, warnJson);
                    break;
                }

                log.warn("规范校验不通过 (尝试{}/{}): {}", retry + 1, maxReviewRetries + 1,
                        validation.getIssues().size());

                if (retry < maxReviewRetries) {
                    // 修复
                    GenerationResult fixResult = codeGenRouter.fix(validation, file, task);
                    if (fixResult.isSuccess()) {
                        file = fixResult.getGeneratedFiles().get(0);
                    }
                } else {
                    // 超过最大重试次数
                    String validationJson;
                    try {
                        validationJson = objectMapper.writeValueAsString(validation);
                    } catch (JsonProcessingException e) {
                        log.warn("序列化校验结果失败, 使用fallback", e);
                        validationJson = "{\"issues\":" + validation.getIssues().size() + "}";
                    }
                    logExecution(subPlan.getId(), "VALIDATION", file.getFilePath(),
                            "FAILED", "规范校验失败，已达最大重试次数", validationJson);
                    long errorCount = validation.getIssues().stream()
                            .filter(i -> "ERROR".equalsIgnoreCase(i.getSeverity()))
                            .count();
                    subPlan.setStatus(SubPlanStatus.FAILED);
                    subPlan.setErrorMessage("规范校验失败: " + errorCount + " 个 ERROR / " + validation.getIssues().size() + " 个问题");
                    subPlan.setCompletedAt(LocalDateTime.now());
                    subPlanMapper.updateById(subPlan);
                    statusNotifier.notifySubPlan(subPlan);
                    return false;
                }
            }

            // 通过校验，记录结果
            if (validationPassed) {
                allGeneratedFiles.add(file);
                taskByPath.put(file.getFilePath(), task);
                logExecution(subPlan.getId(), "CODE_GENERATION", file.getFilePath(),
                        "CREATED", "代码生成并校验通过", null);
            }
        }

        // 3c. 跨文件契约校验 — 先尝试自动修复 Controller→Service 签名不一致
        //     (以 Service 接口为契约源头重生成 Controller), 再跑最终校验记录日志。
        //     其余跨文件 ERROR 维持"仅记录不阻断写盘"策略(修复需跨文件重生成,留给日志供人工/后续参考),
        //     避免因误报阻断可用的部分结果。ConventionValidator(Layer1)已阻断单文件内的硬错误。
        long crossFileErrorCount = 0; // H3: 跨文件未修复 ERROR 数, 用于终态判定(>0 标 COMPLETED_WITH_ERRORS)
        if (!allGeneratedFiles.isEmpty()) {
            retryCrossFileContractMismatches(subPlan, allGeneratedFiles, taskByPath);
            List<CrossFileValidator.ContractIssue> crossIssues = crossFileValidator.validate(allGeneratedFiles);
            if (!crossIssues.isEmpty()) {
                long errorCount = crossIssues.stream().filter(CrossFileValidator.ContractIssue::isError).count();
                crossFileErrorCount = errorCount;
                String summary = "跨文件契约校验: " + crossIssues.size() + " 项 (" + errorCount + " ERROR, "
                        + (crossIssues.size() - errorCount) + " WARN); "
                        + crossIssues.stream().map(Object::toString).limit(5)
                                .reduce((a, b) -> a + " | " + b).orElse("");
                if (errorCount > 0) {
                    log.warn(summary);
                } else {
                    log.info(summary);
                }
                logExecution(subPlan.getId(), "CROSS_FILE_VALIDATION", "",
                        errorCount > 0 ? "FAILED" : "SUCCESS", summary, null);
            } else {
                logExecution(subPlan.getId(), "CROSS_FILE_VALIDATION", "",
                        "SUCCESS", "跨文件契约校验通过", null);
            }
        }

        // 3d-0. 模块骨架补齐 — 为本子方案涉及的 BladeX 模块(api/impl)补齐 pom/Application/bootstrap，
        //       复用既有写盘/落库流程；per-plan ensuredSkeletonKeys 去重避免重复生成。
        List<GeneratedFile> skeletons = BladeXModuleSkeleton.ensureFor(allGeneratedFiles, ensuredSkeletonKeys, generationContext,
                referenceProjectIndex == null ? ignored -> false : referenceProjectIndex::pathExists);
        if (!skeletons.isEmpty()) {
            allGeneratedFiles.addAll(skeletons);
            log.info("补齐模块骨架: {} 个文件", skeletons.size());
        }
        List<GeneratedFile> parentPomUpdates = buildParentPomRegistrations(
                allGeneratedFiles, ensuredSkeletonKeys, generationContext);
        if (!parentPomUpdates.isEmpty()) {
            allGeneratedFiles.addAll(parentPomUpdates);
            log.info("Parent pom registration snapshots added: {}", parentPomUpdates.size());
        }

        // 3d. 写入文件 — 阶段2: 按 plan.writeTarget 决定写盘 root
        //     ISOLATED(默认)→ outputRoot(隔离区);REAL → targetProjectRoot(默认亦隔离区, 需查重)
        WriteTarget writeTarget = WriteTarget.parse(plan.getWriteTarget());
        String writeRoot = plan.getOutputDirectory();
        log.info("Generation write target: writeTarget={}, stagingRoot={}", writeTarget.getCode(), writeRoot);

        // REAL 模式: 写盘前查重 — 类名/表名冲突即拒绝(不覆盖现有代码)
        if (writeTarget.isReal()) {
            String conflict = conflictDetector.detectNameConflicts(allGeneratedFiles);
            if (conflict != null) {
                log.warn("REAL 模式查重冲突,拒绝写盘: {}", conflict);
                logExecution(subPlan.getId(), "FILE_WRITE", "", "SKIPPED",
                        "类名/表名冲突: " + conflict, null);
                generatedFileStore.saveBatch(subPlan, plan, allGeneratedFiles, "SKIPPED");
                subPlan.setStatus(SubPlanStatus.FAILED);
                subPlan.setErrorMessage("REAL 模式查重冲突,拒绝写盘: " + conflict);
                subPlan.setCompletedAt(LocalDateTime.now());
                subPlanMapper.updateById(subPlan);
                statusNotifier.notifySubPlan(subPlan);
                return false;
            }
        }

        // REAL generation is staged outside the target project. The target root must already exist,
        // but it is not modified until every final deterministic gate passes.
        boolean targetAvailable = !writeTarget.isReal()
                || fileWriteExecutor.isRootAvailable(properties.getTargetProjectRoot());
        if (!targetAvailable) {
            generatedFileStore.saveBatch(subPlan, plan, allGeneratedFiles, "SKIPPED");
            logExecution(subPlan.getId(), "FILE_WRITE", "", "FAILED",
                    "REAL target project root is unavailable: " + properties.getTargetProjectRoot(), null);
            subPlan.setStatus(SubPlanStatus.FAILED);
            subPlan.setErrorMessage("REAL target project root is unavailable");
            subPlan.setCompletedAt(LocalDateTime.now());
            subPlanMapper.updateById(subPlan);
            statusNotifier.notifySubPlan(subPlan);
            return false;
        } else {
            List<FileWriteTask> writeTasks = allGeneratedFiles.stream()
                    .map(f -> new FileWriteTask(f.getFilePath(), f.getContent(), f.getAction()))
                    .toList();
            WriteResult writeResult = fileWriteExecutor.write(writeTasks, writeRoot);

            if (!writeResult.isSuccess()) {
                logExecution(subPlan.getId(), "FILE_WRITE", "", "ROLLED_BACK",
                        writeResult.getErrorMessage(), null);
                // 即使写入文件系统失败,我们也把生成的内容落库供 Part A 查看
                generatedFileStore.saveBatch(subPlan, plan, allGeneratedFiles, "FAILED");
                subPlan.setStatus(SubPlanStatus.FAILED);
                subPlan.setErrorMessage(writeResult.getErrorMessage());
                subPlan.setCompletedAt(LocalDateTime.now());
                subPlanMapper.updateById(subPlan);
                statusNotifier.notifySubPlan(subPlan);
                return false;
            }

            log.info("文件写入完成: {} 个文件 (root={})", writeResult.getWrittenFiles().size(), writeRoot);
            // 把生成的代码文件落库,供 Part A /api/plans/{receptionId}/files 拉取
            generatedFileStore.saveBatch(subPlan, plan, allGeneratedFiles, "CREATED");
        }

        // 3d. 编译验证（跳过—需要完整的目标项目环境）
        // BuildResult buildResult = buildVerifier.verify(getAffectedModules(allGeneratedFiles));
        // 当前阶段：跳过编译验证，记录日志
        log.info("编译验证跳过（需要完整目标项目 Maven环境）");

        // 3e. 标记子方案完成 - H3: 跨文件有未修复 ERROR 时标 COMPLETED_WITH_ERRORS(而非 COMPLETED),让 Part A 知情
        subPlan.setStatus(crossFileErrorCount > 0 ? SubPlanStatus.COMPLETED_WITH_ERRORS : SubPlanStatus.COMPLETED);
        subPlan.setCompletedAt(LocalDateTime.now());
        subPlanMapper.updateById(subPlan);

        // 3f. 回调Part A
        statusNotifier.notifySubPlan(subPlan);

        return true;
    }

    /**
     * 跨文件契约不一致的自动修复。
     *
     * <p>覆盖两类可定位到具体文件、且能以 IService 接口为契约源头重生成修复的规则:
     * <ul>
     *   <li>{@code CROSS-CONTROLLER-SERVICE-MISMATCH} — Controller 调用与 IService 不符 → 重生成 Controller;</li>
     *   <li>{@code CROSS-SERVICE-IMPL-IFACE-MISMATCH} — ServiceImpl @Override 方法 IService 未声明 → 重生成 ServiceImpl。</li>
     * </ul>
     * 两者都以 IService 接口(先于实现类生成、承载业务语义)为契约源头, 把接口源码作 context 注入 LLM 重生成。
     *
     * <p>{@code CROSS-IMPORT-CLOSURE-MISSING}(ServiceImpl import 了未生成的类, 如 RecordMapper)无法靠
     * 重生成当前文件修复(缺的是别的文件), 仅记录, 不在此重试; 留给 plan 级校验日志供人工补充生成。
     *
     * <p>复用 maxReviewRetries 限次; 每轮重生所有可修复文件后重跑校验, 直至无可修复 ERROR 或达上限。
     * 不嵌套单文件重试环路, 避免复杂度膨胀。
     */
    private void retryCrossFileContractMismatches(AiSubPlan subPlan,
                                                   List<GeneratedFile> allFiles,
                                                   Map<String, AtomicTask> taskByPath) {
        // M9: 子方案级跨文件契约修复, 以 IService 为契约源头重生成实现类(Controller/ServiceImpl)。
        // 不写回(只内存 replaceFile); 写盘在 executeSubPlan 主流程统一做。
        Set<String> FIXABLE_RULES = Set.of(
                "CROSS-CONTROLLER-SERVICE-MISMATCH", "CROSS-SERVICE-IMPL-IFACE-MISMATCH");
        runRepairLoop(allFiles, FIXABLE_RULES,
                (src, contract, task, desc) -> codeGenRouter.fixWithCrossFileContext(src, contract, task, desc),
                taskByPath::get,
                subPlan.getId(), "CROSS_FILE_FIX", "跨文件契约",
                null, null);
    }

    /**
     * M9: 三个 retry* 的共同修复循环骨架。参数化差异(规则集/fixer/task 解析/写回/subPlanId/日志)。
     *
     * <p>骨架: 循环 maxReviewRetries -> validate -> 过滤 isError&&规则集 -> 空则完成 -> 达上限失败 ->
     * 按 sourceFilePath 分组 -> findFileByPath(source+contract) + taskResolver -> fixer 修复 ->
     * passesConventionAfterFix + replaceFile + writeBack(可选) + logExecution。
     *
     * @param files        待校验文件(会被 replaceFile 修改)
     * @param rules        可修复规则集
     * @param fixer        修复函数(sourceFile, contractCode, task, desc) -> GenerationResult
     * @param taskResolver 从 sourcePath 解析 task(返回 null 则跳过该 source)
     * @param subPlanId    日志用(子方案级=subPlan.getId(); plan 级=null)
     * @param stage        日志 stage("CROSS_FILE_FIX"/"PLAN_CROSS_FIX")
     * @param label        日志标签("跨文件契约"/"Entity↔DDL"/"VO↔Entity")
     * @param writeBack    写回函数(plan 级: persistSingleFile+updateDB; 子方案级: null-只内存 replaceFile)
     * @param plan         writeBack 用(plan 级; 子方案级 null)
     */
    private void runRepairLoop(List<GeneratedFile> files, Set<String> rules,
                                RepairFixer fixer, java.util.function.Function<String, AtomicTask> taskResolver,
                                Long subPlanId, String stage, String label,
                                java.util.function.BiConsumer<AiPlan, GeneratedFile> writeBack, AiPlan plan) {
        for (int attempt = 0; attempt <= maxReviewRetries; attempt++) {
            List<CrossFileValidator.ContractIssue> issues = crossFileValidator.validate(files);
            List<CrossFileValidator.ContractIssue> fixable = issues.stream()
                    .filter(i -> i.isError() && rules.contains(i.rule))
                    .toList();
            if (fixable.isEmpty()) {
                if (attempt > 0) {
                    log.info("{} 修复完成: 第 {} 次重试后无 ERROR", label, attempt);
                    logExecution(subPlanId, stage, "", "SUCCESS",
                            label + " 修复完成 (" + attempt + " 次重试)", null);
                }
                return;
            }
            if (attempt == maxReviewRetries) {
                String summary = label + " 不一致, 已达最大重试次数 (" + (maxReviewRetries + 1)
                        + ") 仍未修复: " + fixable.size() + " 项; "
                        + fixable.stream().map(Object::toString).limit(5)
                                .reduce((a, b) -> a + " | " + b).orElse("");
                log.warn(summary);
                logExecution(subPlanId, stage, "", "FAILED", summary, null);
                return;
            }
            log.info("{} 不一致 (第 {}/{} 次重试): {} 项",
                    label, attempt + 1, maxReviewRetries + 1, fixable.size());

            // 按"需重生成的文件(sourceFilePath)"分组: 同一文件多个问题合并为一次重生成
            Map<String, List<CrossFileValidator.ContractIssue>> bySource = new LinkedHashMap<>();
            for (CrossFileValidator.ContractIssue err : fixable) {
                bySource.computeIfAbsent(err.sourceFilePath, k -> new ArrayList<>()).add(err);
            }
            for (Map.Entry<String, List<CrossFileValidator.ContractIssue>> entry : bySource.entrySet()) {
                String sourcePath = entry.getKey();
                List<CrossFileValidator.ContractIssue> errs = entry.getValue();
                GeneratedFile sourceFile = findFileByPath(files, sourcePath);
                GeneratedFile contractFile = findFileByPath(files, errs.get(0).contractFilePath);
                if (sourceFile == null || contractFile == null) {
                    log.warn("{} 修复跳过: 无法定位文件 (source={}, contract={})",
                            label, sourcePath, errs.get(0).contractFilePath);
                    continue;
                }
                AtomicTask task = taskResolver.apply(sourcePath);
                if (task == null) {
                    log.warn("{} 修复跳过: 无法解析 task: {}", label, sourcePath);
                    continue;
                }
                String mergedDesc = errs.stream().map(e -> e.message)
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                GenerationResult fix = fixer.fix(sourceFile, contractFile.getContent(), task, mergedDesc);
                if (fix.isSuccess()) {
                    GeneratedFile newFile = fix.getGeneratedFiles().get(0);
                    if (!passesConventionAfterFix(newFile)) {
                        log.warn("{} 修复被拒(引入 Layer1 违规), 保留原文件: path={}", label, sourcePath);
                        continue;
                    }
                    replaceFile(files, sourcePath, newFile);
                    if (writeBack != null) {
                        writeBack.accept(plan, newFile);
                    }
                    logExecution(subPlanId, stage, sourcePath,
                            "CREATED", label + " 修复: " + mergedDesc, null);
                } else {
                    log.warn("{} 修复 LLM 失败: {}", label, fix.getErrorMessage());
                }
            }
        }
    }

    /** M9: 修复函数接口(参数化 fixWithCrossFileContext/fixEntityWithDdl/fixVoWithEntity) */
    @FunctionalInterface
    private interface RepairFixer {
        GenerationResult fix(GeneratedFile sourceFile, String contractCode, AtomicTask task, String issueDescription);
    }

    private GeneratedFile findFileByPath(List<GeneratedFile> files, String path) {
        if (path == null) return null;
        for (GeneratedFile f : files) {
            if (path.equals(f.getFilePath())) return f;
        }
        return null;
    }

    private void replaceFile(List<GeneratedFile> files, String path, GeneratedFile newFile) {
        for (int i = 0; i < files.size(); i++) {
            if (path.equals(files.get(i).getFilePath())) {
                files.set(i, newFile);
                return;
            }
        }
    }

    /** 校验修复后的文件是否仍通过 Layer1 规范校验(防止 LLM 修复时丢注解/继承等引入单文件违规)。
     *  返回 true 表示通过(可接受修复),false 表示引入 ERROR(应拒绝修复,保留原文件)。
     *  修复循环(H2)在 replaceFile 前调用,避免"修一个跨文件问题、引入一个单文件硬错误"。 */
    private boolean passesConventionAfterFix(GeneratedFile fixedFile) {
        try {
            ValidationResult vr = conventionValidator.validate(fixedFile);
            boolean hasError = vr.getIssues().stream()
                    .anyMatch(i -> "ERROR".equalsIgnoreCase(i.getSeverity()));
            if (hasError) {
                log.warn("修复后 Layer1 校验不通过(引入单文件违规), 拒绝该次修复: path={}, issues={}",
                        fixedFile.getFilePath(),
                        vr.getIssues().stream().map(Object::toString).limit(5)
                                .reduce((a, b) -> a + " | " + b).orElse(""));
            }
            return !hasError;
        } catch (Exception e) {
            log.warn("修复后 Layer1 校验异常, 保守拒绝: path={}, err={}", fixedFile.getFilePath(), e.getMessage());
            return false;
        }
    }

    /**
     * 阶段2: REAL 模式查重 — 检测本次生成的类名/表名是否与目标项目已有冲突。
     *
     * <p>从每个 GeneratedFile 的路径推类名(文件名去 .java)、从内容提 @TableName,
     * 调 ExistingProjectIndex 查询。任一命中返回冲突描述(用于拒绝写盘),无冲突返回 null。
     *
     * <p>索引未扫描时自动 scan(false)(用缓存或触发);扫描失败则降级"不查重"返回 null + WARN,
     * 避免阻塞主流程(但记日志供排查)。
     */
    /**
     * 阶段2增强: 找参考项目同类代码的结构化摘要。
     *
     * <p>把 task 的 TaskType 映射到 ClassType,从参考项目索引找同类,提取结构化摘要。
     * 未设参考项目/未扫描/找不到同类/解析失败 → 返回 null(降级为不注入参考)。
     */
    /** 适配摘要 per-plan 缓存(planId -> 摘要;空串占位表示"提取过但为 null")。
     *  H7: 原实例字段在单例 Bean 上被并发 plan 互相覆盖,改为 per-plan key 隔离。 */
    private final java.util.Map<Long, String> adaptationSummaryByPlan = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String cachedReferencePath;

    /**
     * 阶段2增强: 获取参考项目适配摘要(带 per-plan 缓存)。
     * 参考项目路径变化时清整个缓存;否则同一 plan 复用(一个 plan 内只提取一次),不同 plan 互不干扰。
     */
    private String getAdaptationSummary(Long planId) {
        if (!referenceProjectIndex.isReady()) {
            log.debug("生成: 参考项目未就绪, 不注入适配摘要(用默认 BladeX 规范)");
            return null;
        }
        String currentPath = referenceProjectIndex.getPath();
        // 参考项目路径变化时清整个缓存(所有 plan 的摘要失效)
        if (cachedReferencePath == null || !currentPath.equals(cachedReferencePath)) {
            adaptationSummaryByPlan.clear();
            cachedReferencePath = currentPath;
        }
        String cached = adaptationSummaryByPlan.get(planId);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String summary = referenceProjectIndex.buildAdaptationSummary();
        adaptationSummaryByPlan.put(planId, summary == null ? "" : summary);
        if (summary != null) {
            log.info("生成: 提取参考项目适配摘要, planId={}, 长度={}", planId, summary.length());
        }
        return summary;
    }

    private String findReferenceSummary(AtomicTask task) {
        if (!referenceProjectIndex.isReady()) {
            log.debug("REAL 生成: 参考项目未就绪, 不注入参考");
            return null;
        }
        ClassType targetType = mapTaskTypeToClassType(task);
        if (targetType == null) {
            log.debug("REAL 生成: task 类型 {} 无对应 ClassType, 不注入参考", task.getType());
            return null;
        }
        // 参考项目独立于生成目标,不排除同模块(同模块恰是风格最相关的参考)
        var ref = referenceProjectIndex.findBestReferenceExample(targetType, task.getModuleName(), task.getEntityName());
        if (ref.isEmpty()) {
            log.debug("REAL 生成: 参考项目中无 {} 类型的参考代码", targetType);
            return null;
        }
        IndexedClassInfo selected = ref.get();
        int score = referenceProjectIndex.scoreReferenceCandidate(selected, task.getModuleName(), task.getEntityName());
        task.setSelectedReferenceClass(selected.simpleName());
        task.setSelectedReferenceModule(selected.module());
        task.setSelectedReferencePath(selected.relativePath());
        task.setReferenceScore(score);
        task.setReferenceReason(score >= 100 ? "same module/type" : score >= 30 ? "same entity/type" : "project-level type fallback");
        logExecution(task.getSourceSubPlanId(), "REFERENCE_SELECTION", task.getTargetPath(), "SUCCESS",
                "selected=" + selected.simpleName() + ", module=" + selected.module()
                        + ", score=" + score + ", reason=" + task.getReferenceReason(), null);
        String summary = referenceProjectIndex.buildStructuredSummary(ref.get().relativePath());
        if (summary == null) {
            log.warn("REAL 生成: 参考代码摘要失败: {}", ref.get().relativePath());
            return null;
        }
        log.info("REAL 生成注入参考: task={}, 参考类={}, 摘要长度={}",
                task.getType(), ref.get().simpleName(), summary.length());
        return summary;
    }

    /**
     * TaskType → ClassType 映射(只有 Java 类类型才有参考价值,DDL/XML/Excel/OTHER 不参考)。
     * ServiceImpl 任务(路径含 impl)单独映射到 SERVICE_IMPL,避免参考接口无方法体。
     */
    private ClassType mapTaskTypeToClassType(AtomicTask task) {
        if (task == null || task.getType() == null) return null;
        // ServiceImpl 任务:路径含 service/impl/ 或类名含 ServiceImpl → 找 SERVICE_IMPL(有方法体可参考)
        String targetPath = task.getTargetPath();
        if (targetPath != null && (targetPath.contains("service/impl/") || targetPath.contains("ServiceImpl"))) {
            return ClassType.SERVICE_IMPL;
        }
        return switch (task.getType()) {
            case STANDARD_CRUD_ENTITY -> ClassType.ENTITY;
            case STANDARD_CRUD_CONTROLLER -> ClassType.CONTROLLER;
            case STANDARD_CRUD_SERVICE, COMPLEX_BUSINESS_SERVICE -> ClassType.SERVICE;
            case CUSTOM_MAPPER -> ClassType.MAPPER;
            case WRAPPER -> ClassType.WRAPPER;
            case FEIGN_CLIENT, FEIGN_PROVIDER -> ClassType.FEIGN;
            case EXCEL_IMPORT_EXPORT -> ClassType.EXCEL;
            default -> null; // DDL_STATEMENT / MAPPER_XML / NACOS_CONFIG / EXCEL_IMPORT_EXPORT / OTHER 不参考
        };
    }

    /**
     * 从子方案解析出 N 个原子任务。
     *
     * <p>策略: 按 <b>子方案标题里的关键字</b> 决定该子方案要产出哪几个独立文件,
     * 然后从子方案内容中提取实体名/模块名/包路径,组合出文件路径。
     * 每个 AtomicTask 只对应 <b>一个</b> 目标文件,LLM 一次只生成一个文件,避免多文件拆分歧义。
     */
    private List<GeneratedFile> buildParentPomRegistrations(
            List<GeneratedFile> generated, Set<String> ensuredKeys, GenerationContext context) {
        if (referenceProjectIndex == null) return List.of();
        List<GeneratedFile> result = new ArrayList<>();
        boolean hasApi = generated.stream().anyMatch(file -> "API".equals(BladeXModuleLayout.sideOfPath(file.getFilePath())));
        boolean hasImpl = generated.stream().anyMatch(file -> "IMPL".equals(BladeXModuleLayout.sideOfPath(file.getFilePath())));
        if (hasApi && !referenceProjectIndex.pathExists(BladeXModuleLayout.apiPomPath(context))
                && ensuredKeys.add(context.identity().moduleName() + ":PARENT_API")) {
            String content = referenceProjectIndex.buildParentPomWithModule(
                    "blade-service-api/pom.xml", context.identity().apiModuleName());
            if (content != null) result.add(GeneratedFile.create(TaskType.OTHER, "blade-service-api/pom.xml", content));
        }
        if (hasImpl && !referenceProjectIndex.pathExists(BladeXModuleLayout.implPomPath(context))
                && ensuredKeys.add(context.identity().moduleName() + ":PARENT_IMPL")) {
            String content = referenceProjectIndex.buildParentPomWithModule(
                    "blade-service/pom.xml", context.identity().serviceModuleName());
            if (content != null) result.add(GeneratedFile.create(TaskType.OTHER, "blade-service/pom.xml", content));
        }
        return result;
    }

    SubPlanTaskCompilation parseAtomicTasks(AiSubPlan subPlan, GenerationContext generationContext) {
        if (generationContext.contractV2()) {
            return parseCanonicalAtomicTasks(subPlan, generationContext);
        }
        String content = subPlan.getPlanContent();
        if (content == null || content.isBlank()) {
            return new SubPlanTaskCompilation(List.of(), List.of(), List.of());
        }

        String title = subPlan.getTitle() == null ? "" : subPlan.getTitle();
        PlanArtifactCompilation artifactCompilation = planArtifactCompiler.compile(
                subPlan.getId(), title, content, generationContext);
        ReferenceArtifactBinder.BindingResult binding = referenceArtifactBinder.bind(
                artifactCompilation.artifacts(), generationContext, referenceProjectIndex);
        List<PlannedArtifact> artifacts = new ArrayList<>(binding.artifacts());
        List<PlanCompilationIssue> issues = new ArrayList<>(artifactCompilation.issues());
        issues.addAll(binding.issues());

        // Keep the proven standard CRUD classifier as a compatibility fallback. Structured artifacts supplement
        // it and can explicitly prohibit defaults, but do not replace stable layer classification.
        String entityName = generationContext.identity().entityName();
        String moduleName = generationContext.identity().moduleName();
        String fullContext = "== REVIEWED SUB-PLAN CONTEXT ==\n" + content;
        SubPlanLayerClassifier.Classification classification = SubPlanLayerClassifier.classify(title, content);
        List<AtomicTask> tasks = new ArrayList<>();
        boolean hasExactEntityArtifact = artifacts.stream().anyMatch(artifact ->
                artifact.kind() == ArtifactKind.ENTITY && entityName.equals(artifact.name()) && !artifact.prohibited());
        boolean hasExactPrimaryController = artifacts.stream().anyMatch(artifact ->
                artifact.kind() == ArtifactKind.CONTROLLER
                        && (entityName + "Controller").equals(artifact.name()) && !artifact.prohibited());
        boolean hasExplicitExcelImplementation = artifacts.stream().anyMatch(artifact ->
                artifact.kind() == ArtifactKind.EXCEL_MODEL && artifact.moduleSide() == ModuleSide.IMPL
                        && !artifact.prohibited());

        if (classification.ddl()) {
            tasks.add(buildTask(TaskType.DDL_STATEMENT,
                    title + " - generate reviewed database DDL\n\n" + fullContext,
                    BladeXModuleLayout.ddlPath(generationContext), entityName, moduleName));
        }
        if (classification.entity() && !hasExactEntityArtifact) {
            tasks.add(buildTask(TaskType.STANDARD_CRUD_ENTITY,
                    title + " - generate exact Entity class (" + entityName + ")\n\n" + fullContext,
                    BladeXModuleLayout.entityPath(generationContext, entityName), entityName, moduleName));
        }
        if (classification.vo()) {
            for (String suffix : new String[]{"QVO", "IVO", "UVO", "VO", "EVO"}) {
                String expectedVoName = entityName + suffix;
                boolean hasExactVoArtifact = artifacts.stream().anyMatch(artifact ->
                        artifact.kind() == ArtifactKind.VO && expectedVoName.equals(artifact.name())
                                && !artifact.prohibited());
                if (hasExactVoArtifact) continue;
                tasks.add(buildTask(TaskType.OTHER,
                        title + " - generate exact " + entityName + suffix + " class\n\n"
                                + voInstructions(suffix, entityName) + "\n\n" + fullContext,
                        BladeXModuleLayout.voPath(generationContext, entityName, suffix), entityName, moduleName));
            }
        }
        if (classification.mapper()) {
            tasks.add(buildTask(TaskType.CUSTOM_MAPPER,
                    title + " - generate exact " + entityName + "Mapper interface\n\n" + fullContext,
                    BladeXModuleLayout.mapperJavaPath(generationContext, entityName), entityName, moduleName));
            tasks.add(buildTask(TaskType.MAPPER_XML,
                    title + " - generate exact " + entityName + "Mapper.xml\n\n" + fullContext,
                    BladeXModuleLayout.mapperXmlPath(generationContext, entityName), entityName, moduleName));
        }
        if (classification.service()) {
            tasks.add(buildTask(TaskType.STANDARD_CRUD_SERVICE,
                    title + " - generate exact I" + entityName + "Service interface\n\n"
                            + serviceInterfaceInstructions(entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.serviceInterfacePath(generationContext, entityName), entityName, moduleName));
            tasks.add(buildTask(TaskType.STANDARD_CRUD_SERVICE,
                    title + " - generate exact " + entityName + "ServiceImpl implementation\n\n"
                            + serviceImplInstructions(entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.serviceImplPath(generationContext, entityName), entityName, moduleName));
        }
        boolean hasExplicitCrossModuleController = artifacts.stream()
                .anyMatch(artifact -> artifact.kind() == ArtifactKind.CONTROLLER
                        && artifact.ownerModule() != null
                        && !artifact.ownerModule().equalsIgnoreCase(generationContext.identity().moduleName()));
        if (classification.wrapper() && !hasExplicitCrossModuleController) {
            tasks.add(buildTask(TaskType.WRAPPER,
                    title + " - generate exact " + entityName + "Wrapper converter\n\n"
                            + wrapperInstructions(entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.wrapperPath(generationContext, entityName), entityName, moduleName));
        }
        if (classification.controller() && !hasExplicitCrossModuleController && !hasExactPrimaryController) {
            tasks.add(buildTask(TaskType.STANDARD_CRUD_CONTROLLER,
                    title + " - generate exact " + entityName + "Controller class\n\n"
                            + controllerInstructions(entityName, moduleName) + "\n\n" + fullContext,
                    BladeXModuleLayout.controllerPath(generationContext, entityName), entityName, moduleName));
        }
        if (classification.excel() && !hasExplicitExcelImplementation) {
            tasks.add(buildTask(TaskType.EXCEL_IMPORT_EXPORT,
                    title + " - generate exact " + entityName + "Excel class\n\n"
                            + excelInstructions(entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.excelPath(generationContext, entityName), entityName, moduleName));
        }
        if (classification.feign()) {
            tasks.add(buildTask(TaskType.FEIGN_CLIENT,
                    title + " - generate exact Feign client interface I" + entityName + "Client (entity: " + entityName + ")\n\n"
                            + feignInstructions(entityName, moduleName) + "\n\n" + fullContext,
                    BladeXModuleLayout.feignPath(generationContext, entityName), entityName, moduleName));
        }

        Set<String> prohibitedPaths = artifacts.stream()
                .filter(PlannedArtifact::prohibited)
                .map(PlannedArtifact::targetPath)
                .filter(Objects::nonNull)
                .map(this::normalizePlanPath)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!prohibitedPaths.isEmpty()) {
            tasks.removeIf(task -> prohibitedPaths.contains(normalizePlanPath(task.getTargetPath())));
        }

        // Compile explicit reviewed deliverables independently of the standard classifier. Failed bindings retain
        // their artifact/issue but produce no task, so downstream generation cannot guess a path.
        Set<String> existingPaths = tasks.stream().map(AtomicTask::getTargetPath)
                .filter(Objects::nonNull).map(this::normalizePlanPath)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (PlannedArtifact artifact : artifacts) {
            if (artifact.prohibited() || artifact.targetPath() == null || artifact.targetPath().isBlank()) continue;
            String normalizedPath = normalizePlanPath(artifact.targetPath());
            if (existingPaths.contains(normalizedPath)) continue;
            AtomicTask task = buildArtifactTask(artifact, title, fullContext, generationContext, issues);
            if (task != null && existingPaths.add(normalizedPath)) tasks.add(task);
        }

        // A real fallback is retained only when the plan contains no explicit structured deliverable or compilation
        // error. When binding failed, generating arbitrary Code.java would hide the plan defect and add noise.
        if (tasks.isEmpty() && artifactCompilation.artifacts().isEmpty() && issues.isEmpty()) {
            tasks.add(buildTask(TaskType.OTHER, title + "\n\n" + fullContext,
                    "ai-generated/subplan-" + subPlan.getId() + "/Code.java", entityName, moduleName));
            log.info("Sub-plan {} matched no deterministic or structured artifact; using generic fallback", title);
        }

        Set<String> seen = new LinkedHashSet<>();
        List<AtomicTask> deduped = new ArrayList<>();
        for (AtomicTask task : tasks) {
            task.setGenerationContext(generationContext);
            if (task.getModuleName() == null || task.getModuleName().isBlank()) {
                task.setModuleName(generationContext.identity().moduleName());
            }
            if (task.getTargetPath() != null && seen.add(normalizePlanPath(task.getTargetPath()))) {
                deduped.add(task);
            }
        }
        log.info("Sub-plan [{}] compiled {} atomic tasks, {} structured artifacts, {} issues: {}",
                title, deduped.size(), artifacts.size(), issues.size(),
                deduped.stream().map(AtomicTask::getTargetPath).toList());
        return new SubPlanTaskCompilation(deduped, artifacts, issues);
    }

    private SubPlanTaskCompilation parseCanonicalAtomicTasks(AiSubPlan subPlan,
                                                               GenerationContext generationContext) {
        List<String> deliverableIds;
        try {
            deliverableIds = subPlan.getDeliverableIdsJson() == null
                    ? List.of() : objectMapper.readValue(subPlan.getDeliverableIdsJson(), new TypeReference<List<String>>() { });
        } catch (JsonProcessingException error) {
            return new SubPlanTaskCompilation(List.of(), List.of(), List.of(
                    PlanCompilationIssue.error(subPlan.getId(), "CANONICAL-DELIVERABLE-JSON-INVALID",
                            "deliverableIds", error.getMessage())));
        }
        PlanArtifactCompilation compilation = planArtifactCompiler.compileCanonical(
                subPlan.getId(), deliverableIds, generationContext);
        ReferenceArtifactBinder.BindingResult binding = referenceArtifactBinder.bind(
                compilation.artifacts(), generationContext, referenceProjectIndex);
        List<PlanCompilationIssue> issues = new ArrayList<>(compilation.issues());
        issues.addAll(binding.issues());
        List<PlannedArtifact> artifacts = new ArrayList<>(binding.artifacts());
        String title = subPlan.getTitle() == null ? "" : subPlan.getTitle();
        String content = subPlan.getPlanContent() == null ? "" : subPlan.getPlanContent();
        String fullContext = "== CANONICAL PLAN CONTRACT V2 ==\n"
                + generationContext.domainContract().describeForPrompt()
                + "\n== REVIEWED SUB-PLAN EXPLANATION ==\n" + content;
        List<AtomicTask> tasks = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PlannedArtifact artifact : artifacts) {
            if (artifact.prohibited() || artifact.targetPath() == null || artifact.targetPath().isBlank()) continue;
            AtomicTask task = buildArtifactTask(artifact, title, fullContext, generationContext, issues);
            if (task == null || !seen.add(normalizePlanPath(task.getTargetPath()))) continue;
            task.setGenerationContext(generationContext);
            tasks.add(task);
        }
        if (tasks.isEmpty() && issues.isEmpty()) {
            issues.add(PlanCompilationIssue.error(subPlan.getId(), "CANONICAL-TASKS-EMPTY",
                    "deliverableIds", "Canonical deliverables produced no atomic tasks"));
        }
        log.info("Canonical v2 sub-plan [{}] compiled {} tasks from deliverables {}",
                title, tasks.size(), deliverableIds);
        return new SubPlanTaskCompilation(tasks, artifacts, issues);
    }

    private AtomicTask buildArtifactTask(PlannedArtifact artifact, String title, String fullContext,
                                         GenerationContext generationContext, List<PlanCompilationIssue> issues) {
        TaskType type = switch (artifact.kind()) {
            case DTO, VO, OTHER -> TaskType.OTHER;
            case FEIGN_INTERFACE -> TaskType.FEIGN_CLIENT;
            case FEIGN_PROVIDER -> TaskType.FEIGN_PROVIDER;
            case CONTROLLER -> TaskType.STANDARD_CRUD_CONTROLLER;
            case ENTITY -> TaskType.STANDARD_CRUD_ENTITY;
            case SERVICE_INTERFACE, SERVICE_IMPL -> TaskType.STANDARD_CRUD_SERVICE;
            case WRAPPER -> TaskType.WRAPPER;
            case MAPPER -> TaskType.CUSTOM_MAPPER;
            case MAPPER_XML -> TaskType.MAPPER_XML;
            case CONFIG -> TaskType.NACOS_CONFIG;
            case EXCEL_MODEL, EXCEL_UTILITY, EXCEL_LISTENER -> TaskType.EXCEL_IMPORT_EXPORT;
            case DDL -> TaskType.DDL_STATEMENT;
        };
        String artifactEntity = artifactEntityName(artifact, generationContext);
        StringBuilder description = new StringBuilder();
        description.append(title).append(" - ")
                .append(artifact.action()).append(' ')
                .append(artifact.kind()).append(' ')
                .append(artifact.name()).append('\n');
        description.append("Exact target path: ").append(artifact.targetPath()).append('\n');
        description.append("Owning module: ").append(artifact.ownerModule()).append('\n');
        if (artifact.declaredPackage() != null) {
            description.append("Exact package: ").append(artifact.declaredPackage()).append('\n');
        }
        description.append(artifactInstructions(artifact)).append("\n\n").append(fullContext);

        boolean crossModuleModification = (artifact.action() == ArtifactAction.MODIFY
                || artifact.action() == ArtifactAction.EXTEND)
                && artifact.ownerModule() != null
                && !artifact.ownerModule().equalsIgnoreCase(generationContext.identity().moduleName())
                && artifact.kind() != ArtifactKind.DDL;
        if (crossModuleModification) {
            String existingSource = referenceProjectIndex == null
                    ? null : referenceProjectIndex.readSourceContent(artifact.targetPath());
            if (existingSource == null || existingSource.isBlank()) {
                issues.add(PlanCompilationIssue.error(artifact.subPlanId(), "PLAN-TARGET-SOURCE-UNAVAILABLE",
                        artifact.targetPath(), "Bound cross-module target source could not be read; generation skipped"));
                return null;
            }
            description.append("\n\n== AUTHORITATIVE EXISTING FILE ==\n```java\n")
                    .append(existingSource)
                    .append("\n```\nPreserve unrelated logic and public contracts. Apply only the reviewed extension. ")
                    .append("Return the complete modified source file; never replace it with a new simplified CRUD class.");
        }
        return buildTask(type, description.toString(), artifact.targetPath(), artifactEntity,
                artifact.ownerModule() == null ? generationContext.identity().moduleName() : artifact.ownerModule());
    }

    private String artifactInstructions(PlannedArtifact artifact) {
        return switch (artifact.kind()) {
            case DTO -> "Generate the exact DTO class named " + artifact.name()
                    + "; preserve every reviewed request/response field and do not rename the class.";
            case FEIGN_INTERFACE -> "Generate the exact Feign contract interface " + artifact.name()
                    + "; do not turn a provider implementation into a second client interface.";
            case FEIGN_PROVIDER -> "Generate the concrete service-side Feign provider class " + artifact.name()
                    + "; it must implement the reviewed client contract and must not declare @FeignClient.";
            case CONTROLLER -> "Generate or modify only the exact controller " + artifact.name()
                    + "; framework base classes mentioned in prose are dependencies, never deliverables.";
            case EXCEL_MODEL -> "Generate only the explicitly reviewed Excel/EVO artifact " + artifact.name() + ".";
            case EXCEL_UTILITY -> "Generate the exact EasyExcel utility " + artifact.name()
                    + " with the reviewed export and template-download operations; do not emit a data model instead.";
            case EXCEL_LISTENER -> "Generate the concrete EasyExcel ReadListener " + artifact.name()
                    + " for the reviewed import model and transactional service import contract.";
            case ENTITY -> "Generate or modify only the exact entity " + artifact.name() + ".";
            case MAPPER -> "Generate only the exact Mapper interface " + artifact.name() + " using canonical entity and VO types.";
            case MAPPER_XML -> "Generate only the Mapper XML for " + artifact.name() + " and keep namespace/result mappings type-closed.";
            case CONFIG -> "Generate only the exact BladeX configuration file " + artifact.name() + ".";
            case SERVICE_INTERFACE, SERVICE_IMPL -> "Generate or modify only the exact service type " + artifact.name() + ".";
            case WRAPPER -> "Generate the exact wrapper " + artifact.name()
                    + "; extend BaseEntityWrapper and provide build(), entityVO(), and entity() conversions.";
            case DDL -> "Generate only the reviewed DDL change for module " + artifact.ownerModule() + ".";
            default -> "Generate only the exact reviewed artifact " + artifact.name() + ".";
        };
    }

    private String artifactEntityName(PlannedArtifact artifact, GenerationContext generationContext) {
        String name = artifact.name();
        String canonical = generationContext.identity().entityName();
        boolean currentModule = artifact.ownerModule() == null
                || artifact.ownerModule().equalsIgnoreCase(generationContext.identity().moduleName());
        if (currentModule && (name.contains(canonical)
                || artifact.kind() == ArtifactKind.EXCEL_UTILITY
                || artifact.kind() == ArtifactKind.EXCEL_LISTENER)) {
            return canonical;
        }
        return switch (artifact.kind()) {
            case FEIGN_INTERFACE, FEIGN_PROVIDER -> stripClientName(name);
            case WRAPPER -> stripSuffix(name, "Wrapper");
            case CONTROLLER -> stripSuffix(name, "Controller");
            case SERVICE_INTERFACE -> stripSuffix(stripLeadingInterfacePrefix(name), "Service");
            case SERVICE_IMPL -> stripSuffix(name, "ServiceImpl");
            case DTO -> stripSuffix(name, "DTO");
            case EXCEL_MODEL -> name.endsWith("EVO") ? stripSuffix(name, "EVO")
                    : name.endsWith("ExcelUtil") ? stripSuffix(name, "ExcelUtil") : stripSuffix(name, "Excel");
            case EXCEL_UTILITY -> name.endsWith("ExcelUtil") ? stripSuffix(name, "ExcelUtil")
                    : name;
            case EXCEL_LISTENER -> name.endsWith("ExcelReadListener") ? stripSuffix(name, "ExcelReadListener")
                    : stripSuffix(name, "ReadListener");
            default -> name;
        };
    }

    private String stripLeadingInterfacePrefix(String name) {
        return name != null && name.length() > 1 && name.charAt(0) == 'I' && Character.isUpperCase(name.charAt(1))
                ? name.substring(1) : name;
    }

    private String stripSuffix(String value, String suffix) {
        return value != null && suffix != null && value.endsWith(suffix) && value.length() > suffix.length()
                ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private String normalizePlanPath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    record SubPlanTaskCompilation(
            List<AtomicTask> tasks,
            List<PlannedArtifact> artifacts,
            List<PlanCompilationIssue> issues) {
        SubPlanTaskCompilation {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    private AtomicTask buildTask(TaskType type, String description, String targetPath, String entityName, String moduleName) {
        AtomicTask t = new AtomicTask();
        t.setType(type);
        t.setTaskDescription(description);
        t.setTargetPath(targetPath);
        String normalizedPath = targetPath == null ? "" : targetPath.replace('\\', '/');
        int slash = normalizedPath.lastIndexOf('/');
        String fileName = slash >= 0 ? normalizedPath.substring(slash + 1) : normalizedPath;
        int dot = fileName.lastIndexOf('.');
        t.setExpectedClassName(dot > 0 ? fileName.substring(0, dot) : fileName);
        // 把推导出的实体名/模块名带入任务,供 PromptBuilder 替换占位符 {Entity}/{Name}/{module}
        t.setEntityName(entityName);
        t.setModuleName(moduleName);
        return t;
    }

    /** 提取实体名(类名),例如 "Order" / "Product" */
    private String stripClientName(String className) {
        String value = className.startsWith("I") && className.length() > 1 && Character.isUpperCase(className.charAt(1))
                ? className.substring(1) : className;
        return value.endsWith("Client") ? value.substring(0, value.length() - "Client".length()) : value;
    }

    private String extractEntityName(String content) {
        // 优先匹配显式声明 + 反引号 + 路径,避免被 "Entity extends BaseEntity" 这种范例字面值误匹配
        String[] pats = new String[]{
                // 完整词 + 可选标点 + 类名: "实体名 Order" / "类名: Order" / "实体类名:Order"
                "(?:实体类名|实体名|类名|Entity\\s*名)\\s*[:：]?\\s*`?([A-Z][a-zA-Z0-9]*)`?",
                // 旧格式: "实体: Order" (实体后必须紧跟冒号或空格,但不能紧跟 "名" 等其他字)
                "实体[\\s:：]+`?([A-Z][a-zA-Z0-9]*)`?",
                "class\\s+([A-Z][a-zA-Z0-9]*)\\s+extends\\s+BaseEntity",
                // 反引号或代码引用: `Order` Entity
                "`([A-Z][a-zA-Z0-9]*)`\\s*(?:Entity|实体)",
                // 路径中的类名: org/springblade/order/entity/Order.java
                "/entity/([A-Z][a-zA-Z0-9]*)\\.java",
                // 兜底: Foo extends BaseEntity (排除 "Entity" 这种字面值)
                "([A-Z][a-zA-Z0-9]*)\\s+extends\\s+BaseEntity",
        };
        for (String pat : pats) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pat).matcher(content);
            while (m.find()) {
                String name = m.group(1);
                if (!name.equals("Entity") && !name.equals("Base") && !name.equals("Abstract")) {
                    return name;
                }
            }
        }
        // 从类名后缀倒推 — 但要过滤掉 "Entity"/"BaseEntity" 这种通用词
        for (String suffix : new String[]{"Controller", "ServiceImpl", "Service", "Mapper", "Wrapper", "Excel", "EVO", "QVO", "IVO", "UVO", "VO"}) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\\b([A-Z][a-z]\\w*)" + suffix + "\\b").matcher(content);
            while (m.find()) {
                String name = m.group(1);
                // 排除 "Base"/"Entity" 这种泛词
                if (!name.equals("Base") && !name.equals("Entity") && !name.equals("Abstract")) {
                    return name;
                }
            }
        }
        // 从 IXxxService / IXxxClient 倒推
        java.util.regex.Matcher iface = java.util.regex.Pattern.compile(
                "\\bI([A-Z][a-z]\\w*)(?:Service|Client)\\b").matcher(content);
        while (iface.find()) {
            String name = iface.group(1);
            if (!name.equals("Base") && !name.equals("Entity")) return name;
        }
        // 兜底: 用 blade_xxx 表名推导
        java.util.regex.Matcher t = java.util.regex.Pattern.compile("blade_(\\w+)").matcher(content);
        if (t.find()) {
            String tbl = t.group(1);
            StringBuilder sb = new StringBuilder();
            for (String part : tbl.split("_")) {
                if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            return sb.length() > 0 ? sb.toString() : "Entity";
        }
        return "Entity";
    }

    /** 提取模块名,例如 "order" / "product" */
    private String extractModuleName(String content, String entityName) {
        // 优先匹配 "模块: order" / "module: order" / "包路径: org.springblade.order.xxx"
        for (String pat : new String[]{
                "(?:模块名?|module)[:：\\s]+([a-z][a-z0-9_]*)",
                "org\\.springblade\\.([a-z][a-z0-9_]*)\\.(?:entity|service|controller|mapper|wrapper|excel|feign|vo)",
                // 路径: blade-order/...
                "blade-([a-z][a-z0-9_-]*)/src",
                // 表名 blade_xxx → xxx
                "blade_([a-z][a-z0-9_]*)",
        }) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pat).matcher(content);
            if (m.find()) {
                String mod = m.group(1);
                if (!mod.equals("core") && !mod.equals("entity")) return mod;
            }
        }
        // 兜底: 用 entityName 小写
        return entityName.toLowerCase();
    }

    // ─── 各文件类型的具体指令 ───

    private String voInstructions(String suffix, String entityName) {
        return switch (suffix) {
            case "QVO" -> entityName + "QVO 是查询参数对象, 含可筛选字段(如 " + entityName + " 的业务字段 + 范围字段如 startDateStart/startDateEnd), 不需要 @NotNull 校验";
            case "IVO" -> entityName + "IVO 是新增参数对象, 关键字段使用 @NotBlank/@NotNull";
            case "UVO" -> entityName + "UVO 是修改参数对象, 必须含 id 字段 + @NotNull";
            case "VO" -> entityName + "VO 是输出对象, Long 类型 id 用 @JsonSerialize(using = ToStringSerializer.class)";
            case "EVO" -> entityName + "EVO 是 Excel 导出对象, 每个字段使用 @ExcelProperty 标注列名, 类级别 @ColumnWidth @HeadRowHeight @ContentRowHeight";
            default -> "";
        };
    }

    private String serviceInterfaceInstructions(String entityName) {
        return "I" + entityName + "Service must extend BaseService<" + entityName + "> and declare every "
                + "business operation required by the reviewed plan (state changes, matching, configuration checks, "
                + "custom queries and integration operations). Do not leave the interface empty when the plan defines custom behavior.";
    }

    private String serviceImplInstructions(String entityName) {
        return entityName + "ServiceImpl must:\n"
                + "- extend BaseServiceImpl<" + entityName + "Mapper, " + entityName + ">\n"
                + "- implement I" + entityName + "Service\n"
                + "- use @Service and constructor injection\n"
                + "- implement every custom method declared by I" + entityName + "Service\n"
                + "- preserve all validation, uniqueness, state-transition and transaction rules from the reviewed plan";
    }

    private String wrapperInstructions(String entityName) {
        return entityName + "Wrapper 必须:\n"
                + "- extends BaseEntityWrapper<" + entityName + ", " + entityName + "VO>\n"
                + "- 提供 public static " + entityName + "Wrapper build() 工厂方法\n"
                + "- 覆盖 public " + entityName + "VO entityVO(" + entityName + " entity) 方法, 用 BeanUtil.copy() 拷贝";
    }

    private String controllerInstructions(String entityName, String moduleName) {
        return entityName + "Controller must:\n"
                + "- extend BladeController and use the annotation generation detected from the reference project\n"
                + "- inject I" + entityName + "Service\n"
                + "- expose every endpoint required by the reviewed plan, including custom enable/disable/match/configuration endpoints\n"
                + "- call the corresponding custom service method for business operations\n"
                + "- never replace reviewed business methods with generic save/updateById/deleteLogic calls that bypass validation\n"
                + "- keep request DTO/VO and return types aligned with the generated service contract";
    }

    private String excelInstructions(String entityName) {
        return entityName + "Excel 必须:\n"
                + "- implements Serializable, 含 serialVersionUID\n"
                + "- 类级别 @ColumnWidth(25) @HeadRowHeight(20) @ContentRowHeight(18)\n"
                + "- 每个字段加 @ExcelProperty(\"列名\")\n"
                + "- 金额字段使用 String 类型避免精度问题";
    }

    /** Feign 客户端生成指令 — 显式锚定接口名 I{Entity}Client,杜绝实体名漂移 */
    private String feignInstructions(String entityName, String moduleName) {
        return "本次必须生成 Feign 客户端接口,类名固定为 I" + entityName + "Client (实体名严格使用 " + entityName + ",不要改成 User/其他):\n"
                + "- 接口 I" + entityName + "Client + @FeignClient(value = \"blade-" + moduleName + "-service\", fallback = I" + entityName + "ClientFallback.class)\n"
                + "- 方法返回 R<" + entityName + "> 或 R<List<" + entityName + ">>,与 Controller 端点对应\n"
                + "- 只输出一个接口文件,不要输出 Fallback 和实现类(本任务只生成接口)";
    }

    /**
     * 记录执行日志
     */
    private void logPlanExecution(AiPlan plan, String stage, String filePath,
                                  String action, String reason, String validationJson) {
        AiExecutionLog logEntry = new AiExecutionLog();
        logEntry.setPlanId(plan == null ? null : plan.getId());
        populateAndInsertExecutionLog(logEntry, null, stage, filePath, action, reason, validationJson);
    }

    private void logExecution(Long subPlanId, String stage, String filePath,
                              String action, String reason, String validationJson) {
        populateAndInsertExecutionLog(new AiExecutionLog(), subPlanId, stage, filePath, action, reason, validationJson);
    }

    private void populateAndInsertExecutionLog(AiExecutionLog logEntry, Long subPlanId, String stage, String filePath,
                                               String action, String reason, String validationJson) {
        logEntry.setSubPlanId(subPlanId);
        logEntry.setStage(stage);
        logEntry.setFilePath(filePath);
        logEntry.setAction(action);
        logEntry.setActionReason(reason);
        logEntry.setValidationResult(normalizeValidationJson(objectMapper, validationJson));
        logEntry.setStatus("FAILED".equals(action) || "ROLLED_BACK".equals(action) ? "FAILED" : "SUCCESS");
        logEntry.setCreateTime(LocalDateTime.now());
        executionLogMapper.insert(logEntry);
    }

    /**
     * Normalize validation_result as valid JSON before writing the MySQL JSON column.
     * Plain-text diagnostics are preserved under a summary property instead of breaking log persistence.
     */
    static String normalizeValidationJson(ObjectMapper candidate, String validationJson) {
        if (validationJson == null || validationJson.isBlank()) {
            return null;
        }
        ObjectMapper mapper = candidate != null ? candidate : new ObjectMapper();
        try {
            Object parsed = mapper.readTree(validationJson);
            if (parsed != null) {
                return mapper.writeValueAsString(parsed);
            }
        } catch (JsonProcessingException ignored) {
            // Preserve non-JSON diagnostics as a structured summary for reliable persistence.
        }
        return mapper.createObjectNode()
                .put("summary", validationJson)
                .toString();
    }

    private List<GeneratedProjectValidator.Issue> repairPlanWideProjectQuality(
            AiPlan plan, List<ExpectedDeliverable> expectedDeliverables,
            GenerationContext generationContext, List<AtomicTask> tasks) {
        try {
            Map<String, AtomicTask> tasksByPath = new LinkedHashMap<>();
            for (AtomicTask task : tasks) {
                if (task.getTargetPath() != null) {
                    tasksByPath.put(task.getTargetPath().replace('\\', '/'), task);
                }
            }
            ProjectQualityRepairer.RepairResult result = projectQualityRepairer.repair(
                    generatedFileStore.loadPlanFiles(plan), expectedDeliverables, generationContext,
                    referenceProjectIndex, tasksByPath, maxReviewRetries,
                    (source, projectContext, task, issues) ->
                            codeGenRouter.fixProjectQuality(source, projectContext, task, issues),
                    file -> generatedFileStore.persistRepair(plan, file));
            for (ProjectQualityRepairer.RepairEvent event : result.events()) {
                logPlanExecution(plan, "PLAN_QUALITY_FIX", event.filePath(),
                        event.success() ? "CREATED" : "FAILED",
                        "attempt=" + event.attempt() + ", strategy=" + event.strategy() + ": " + event.detail(), null);
            }
            long remainingErrors = result.issues().stream().filter(GeneratedProjectValidator.Issue::isError).count();
            if (!result.events().isEmpty()) {
                log.info("Project quality repair completed: attempts={}, events={}, remainingErrors={}",
                        result.attempts(), result.events().size(), remainingErrors);
            }
            return result.issues();
        } catch (Exception e) {
            log.warn("Project quality repair failed unexpectedly; final validator will report persisted files: {}",
                    e.getMessage(), e);
            List<GeneratedFile> persisted = generatedFileStore.loadPlanFiles(plan);
            List<GeneratedProjectValidator.Issue> fallback = new ArrayList<>(generatedProjectValidator.validate(
                    persisted, expectedDeliverables, generationContext, referenceProjectIndex));
            appendUniqueProjectIssues(fallback, generatedSourceGate.validate(persisted));
            return fallback;
        }
    }

    /**
     * 全 plan 级别的跨文件契约校验。
     * 从数据库拉出该 plan 下所有生成文件 (跨子方案),用 CrossFileValidator 检测:
     * 子方案 A 生成的 Controller 引用的 VO 类是否与子方案 B 生成的 VO 文件契约一致。
     * 结果写到 execution_log 的 PLAN_CROSS_VALIDATION 阶段,不阻断主流程。
     */
    private Optional<List<CrossFileValidator.ContractIssue>> validatePlanWideContracts(AiPlan plan) {
        try {
            List<AiGeneratedFile> dbFiles = generatedFileMapper.selectByPlanId(plan.getId());
            if (dbFiles == null || dbFiles.isEmpty()) {
                log.info("plan 级跨文件校验跳过: 无生成文件");
                return Optional.empty();
            }
            // 转换为 GeneratedFile (.java + .sql + .xml 均纳入，覆盖 entity↔DDL、mapper xml namespace)
            List<GeneratedFile> files = new ArrayList<>();
            for (AiGeneratedFile f : dbFiles) {
                if (f.getFilePath() == null || f.getContent() == null) continue;
                files.add(new GeneratedFile(null, f.getFilePath(), f.getContent(), f.getAction()));
            }
            if (files.isEmpty()) {
                log.info("plan 级跨文件校验跳过: 无生成文件");
                return Optional.empty();
            }
            List<CrossFileValidator.ContractIssue> issues = crossFileValidator.validate(files, true);
            long errorCount = issues.stream().filter(CrossFileValidator.ContractIssue::isError).count();
            String summary = "plan 级跨文件契约校验: " + files.size() + " 个 Java 文件, "
                    + issues.size() + " 项问题 (" + errorCount + " ERROR, "
                    + (issues.size() - errorCount) + " WARN)";
            if (!issues.isEmpty()) {
                summary += "; " + issues.stream().map(Object::toString).limit(10)
                        .reduce((a, b) -> a + " | " + b).orElse("");
            }
            if (errorCount > 0) {
                log.warn(summary);
            } else {
                log.info(summary);
            }
            // 写到 execution_log,subPlanId 为 null 表示 plan 级
            AiExecutionLog entry = new AiExecutionLog();
            entry.setPlanId(plan.getId());
            entry.setSubPlanId(null);
            entry.setStage("PLAN_CROSS_VALIDATION");
            entry.setAction(errorCount > 0 ? "FAILED" : "SUCCESS");
            entry.setActionReason(summary.length() > 4000 ? summary.substring(0, 4000) : summary);
            entry.setStatus(errorCount > 0 ? "FAILED" : "SUCCESS");
            entry.setCreateTime(LocalDateTime.now());
            executionLogMapper.insert(entry);
            return Optional.of(issues);
        } catch (Exception e) {
            log.warn("plan 级跨文件校验异常 (不影响主流程): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * plan 级 Entity↔DDL 自动修复。
     *
     * <p>子方案级校验时 DDL(在 sub71)与 Entity(在 sub72)分属不同子方案, 检不出 entity↔DDL 不一致;
     * 此处把整个 plan 的文件拉到一起, 检出 ENTITY-DDL-COLUMN-MISSING / TYPE-MISMATCH / TENANT 后,
     * 以 DDL 为契约源头重生成 Entity, 并写盘 + 更新 DB。
     *
     * <p>范围: 仅 ENTITY-DDL-* 三类可定位到 Entity 文件的规则。其余跨文件 ERROR 仍"仅记录"。
     * 复用 maxReviewRetries 限次; 修复失败不阻断主流程(保留原文件)。
     *
     * <p>注意: 此处 plan 级无 AtomicTask(子方案级才有), 需从 Entity 文件路径反推实体名/模块名临时构造 task,
     * 供 PromptBuilder 替换 {Entity}/{module} 占位符 + 系统 prompt 角色。
     */
    private void repairPackageDeclarations(AiPlan plan) {
        List<GeneratedFile> files = generatedFileStore.loadPlanFiles(plan);
        Pattern packagePattern = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
        int repaired = 0;
        for (GeneratedFile file : files) {
            String path = file.getFilePath() == null ? "" : file.getFilePath().replace('\\', '/');
            if (!path.endsWith(".java")) continue;
            int source = path.indexOf("/src/main/java/");
            int fileSlash = path.lastIndexOf('/');
            if (source < 0 || fileSlash <= source) continue;
            String expectedPackage = path.substring(source + "/src/main/java/".length(), fileSlash).replace('/', '.');
            Matcher matcher = packagePattern.matcher(file.getContent());
            if (!matcher.find() || expectedPackage.equals(matcher.group(1))) continue;
            String fixedContent = matcher.replaceFirst(Matcher.quoteReplacement("package " + expectedPackage + ";"));
            GeneratedFile fixed = new GeneratedFile(file.getType(), file.getFilePath(), fixedContent, "MODIFY");
            if (generatedFileStore.persistRepair(plan, fixed)) repaired++;
        }
        if (repaired > 0) {
            logPlanExecution(plan, "PLAN_CROSS_FIX", "", "SUCCESS",
                    "Synchronized " + repaired + " package declarations with physical target paths", null);
        }
    }

    private void retryPlanWideEntityDdlMismatches(AiPlan plan, GenerationContext generationContext) {
        try {
            List<GeneratedFile> files = generatedFileStore.loadPlanFiles(plan);
            if (files.isEmpty()) return;
            // M9: 以 DDL 为契约源头重生成 Entity, 写回(persistSingleFile+updateDB)
            Set<String> ENTITY_DDL_RULES = Set.of(
                    "ENTITY-DDL-COLUMN-MISSING", "ENTITY-DDL-TYPE-MISMATCH", "ENTITY-DDL-TENANT");
            runRepairLoop(files, ENTITY_DDL_RULES,
                    (src, contract, task, desc) -> codeGenRouter.fixEntityWithDdl(src, contract, task, desc),
                    path -> buildTaskFromEntityPath(path, generationContext),
                    null, "PLAN_CROSS_FIX", "Entity↔DDL",
                    (p, f) -> generatedFileStore.persistRepair(p, f),
                    plan);
        } catch (Exception e) {
            log.warn("plan 级 Entity↔DDL 修复异常 (不影响主流程): {}", e.getMessage());
        }
    }

    /** 从 Entity 文件路径反推实体名/模块名, 构造临时 AtomicTask(STANDARD_CRUD_ENTITY)。
     *  路径形如 src/main/java/org/springblade/{module}/pojo/entity/{Entity}.java */
    private AtomicTask buildTaskFromEntityPath(String path, GenerationContext generationContext) {
        if (path == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("org/springblade/([a-z][a-z0-9_]*)/(?:pojo/)?entity/([A-Z][a-zA-Z0-9_]*)\\.java")
                .matcher(path);
        if (!m.find()) return null;
        AtomicTask t = new AtomicTask();
        t.setType(TaskType.STANDARD_CRUD_ENTITY);
        t.setEntityName(m.group(2));
        t.setModuleName(m.group(1));
        t.setTargetPath(path);
        t.setGenerationContext(generationContext);
        t.setExpectedClassName(m.group(2));
        t.setTaskDescription("修复 " + m.group(2) + " Entity 使其与 DDL 表结构对齐");
        return t;
    }

    /**
     * 写盘单个文件 — 按 plan.writeTarget 决定写盘根(与 executeSubPlan 主写盘逻辑一致)。
     * 修复前 bug: 单参 write(tasks) 固定写 outputRoot,导致 REAL 模式修复后 Entity 落 ai-generated-modules
     * 而 blade_hgsjy 仍是旧版,造成 DB↔磁盘不一致。
     */
    /**
     * plan 级 VO/IVO/UVO 与 Entity 字段一致性自动修复(B1/B2/B3)。
     *
     * <p>子方案级校验时 Entity 与 VO 可能分属不同子方案, 检不出字段不一致; 此处把整个 plan 的文件拉到一起,
     * 检出 VO-ENTITY-FIELD-MISMATCH / VO-ENTITY-FIELD-TYPE-MISMATCH 后, 以 Entity 为契约源头重生成
     * VO/IVO/UVO, 并写盘 + 更新 DB。
     *
     * <p>范围: 仅 VO-ENTITY-FIELD-* 两类可定位到 VO 文件的规则。复用 maxReviewRetries 限次;
     * 修复失败不阻断主流程(保留原文件)。plan 级无 AtomicTask, 需从 VO 文件路径反推实体名/模块名临时构造 task。
     */
    private void retryPlanWideVoEntityMismatches(AiPlan plan, GenerationContext generationContext) {
        try {
            List<GeneratedFile> files = generatedFileStore.loadPlanFiles(plan);
            if (files.isEmpty()) return;
            // M9: 以 Entity 为契约源头重生成 VO/IVO/UVO, 写回(persistSingleFile+updateDB)
            Set<String> VO_ENTITY_RULES = Set.of(
                    "VO-ENTITY-FIELD-MISMATCH", "VO-ENTITY-FIELD-TYPE-MISMATCH");
            runRepairLoop(files, VO_ENTITY_RULES,
                    (src, contract, task, desc) -> codeGenRouter.fixVoWithEntity(src, contract, task, desc),
                    path -> buildTaskFromVoPath(path, generationContext),
                    null, "PLAN_CROSS_FIX", "VO↔Entity",
                    (p, f) -> generatedFileStore.persistRepair(p, f),
                    plan);
        } catch (Exception e) {
            log.warn("plan 级 VO↔Entity 修复异常 (不影响主流程): {}", e.getMessage());
        }
    }

    /** 从 VO 文件路径反推实体名/模块名, 构造临时 AtomicTask(OTHER 类型, 走 VO 通用系统提示词)。
     *  路径形如 src/main/java/org/springblade/{module}/pojo/vo/{Entity}{Suffix}.java, Suffix 为 VO/IVO/UVO/EVO。 */
    private AtomicTask buildTaskFromVoPath(String path, GenerationContext generationContext) {
        if (path == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("org/springblade/([a-z][a-z0-9_]*)/(?:pojo/)?vo(?:/(?:ivo|uvo|qvo|evo))?/([A-Z][a-zA-Z0-9_]*)\\.java")
                .matcher(path);
        if (!m.find()) return null;
        String voClassName = m.group(2);
        String entityName = stripVoSuffix(voClassName);
        AtomicTask t = new AtomicTask();
        t.setType(TaskType.OTHER);
        t.setEntityName(entityName);
        t.setModuleName(m.group(1));
        t.setTargetPath(path);
        t.setGenerationContext(generationContext);
        t.setExpectedClassName(m.group(2));
        t.setTaskDescription("修复 " + voClassName + " 使其字段与 " + entityName + " Entity 严格对齐");
        return t;
    }

    /** 剥离 VO 类名后缀(IVO/UVO/EVO/VO, 不含 QVO)得到 Entity 名, 如 OrderIVO -> Order */
    private String stripVoSuffix(String voClassName) {
        for (String s : new String[]{"IVO", "UVO", "EVO"}) {
            if (voClassName.endsWith(s) && voClassName.length() > s.length()) {
                return voClassName.substring(0, voClassName.length() - s.length());
            }
        }
        if (voClassName.endsWith("VO") && !voClassName.endsWith("QVO") && voClassName.length() > 2) {
            return voClassName.substring(0, voClassName.length() - 2);
        }
        return voClassName;
    }
}
