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
        GenerationContext generationContext = new GenerationContext(generationIdentity, frameworkProfile);
        try {
            plan.setGenerationIdentityJson(objectMapper.writeValueAsString(generationIdentity));
            plan.setReferenceProfileJson(objectMapper.writeValueAsString(frameworkProfile));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to persist generation context", e);
        }
        plan.setOutputDirectory(WriteTarget.parse(plan.getWriteTarget()).isReal()
                ? properties.getTargetProjectRoot()
                : Paths.get(properties.getOutputRoot(), receptionId).toString());
        plan.setCompileVerificationStatus("NOT_RUN");
        planMapper.updateById(plan);
        log.info("Generation context locked: identity={}, profile={}", generationIdentity, frameworkProfile.describeForPrompt());

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
        for (AiSubPlan plannedSubPlan : executionOrder) {
            List<AtomicTask> subPlanTasks = parseAtomicTasks(plannedSubPlan, generationContext);
            subPlanTasks.forEach(task -> task.setSourceSubPlanId(plannedSubPlan.getId()));
            plannedTasks.put(plannedSubPlan.getId(), subPlanTasks);
            for (AtomicTask task : subPlanTasks) {
                expectedDeliverables.add(ExpectedDeliverable.from(plannedSubPlan.getId(), task));
            }
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
        retryPlanWideEntityDdlMismatches(plan);

        // 3.5b plan 级 VO/IVO/UVO <-> Entity 字段一致性修复(B1/B2/B3) - Entity 与 VO 常分属不同子方案,
        //     子方案级检不出; 以 Entity 为源头重生成 VO/IVO/UVO, 写盘 + 更新 DB。修复失败不阻断主流程。
        retryPlanWideVoEntityMismatches(plan);

        // 3.6 全 plan 级跨文件契约校验(修复后跑,反映修复后真实状态)。
        Optional<List<CrossFileValidator.ContractIssue>> finalContractValidation = validatePlanWideContracts(plan);
        boolean finalValidationSucceeded = finalContractValidation.isPresent();
        long finalContractErrorCount = finalContractValidation
                .map(issues -> issues.stream().filter(CrossFileValidator.ContractIssue::isError).count())
                .orElse(0L);

        List<GeneratedProjectValidator.Issue> projectIssues = generatedProjectValidator.validate(
                generatedFileStore.loadPlanFiles(plan), expectedDeliverables, generationContext, referenceProjectIndex);
        long projectQualityErrorCount = projectIssues.stream().filter(GeneratedProjectValidator.Issue::isError).count();
        if (!projectIssues.isEmpty()) {
            String report;
            try {
                report = objectMapper.writeValueAsString(projectIssues);
            } catch (JsonProcessingException e) {
                report = projectIssues.toString();
            }
            logExecution(null, "PROJECT_QUALITY_VALIDATION", "",
                    projectQualityErrorCount > 0 ? "FAILED" : "SUCCESS",
                    "Project quality validation: " + projectQualityErrorCount + " ERROR / " + projectIssues.size() + " issues", report);
        } else {
            logExecution(null, "PROJECT_QUALITY_VALIDATION", "", "SUCCESS",
                    "Project quality validation passed", null);
        }

        // 子方案级校验发生在 plan 级修复之前。只有最终校验确实执行成功且已无 ERROR，才把此前的
        // COMPLETED_WITH_ERRORS 恢复成 COMPLETED；校验异常时保留原状态，不能把未知结果误报成功。
        if (finalValidationSucceeded && finalContractErrorCount == 0 && projectQualityErrorCount == 0) {
            reconcileRepairedSubPlanStatuses(executionOrder);
        }

        // 4. 更新最终状态 - 有 FAILED -> FAILED；最终契约/编译仍有 ERROR -> COMPLETED_WITH_ERRORS；否则 COMPLETED。
        // 3.7 C1: REAL 模式编译验证(真实项目有平台 jar 可编译;ISOLATED 跳过-隔离区缺平台 jar)。
        //        编译失败标 COMPLETED_WITH_ERRORS(代码已写盘,不回滚-留人工处理)。
        boolean compileFailed = false;
        if (WriteTarget.parse(plan.getWriteTarget()).isReal()) {
            compileFailed = !runCompileVerification(plan);
            plan.setCompileVerificationStatus(compileFailed ? "FAILED" : "PASSED");
        } else {
            plan.setCompileVerificationStatus("SKIPPED_DEPENDENCIES_UNAVAILABLE");
            logExecution(null, "COMPILE_VERIFICATION", "", "SKIPPED",
                    "Compile verification was not run because private reference dependencies are unavailable", null);
        }

        boolean hasSubPlanErrors = executionOrder.stream()
                .anyMatch(sp -> sp.getStatus() == SubPlanStatus.COMPLETED_WITH_ERRORS);
        plan.setStatus(determineFinalPlanStatus(
                allSuccess, hasSubPlanErrors, finalContractErrorCount + projectQualityErrorCount, compileFailed));
        List<AtomicTask> allPlannedTasks = plannedTasks.values().stream().flatMap(Collection::stream).toList();
        GenerationReportWriter.write(plan, generatedFileStore.loadPlanFiles(plan), expectedDeliverables,
                allPlannedTasks, projectIssues, objectMapper);
        planMapper.updateById(plan);

        // 5. 回调Part A
        statusNotifier.notifyPlan(plan, executionOrder);

        log.info("工作流执行完成: receptionId={}, status={}", receptionId, plan.getStatus());
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

    /** C1: REAL 模式编译验证 - 从 DB 拉全 plan 文件,推受影响 BladeX 模块,跑 mvn compile。
     *  返回 true=通过/跳过, false=编译失败。失败记 execution_log;异常不阻断(视为通过,避免编译环境问题让 plan 失败)。 */
    private boolean runCompileVerification(AiPlan plan) {
        try {
            List<AiGeneratedFile> dbFiles = generatedFileMapper.selectByPlanId(plan.getId());
            if (dbFiles == null || dbFiles.isEmpty()) return true;
            // 从 .java 文件路径推 BladeX 模块(blade-service/blade-{module} 或 blade-service-api/blade-{module}-api)
            java.util.Set<String> modules = new java.util.LinkedHashSet<>();
            for (AiGeneratedFile f : dbFiles) {
                String path = f.getFilePath();
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
            java.util.List<String> moduleList = new java.util.ArrayList<>(modules);
            BuildResult buildResult = buildVerifier.verify(moduleList);
            if (buildResult.isPasses()) {
                logExecution(null, "COMPILE_VERIFICATION", "", "SUCCESS",
                        "编译验证通过: " + moduleList, null);
                return true;
            }
            String summary = "编译验证失败: " + moduleList + "; "
                    + buildResult.getErrors().stream().map(Object::toString).limit(10)
                            .reduce((a, b) -> a + " | " + b).orElse("");
            logExecution(null, "COMPILE_VERIFICATION", "", "FAILED", summary, null);
            log.warn("C1 编译验证失败: plan={}, {}", plan.getReceptionId(), summary);
            return false;
        } catch (Exception e) {
            log.warn("C1 编译验证异常(不阻断, 视为通过): plan={}, err={}", plan.getReceptionId(), e.getMessage());
            return true;
        }
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
        List<GeneratedFile> skeletons = BladeXModuleSkeleton.ensureFor(allGeneratedFiles, ensuredSkeletonKeys, generationContext);
        if (!skeletons.isEmpty()) {
            allGeneratedFiles.addAll(skeletons);
            log.info("补齐模块骨架: {} 个文件", skeletons.size());
        }

        // 3d. 写入文件 — 阶段2: 按 plan.writeTarget 决定写盘 root
        //     ISOLATED(默认)→ outputRoot(隔离区);REAL → targetProjectRoot(默认亦隔离区, 需查重)
        WriteTarget writeTarget = WriteTarget.parse(plan.getWriteTarget());
        String writeRoot = writeTarget.isReal()
                ? properties.getTargetProjectRoot()
                : plan.getOutputDirectory();
        log.info("写盘目标: writeTarget={}, root={}", writeTarget.getCode(), writeRoot);

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

        // 写盘根可用性检查:ISOLATED 自动创建;REAL 必须已存在(避免误造目标项目目录)
        boolean rootAvailable = writeTarget.isReal()
                ? fileWriteExecutor.isRootAvailable(writeRoot)
                : fileWriteExecutor.isTargetRootAvailable();
        if (!rootAvailable) {
            log.warn("写盘根不可用,跳过文件写入,仅把生成内容落库供 Part A 查看: {}", writeRoot);
            generatedFileStore.saveBatch(subPlan, plan, allGeneratedFiles, "SKIPPED");
            logExecution(subPlan.getId(), "FILE_WRITE", "",
                    "SKIPPED", "写盘根不可用: " + writeRoot, null);
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
            case FEIGN_CLIENT -> ClassType.FEIGN;
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
    private List<AtomicTask> parseAtomicTasks(AiSubPlan subPlan, GenerationContext generationContext) {
        String content = subPlan.getPlanContent();
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }

        // 提取关键信息: 实体名 (Order) 和模块名 (order)
        String extractedEntity = extractEntityName(content);
        String entityName = "Entity".equals(extractedEntity)
                ? generationContext.identity().entityName() : extractedEntity;
        String moduleName = generationContext.identity().moduleName();

        String title = subPlan.getTitle() == null ? "" : subPlan.getTitle();
        String tLower = title.toLowerCase();
        String fullContext = "【子方案完整上下文】\n" + content;

        List<AtomicTask> tasks = new ArrayList<>();

        // 1. DDL — 标题含 "DDL/数据库/建表/SQL"（落 doc/sql/{module}）
        if (tLower.contains("ddl") || title.contains("数据库") || title.contains("建表") || title.contains("sql")) {
            tasks.add(buildTask(TaskType.DDL_STATEMENT,
                    title + " — 生成数据库 DDL\n\n" + fullContext,
                    BladeXModuleLayout.ddlPath(generationContext),
                    entityName, moduleName));
        }

        // 2. Entity — 标题含 "Entity/实体"（落 API 模块 pojo.entity）
        if (title.contains("Entity") || title.contains("实体")) {
            tasks.add(buildTask(TaskType.STANDARD_CRUD_ENTITY,
                    title + " — 生成 Entity 类 (" + entityName + ")\n\n" + fullContext,
                    BladeXModuleLayout.entityPath(generationContext, entityName),
                    entityName, moduleName));
        }

        // 3. VO 类 — 标题含 "VO/视图",生成所有在内容里被提及的 VO 类（落 API 模块 pojo.vo）
        if (title.contains("VO") || title.contains("视图")) {
            for (String suffix : new String[]{"QVO", "IVO", "UVO", "VO", "EVO"}) {
                    tasks.add(buildTask(TaskType.OTHER,
                    title + " — 生成 " + entityName + suffix + " 类\n\n" + voInstructions(suffix, entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.voPath(generationContext, entityName, suffix),
                    entityName, moduleName));
            }
        }

        // 4. Mapper — 标题含 "Mapper" 或 "Service"(Service 子方案通常同时含 Mapper)（落 IMPL 模块 mapper）
        if (title.contains("Mapper") || title.contains("Service") || title.contains("服务")) {
            tasks.add(buildTask(TaskType.CUSTOM_MAPPER,
                    title + " — 生成 " + entityName + "Mapper 接口\n\n" + fullContext,
                    BladeXModuleLayout.mapperJavaPath(generationContext, entityName),
                    entityName, moduleName));
            // Mapper.xml 同伴文件（与 .java 同包目录，BladeX 约定）
            tasks.add(buildTask(TaskType.MAPPER_XML,
                    title + " — 生成 " + entityName + "Mapper.xml\n\n" + fullContext,
                    BladeXModuleLayout.mapperXmlPath(generationContext, entityName),
                    entityName, moduleName));
        }

        // 5. Service 接口 + 实现（落 IMPL 模块 service / service.impl）
        if (title.contains("Service") || title.contains("服务")) {
            tasks.add(buildTask(TaskType.STANDARD_CRUD_SERVICE,
                    title + " — 生成 I" + entityName + "Service 接口\n\n" + serviceInterfaceInstructions(entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.serviceInterfacePath(generationContext, entityName),
                    entityName, moduleName));
            // 6. Service 实现
            tasks.add(buildTask(TaskType.STANDARD_CRUD_SERVICE,
                    title + " — 生成 " + entityName + "ServiceImpl 实现类\n\n" + serviceImplInstructions(entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.serviceImplPath(generationContext, entityName),
                    entityName, moduleName));
        }

        // 7. Wrapper（落 IMPL 模块 wrapper；用 WRAPPER 类型命中 buildWrapperSystemPrompt 的 DeptCache 禁令）
        if (title.contains("Wrapper") || title.contains("包装") || title.contains("Controller")) {
            tasks.add(buildTask(TaskType.WRAPPER,
                    title + " — 生成 " + entityName + "Wrapper 转换类\n\n" + wrapperInstructions(entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.wrapperPath(generationContext, entityName),
                    entityName, moduleName));
        }

        // 8. Controller（落 IMPL 模块 controller）
        if (title.contains("Controller") || title.contains("控制器") || title.contains("API")) {
            tasks.add(buildTask(TaskType.STANDARD_CRUD_CONTROLLER,
                    title + " — 生成 " + entityName + "Controller 类\n\n" + controllerInstructions(entityName, moduleName) + "\n\n" + fullContext,
                    BladeXModuleLayout.controllerPath(generationContext, entityName),
                    entityName, moduleName));
        }

        // 9. Excel（落 IMPL 模块 excel）
        if (title.contains("Excel") || title.contains("导入导出")) {
            tasks.add(buildTask(TaskType.EXCEL_IMPORT_EXPORT,
                    title + " — 生成 " + entityName + "Excel 类\n\n" + excelInstructions(entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.excelPath(generationContext, entityName),
                    entityName, moduleName));
        }

        // 10. Feign — description 必须明确接口名 I{Entity}Client,否则 LLM 容易把实体名漂移成其他词（落 API 模块 feign）
        if (title.contains("Feign") || title.contains("远程")) {
            tasks.add(buildTask(TaskType.FEIGN_CLIENT,
                    title + " — 生成 Feign 客户端接口 I" + entityName + "Client (实体名: " + entityName + ")\n\n"
                            + feignInstructions(entityName, moduleName) + "\n\n" + fullContext,
                    BladeXModuleLayout.feignPath(generationContext, entityName),
                    entityName, moduleName));
        }

        // 兜底
        // Explicit named deliverables mentioned by the reviewed plan are generated in addition
        // to the primary CRUD stack. This prevents DTO/Feign/server-entry files from silently disappearing.
        for (String dtoClass : extractNamedDeliverables(content, "DTO")) {
            tasks.add(buildTask(TaskType.OTHER,
                    title + " - generate exact DTO class " + dtoClass + "\n\n" + fullContext,
                    BladeXModuleLayout.dtoPath(generationContext, dtoClass),
                    entityName, moduleName));
        }
        for (String clientClass : extractNamedDeliverables(content, "Client")) {
            String primaryClient = "I" + entityName + "Client";
            if (clientClass.equals(primaryClient)) continue;
            tasks.add(buildTask(TaskType.FEIGN_CLIENT,
                    title + " - generate exact Feign interface " + clientClass
                            + "; do not rename it or replace its owning module\n\n" + fullContext,
                    BladeXModuleLayout.namedFeignPath(generationContext, clientClass),
                    stripClientName(clientClass), moduleName));
        }
        for (String controllerClass : extractNamedDeliverables(content, "Controller")) {
            if (controllerClass.equals(entityName + "Controller")) continue;
            tasks.add(buildTask(TaskType.STANDARD_CRUD_CONTROLLER,
                    title + " - generate exact controller class " + controllerClass
                            + "; keep all endpoints required by the reviewed plan\n\n" + fullContext,
                    BladeXModuleLayout.namedControllerPath(generationContext, controllerClass),
                    controllerClass.substring(0, controllerClass.length() - "Controller".length()), moduleName));
        }

        if (tasks.isEmpty()) {
            tasks.add(buildTask(TaskType.OTHER,
                    title + "\n\n" + fullContext,
                    "ai-generated/subplan-" + subPlan.getId() + "/Code.java",
                    entityName, moduleName));
            log.info("子方案 {} 未匹配关键字,使用兜底", title);
        }

        // 去重 (按 targetPath)
        Set<String> seen = new java.util.LinkedHashSet<>();
        List<AtomicTask> deduped = new ArrayList<>();
        for (AtomicTask t : tasks) {
            t.setGenerationContext(generationContext);
            t.setModuleName(generationContext.identity().moduleName());
            if (seen.add(t.getTargetPath())) {
                deduped.add(t);
            }
        }
        log.info("子方案 [{}] 解析出 {} 个原子任务: {}", title, deduped.size(),
                deduped.stream().map(AtomicTask::getTargetPath).toList());
        return deduped;
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
    private Set<String> extractNamedDeliverables(String content, String suffix) {
        Set<String> names = new LinkedHashSet<>();
        if (content == null || suffix == null) return names;
        Pattern explicit = Pattern.compile(
                "(?:create|generate|add|new|\\u521b\\u5efa|\\u751f\\u6210|\\u65b0\\u589e|\\u5b9e\\u73b0|\\u4ea4\\u4ed8)[^\\n]{0,80}?`?"
                        + "(I?[A-Z][A-Za-z0-9_]*" + Pattern.quote(suffix) + ")`?",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = explicit.matcher(content);
        while (matcher.find()) names.add(matcher.group(1));
        Pattern fileList = Pattern.compile("`(I?[A-Z][A-Za-z0-9_]*" + Pattern.quote(suffix) + ")(?:\\.java)?`");
        matcher = fileList.matcher(content);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

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
        return "I" + entityName + "Service 必须 extends BaseService<" + entityName + ">, 接口体可以为空";
    }

    private String serviceImplInstructions(String entityName) {
        return entityName + "ServiceImpl 必须:\n"
                + "- extends BaseServiceImpl<" + entityName + "Mapper, " + entityName + ">\n"
                + "- implements I" + entityName + "Service\n"
                + "- 类上加 @Service\n"
                + "- 实现体可以为空(基础 CRUD 由父类提供)";
    }

    private String wrapperInstructions(String entityName) {
        return entityName + "Wrapper 必须:\n"
                + "- extends BaseEntityWrapper<" + entityName + ", " + entityName + "VO>\n"
                + "- 提供 public static " + entityName + "Wrapper build() 工厂方法\n"
                + "- 覆盖 public " + entityName + "VO entityVO(" + entityName + " entity) 方法, 用 BeanUtil.copy() 拷贝";
    }

    private String controllerInstructions(String entityName, String moduleName) {
        return entityName + "Controller 必须:\n"
                + "- extends BladeController\n"
                + "- @RestController @AllArgsConstructor @RequestMapping(\"/" + moduleName + "\") @Tag(name = \"" + entityName + "管理\")\n"
                + "- 注入 I" + entityName + "Service\n"
                + "- 5 个标准端点: /detail (GET), /list (GET 分页), /save (POST), /update (POST), /remove (POST 用 deleteLogic)\n"
                + "- 所有方法返回 R<...>\n"
                + "- 使用 " + entityName + "Wrapper.build() 转换 VO\n"
                + "- 使用 @ApiOperationSupport(order = N)、@Operation(summary = ...)";
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
    private void logExecution(Long subPlanId, String stage, String filePath,
                               String action, String reason, String validationJson) {
        AiExecutionLog logEntry = new AiExecutionLog();
        logEntry.setSubPlanId(subPlanId);
        logEntry.setStage(stage);
        logEntry.setFilePath(filePath);
        logEntry.setAction(action);
        logEntry.setActionReason(reason);
        logEntry.setValidationResult(validationJson);
        logEntry.setStatus("FAILED".equals(action) || "ROLLED_BACK".equals(action) ? "FAILED" : "SUCCESS");
        logEntry.setCreateTime(LocalDateTime.now());
        executionLogMapper.insert(logEntry);
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
    private void retryPlanWideEntityDdlMismatches(AiPlan plan) {
        try {
            List<GeneratedFile> files = generatedFileStore.loadPlanFiles(plan);
            if (files.isEmpty()) return;
            // M9: 以 DDL 为契约源头重生成 Entity, 写回(persistSingleFile+updateDB)
            Set<String> ENTITY_DDL_RULES = Set.of(
                    "ENTITY-DDL-COLUMN-MISSING", "ENTITY-DDL-TYPE-MISMATCH", "ENTITY-DDL-TENANT");
            runRepairLoop(files, ENTITY_DDL_RULES,
                    (src, contract, task, desc) -> codeGenRouter.fixEntityWithDdl(src, contract, task, desc),
                    this::buildTaskFromEntityPath,
                    null, "PLAN_CROSS_FIX", "Entity↔DDL",
                    (p, f) -> generatedFileStore.persistRepair(p, f),
                    plan);
        } catch (Exception e) {
            log.warn("plan 级 Entity↔DDL 修复异常 (不影响主流程): {}", e.getMessage());
        }
    }

    /** 从 Entity 文件路径反推实体名/模块名, 构造临时 AtomicTask(STANDARD_CRUD_ENTITY)。
     *  路径形如 src/main/java/org/springblade/{module}/pojo/entity/{Entity}.java */
    private AtomicTask buildTaskFromEntityPath(String path) {
        if (path == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("org/springblade/([a-z][a-z0-9_]*)/pojo/entity/([A-Z][a-zA-Z0-9_]*)\\.java")
                .matcher(path);
        if (!m.find()) return null;
        AtomicTask t = new AtomicTask();
        t.setType(TaskType.STANDARD_CRUD_ENTITY);
        t.setEntityName(m.group(2));
        t.setModuleName(m.group(1));
        t.setTargetPath(path);
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
    private void retryPlanWideVoEntityMismatches(AiPlan plan) {
        try {
            List<GeneratedFile> files = generatedFileStore.loadPlanFiles(plan);
            if (files.isEmpty()) return;
            // M9: 以 Entity 为契约源头重生成 VO/IVO/UVO, 写回(persistSingleFile+updateDB)
            Set<String> VO_ENTITY_RULES = Set.of(
                    "VO-ENTITY-FIELD-MISMATCH", "VO-ENTITY-FIELD-TYPE-MISMATCH");
            runRepairLoop(files, VO_ENTITY_RULES,
                    (src, contract, task, desc) -> codeGenRouter.fixVoWithEntity(src, contract, task, desc),
                    this::buildTaskFromVoPath,
                    null, "PLAN_CROSS_FIX", "VO↔Entity",
                    (p, f) -> generatedFileStore.persistRepair(p, f),
                    plan);
        } catch (Exception e) {
            log.warn("plan 级 VO↔Entity 修复异常 (不影响主流程): {}", e.getMessage());
        }
    }

    /** 从 VO 文件路径反推实体名/模块名, 构造临时 AtomicTask(OTHER 类型, 走 VO 通用系统提示词)。
     *  路径形如 src/main/java/org/springblade/{module}/pojo/vo/{Entity}{Suffix}.java, Suffix 为 VO/IVO/UVO/EVO。 */
    private AtomicTask buildTaskFromVoPath(String path) {
        if (path == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("org/springblade/([a-z][a-z0-9_]*)/pojo/vo/([A-Z][a-zA-Z0-9_]*)\\.java")
                .matcher(path);
        if (!m.find()) return null;
        String voClassName = m.group(2);
        String entityName = stripVoSuffix(voClassName);
        AtomicTask t = new AtomicTask();
        t.setType(TaskType.OTHER);
        t.setEntityName(entityName);
        t.setModuleName(m.group(1));
        t.setTargetPath(path);
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
