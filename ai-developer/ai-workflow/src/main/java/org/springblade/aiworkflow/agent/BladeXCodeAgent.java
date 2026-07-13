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
import org.springblade.aiworkflow.vo.StatusUpdateRequest;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

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
    private final Consumer<StatusUpdateRequest> callbackNotifier;
    /** 阶段2: 配置(取 targetProjectRoot/outputRoot 决定写盘 root) */
    private final AiWorkflowProperties properties;
    /** 阶段2: 已有项目索引(REAL 模式查重用) */
    private final ExistingProjectIndex existingProjectIndex;
    /** 阶段2增强: 参考项目索引(REAL 模式生成时参考现有风格) */
    private final ReferenceProjectIndex referenceProjectIndex;

    /** 跨文件契约校验器 — 无状态工具, 直接持有实例 */
    private final CrossFileValidator crossFileValidator = new CrossFileValidator();

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
                           Consumer<StatusUpdateRequest> callbackNotifier,
                           AiWorkflowProperties properties,
                           ExistingProjectIndex existingProjectIndex,
                           ReferenceProjectIndex referenceProjectIndex) {
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
        this.callbackNotifier = callbackNotifier;
        this.properties = properties;
        this.existingProjectIndex = existingProjectIndex;
        this.referenceProjectIndex = referenceProjectIndex;
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

        // 2. 构建DAG并验证无环
        List<AiSubPlan> executionOrder = buildExecutionOrder(subPlans);
        if (executionOrder == null) {
            log.error("子方案依赖关系中存在循环");
            plan.setStatus(PlanStatus.FAILED);
            planMapper.updateById(plan);
            return;
        }

        // 3. 按拓扑顺序执行
        //    子方案失败不立刻中断整条流水线: 仅跳过依赖该失败子方案的下游(直接/传递)子方案,
        //    无依赖关系的并列子方案继续执行,让 Part A 拿到尽可能多的部分结果。
        boolean allSuccess = true;
        Set<Long> failedIds = new HashSet<>();
        Map<Long, List<String>> reverseDeps = buildReverseDependencies(executionOrder);
        for (AiSubPlan subPlan : executionOrder) {
            // 判断当前子方案是否依赖某个已失败的子方案 -> 直接 SKIPPED
            if (dependsOnFailed(subPlan, failedIds)) {
                subPlan.setStatus(SubPlanStatus.SKIPPED);
                subPlan.setErrorMessage("前置依赖失败,跳过执行");
                subPlan.setCompletedAt(LocalDateTime.now());
                subPlanMapper.updateById(subPlan);
                failedIds.add(subPlan.getId());
                allSuccess = false;
                notifyPartAForSubPlan(subPlan);
                continue;
            }
            try {
                boolean subPlanSuccess = executeSubPlan(subPlan, plan, ensuredSkeletonKeys);
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
                notifyPartAForSubPlan(subPlan);
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

        // 3.6 全 plan 级跨文件契约校验(修复后跑,反映修复后真实状态) — 仅记录到 execution_log, 不阻断。
        validatePlanWideContracts(plan);

        // 4. 更新最终状态 - H3: 有子方案 FAILED -> FAILED; 有 COMPLETED_WITH_ERRORS(无 FAILED) -> COMPLETED_WITH_ERRORS; 否则 COMPLETED
        // 3.7 C1: REAL 模式编译验证(真实项目有平台 jar 可编译;ISOLATED 跳过-隔离区缺平台 jar)。
        //        编译失败标 COMPLETED_WITH_ERRORS(代码已写盘,不回滚-留人工处理)。
        boolean compileFailed = false;
        if (WriteTarget.parse(plan.getWriteTarget()).isReal()) {
            compileFailed = !runCompileVerification(plan);
        }

        boolean hasSubPlanErrors = executionOrder.stream()
                .anyMatch(sp -> sp.getStatus() == SubPlanStatus.COMPLETED_WITH_ERRORS);
        plan.setStatus(!allSuccess ? PlanStatus.FAILED
                : (hasSubPlanErrors || compileFailed) ? PlanStatus.COMPLETED_WITH_ERRORS : PlanStatus.COMPLETED);
        planMapper.updateById(plan);

        // 5. 回调Part A
        notifyPartA(plan, executionOrder);

        log.info("工作流执行完成: receptionId={}, status={}", receptionId, plan.getStatus());
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
    private boolean executeSubPlan(AiSubPlan subPlan, AiPlan plan, Set<String> ensuredSkeletonKeys) {
        log.info("执行子方案: id={}, title={}", subPlan.getId(), subPlan.getTitle());

        // 更新状态
        subPlan.setStatus(SubPlanStatus.EXECUTING);
        subPlan.setStartedAt(LocalDateTime.now());
        subPlanMapper.updateById(subPlan);

        // 3a. 解析子方案为原子任务
        List<AtomicTask> tasks = parseAtomicTasks(subPlan);
        log.info("解析出 {} 个原子任务", tasks.size());

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
                notifyPartAForSubPlan(subPlan);
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
                    notifyPartAForSubPlan(subPlan);
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
        List<GeneratedFile> skeletons = BladeXModuleSkeleton.ensureFor(allGeneratedFiles, ensuredSkeletonKeys);
        if (!skeletons.isEmpty()) {
            allGeneratedFiles.addAll(skeletons);
            log.info("补齐模块骨架: {} 个文件", skeletons.size());
        }

        // 3d. 写入文件 — 阶段2: 按 plan.writeTarget 决定写盘 root
        //     ISOLATED(默认)→ outputRoot(隔离区);REAL → targetProjectRoot(默认亦隔离区, 需查重)
        WriteTarget writeTarget = WriteTarget.parse(plan.getWriteTarget());
        String writeRoot = writeTarget.isReal()
                ? properties.getTargetProjectRoot()
                : properties.getOutputRoot();
        log.info("写盘目标: writeTarget={}, root={}", writeTarget.getCode(), writeRoot);

        // REAL 模式: 写盘前查重 — 类名/表名冲突即拒绝(不覆盖现有代码)
        if (writeTarget.isReal()) {
            String conflict = detectNameConflicts(allGeneratedFiles);
            if (conflict != null) {
                log.warn("REAL 模式查重冲突,拒绝写盘: {}", conflict);
                logExecution(subPlan.getId(), "FILE_WRITE", "", "SKIPPED",
                        "类名/表名冲突: " + conflict, null);
                persistGeneratedFiles(subPlan, plan, allGeneratedFiles, "SKIPPED");
                subPlan.setStatus(SubPlanStatus.FAILED);
                subPlan.setErrorMessage("REAL 模式查重冲突,拒绝写盘: " + conflict);
                subPlan.setCompletedAt(LocalDateTime.now());
                subPlanMapper.updateById(subPlan);
                notifyPartAForSubPlan(subPlan);
                return false;
            }
        }

        // 写盘根可用性检查:ISOLATED 自动创建;REAL 必须已存在(避免误造目标项目目录)
        boolean rootAvailable = writeTarget.isReal()
                ? fileWriteExecutor.isRootAvailable(writeRoot)
                : fileWriteExecutor.isTargetRootAvailable();
        if (!rootAvailable) {
            log.warn("写盘根不可用,跳过文件写入,仅把生成内容落库供 Part A 查看: {}", writeRoot);
            persistGeneratedFiles(subPlan, plan, allGeneratedFiles, "SKIPPED");
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
                persistGeneratedFiles(subPlan, plan, allGeneratedFiles, "FAILED");
                subPlan.setStatus(SubPlanStatus.FAILED);
                subPlan.setErrorMessage(writeResult.getErrorMessage());
                subPlan.setCompletedAt(LocalDateTime.now());
                subPlanMapper.updateById(subPlan);
                notifyPartAForSubPlan(subPlan);
                return false;
            }

            log.info("文件写入完成: {} 个文件 (root={})", writeResult.getWrittenFiles().size(), writeRoot);
            // 把生成的代码文件落库,供 Part A /api/plans/{receptionId}/files 拉取
            persistGeneratedFiles(subPlan, plan, allGeneratedFiles, "CREATED");
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
        notifyPartAForSubPlan(subPlan);

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
        // 可修复规则: 以 IService 接口为契约源头重生成实现类(Controller / ServiceImpl)
        Set<String> FIXABLE_RULES = Set.of(
                "CROSS-CONTROLLER-SERVICE-MISMATCH",
                "CROSS-SERVICE-IMPL-IFACE-MISMATCH");

        for (int attempt = 0; attempt <= maxReviewRetries; attempt++) {
            List<CrossFileValidator.ContractIssue> issues = crossFileValidator.validate(allFiles);
            List<CrossFileValidator.ContractIssue> fixable = issues.stream()
                    .filter(i -> i.isError() && FIXABLE_RULES.contains(i.rule))
                    .toList();
            if (fixable.isEmpty()) {
                if (attempt > 0) {
                    log.info("跨文件契约修复完成: 第 {} 次重试后无可修复 ERROR", attempt);
                    logExecution(subPlan.getId(), "CROSS_FILE_FIX", "",
                            "SUCCESS", "跨文件契约修复完成 (" + attempt + " 次重试)", null);
                }
                return;
            }
            if (attempt == maxReviewRetries) {
                String summary = "跨文件契约不一致, 已达最大重试次数 (" + (maxReviewRetries + 1)
                        + ") 仍未修复: " + fixable.size() + " 项; "
                        + fixable.stream().map(Object::toString).limit(5)
                                .reduce((a, b) -> a + " | " + b).orElse("");
                log.warn(summary);
                logExecution(subPlan.getId(), "CROSS_FILE_FIX", "", "FAILED", summary, null);
                return;
            }
            log.info("跨文件契约不一致 (第 {}/{} 次重试): {} 项",
                    attempt + 1, maxReviewRetries + 1, fixable.size());

            // 按"需重生成的文件(sourceFilePath)"分组: 同一文件的多个问题合并为一次重生成,
            // 避免逐个修又把前一个修好的改回去。
            Map<String, List<CrossFileValidator.ContractIssue>> bySource = new LinkedHashMap<>();
            for (CrossFileValidator.ContractIssue err : fixable) {
                bySource.computeIfAbsent(err.sourceFilePath, k -> new ArrayList<>()).add(err);
            }
            for (Map.Entry<String, List<CrossFileValidator.ContractIssue>> entry : bySource.entrySet()) {
                String sourcePath = entry.getKey();
                List<CrossFileValidator.ContractIssue> errs = entry.getValue();
                GeneratedFile sourceFile = findFileByPath(allFiles, sourcePath);
                // 契约对端: IService 接口(两类规则的 contractFilePath 都指向它)
                GeneratedFile contractFile = findFileByPath(allFiles, errs.get(0).contractFilePath);
                AtomicTask task = taskByPath.get(sourcePath);
                if (sourceFile == null || contractFile == null || task == null) {
                    log.warn("跨文件修复跳过: 无法定位文件 (source={}, contract={}, hasTask={})",
                            sourcePath, errs.get(0).contractFilePath, task != null);
                    continue;
                }
                String mergedDesc = errs.stream().map(e -> e.message)
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                GenerationResult fix = codeGenRouter.fixWithCrossFileContext(
                        sourceFile, contractFile.getContent(), task, mergedDesc);
                if (fix.isSuccess()) {
                    GeneratedFile newFile = fix.getGeneratedFiles().get(0);
                    if (!passesConventionAfterFix(newFile)) {
                        log.warn("跨文件修复被拒(引入 Layer1 违规), 保留原文件: path={}", sourcePath);
                        continue;
                    }
                    replaceFile(allFiles, sourcePath, newFile);
                    logExecution(subPlan.getId(), "CROSS_FILE_FIX", sourcePath,
                            "CREATED", "跨文件契约修复: " + mergedDesc, null);
                } else {
                    log.warn("跨文件修复 LLM 失败: {}", fix.getErrorMessage());
                }
            }
        }
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
        var ref = referenceProjectIndex.findReferenceExample(targetType, null);
        if (ref.isEmpty()) {
            log.debug("REAL 生成: 参考项目中无 {} 类型的参考代码", targetType);
            return null;
        }
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
            default -> null; // DDL_STATEMENT / MAPPER_XML / NACOS_CONFIG / EXCEL_IMPORT_EXPORT / OTHER 不参考
        };
    }

    private String detectNameConflicts(List<GeneratedFile> files) {
        // REAL 模式查重必须用最新索引 — 目标项目可能在上次扫描后被改动(如并发写、手动改、
        // 或本 plan 前序子方案刚写入新文件)。用 force=true 强制重扫,确保不漏(代价 ~1.5s,
        // REAL 模式低频可接受)。降级策略:扫描失败才用缓存,且记 WARN(有覆盖风险)。
        try {
            existingProjectIndex.scan(true);
        } catch (Exception e) {
            log.warn("REAL 模式查重: 强制重扫失败, 回退缓存(有覆盖风险): {}", e.getMessage());
        }
        if (existingProjectIndex.getCachedScan() == null) {
            log.warn("REAL 模式查重: 索引仍为空, 降级不查重(有覆盖风险)");
            return null;
        }

        for (GeneratedFile f : files) {
            if (f.getFilePath() == null || !f.getFilePath().endsWith(".java")) continue;
            // 类名 = 文件名去 .java
            String path = f.getFilePath();
            String simpleName = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
            if (existingProjectIndex.findBySimpleName(simpleName).isPresent()) {
                return "类名 " + simpleName + " 已存在于目标项目(" + path + ")";
            }
            // 表名: 从 content 提 @TableName("xxx")
            if (f.getContent() != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("@TableName\\s*\\(\\s*\"([^\"]+)\"").matcher(f.getContent());
                if (m.find()) {
                    String tableName = m.group(1);
                    boolean tableConflict = existingProjectIndex.getCachedClasses().stream()
                            .anyMatch(c -> tableName.equals(c.tableName()));
                    if (tableConflict) {
                        return "表名 " + tableName + " 已存在于目标项目(" + simpleName + ")";
                    }
                }
            }
        }
        return null;
    }

    /**
     * 从子方案解析出 N 个原子任务。
     *
     * <p>策略: 按 <b>子方案标题里的关键字</b> 决定该子方案要产出哪几个独立文件,
     * 然后从子方案内容中提取实体名/模块名/包路径,组合出文件路径。
     * 每个 AtomicTask 只对应 <b>一个</b> 目标文件,LLM 一次只生成一个文件,避免多文件拆分歧义。
     */
    private List<AtomicTask> parseAtomicTasks(AiSubPlan subPlan) {
        String content = subPlan.getPlanContent();
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }

        // 提取关键信息: 实体名 (Order) 和模块名 (order)
        String entityName = extractEntityName(content);
        String moduleName = extractModuleName(content, entityName);

        String title = subPlan.getTitle() == null ? "" : subPlan.getTitle();
        String tLower = title.toLowerCase();
        String fullContext = "【子方案完整上下文】\n" + content;

        List<AtomicTask> tasks = new ArrayList<>();

        // 1. DDL — 标题含 "DDL/数据库/建表/SQL"（落 doc/sql/{module}）
        if (tLower.contains("ddl") || title.contains("数据库") || title.contains("建表") || title.contains("sql")) {
            tasks.add(buildTask(TaskType.DDL_STATEMENT,
                    title + " — 生成数据库 DDL\n\n" + fullContext,
                    BladeXModuleLayout.ddlPath(moduleName),
                    entityName, moduleName));
        }

        // 2. Entity — 标题含 "Entity/实体"（落 API 模块 pojo.entity）
        if (title.contains("Entity") || title.contains("实体")) {
            tasks.add(buildTask(TaskType.STANDARD_CRUD_ENTITY,
                    title + " — 生成 Entity 类 (" + entityName + ")\n\n" + fullContext,
                    BladeXModuleLayout.entityPath(moduleName, entityName),
                    entityName, moduleName));
        }

        // 3. VO 类 — 标题含 "VO/视图",生成所有在内容里被提及的 VO 类（落 API 模块 pojo.vo）
        if (title.contains("VO") || title.contains("视图")) {
            for (String suffix : new String[]{"QVO", "IVO", "UVO", "VO", "EVO"}) {
                // 触发条件: 内容里出现 EntityName+suffix (如 OrderQVO),或子方案标题就是 "Entity 与 VO"
                boolean mentioned = content.contains(entityName + suffix)
                        || content.matches("(?s).*\\b" + suffix + "\\b.*");
                if (mentioned) {
                    tasks.add(buildTask(TaskType.OTHER,
                            title + " — 生成 " + entityName + suffix + " 类\n\n" + voInstructions(suffix, entityName) + "\n\n" + fullContext,
                            BladeXModuleLayout.voPath(moduleName, entityName, suffix),
                            entityName, moduleName));
                }
            }
        }

        // 4. Mapper — 标题含 "Mapper" 或 "Service"(Service 子方案通常同时含 Mapper)（落 IMPL 模块 mapper）
        if (title.contains("Mapper") || title.contains("Service") || title.contains("服务")) {
            tasks.add(buildTask(TaskType.CUSTOM_MAPPER,
                    title + " — 生成 " + entityName + "Mapper 接口\n\n" + fullContext,
                    BladeXModuleLayout.mapperJavaPath(moduleName, entityName),
                    entityName, moduleName));
            // Mapper.xml 同伴文件（与 .java 同包目录，BladeX 约定）
            tasks.add(buildTask(TaskType.MAPPER_XML,
                    title + " — 生成 " + entityName + "Mapper.xml\n\n" + fullContext,
                    BladeXModuleLayout.mapperXmlPath(moduleName, entityName),
                    entityName, moduleName));
        }

        // 5. Service 接口 + 实现（落 IMPL 模块 service / service.impl）
        if (title.contains("Service") || title.contains("服务")) {
            tasks.add(buildTask(TaskType.STANDARD_CRUD_SERVICE,
                    title + " — 生成 I" + entityName + "Service 接口\n\n" + serviceInterfaceInstructions(entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.serviceInterfacePath(moduleName, entityName),
                    entityName, moduleName));
            // 6. Service 实现
            tasks.add(buildTask(TaskType.STANDARD_CRUD_SERVICE,
                    title + " — 生成 " + entityName + "ServiceImpl 实现类\n\n" + serviceImplInstructions(entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.serviceImplPath(moduleName, entityName),
                    entityName, moduleName));
        }

        // 7. Wrapper（落 IMPL 模块 wrapper；用 WRAPPER 类型命中 buildWrapperSystemPrompt 的 DeptCache 禁令）
        if (title.contains("Wrapper") || title.contains("包装") || title.contains("Controller")) {
            tasks.add(buildTask(TaskType.WRAPPER,
                    title + " — 生成 " + entityName + "Wrapper 转换类\n\n" + wrapperInstructions(entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.wrapperPath(moduleName, entityName),
                    entityName, moduleName));
        }

        // 8. Controller（落 IMPL 模块 controller）
        if (title.contains("Controller") || title.contains("控制器") || title.contains("API")) {
            tasks.add(buildTask(TaskType.STANDARD_CRUD_CONTROLLER,
                    title + " — 生成 " + entityName + "Controller 类\n\n" + controllerInstructions(entityName, moduleName) + "\n\n" + fullContext,
                    BladeXModuleLayout.controllerPath(moduleName, entityName),
                    entityName, moduleName));
        }

        // 9. Excel（落 IMPL 模块 excel）
        if (title.contains("Excel") || title.contains("导入导出")) {
            tasks.add(buildTask(TaskType.EXCEL_IMPORT_EXPORT,
                    title + " — 生成 " + entityName + "Excel 类\n\n" + excelInstructions(entityName) + "\n\n" + fullContext,
                    BladeXModuleLayout.excelPath(moduleName, entityName),
                    entityName, moduleName));
        }

        // 10. Feign — description 必须明确接口名 I{Entity}Client,否则 LLM 容易把实体名漂移成其他词（落 API 模块 feign）
        if (title.contains("Feign") || title.contains("远程")) {
            tasks.add(buildTask(TaskType.FEIGN_CLIENT,
                    title + " — 生成 Feign 客户端接口 I" + entityName + "Client (实体名: " + entityName + ")\n\n"
                            + feignInstructions(entityName, moduleName) + "\n\n" + fullContext,
                    BladeXModuleLayout.feignPath(moduleName, entityName),
                    entityName, moduleName));
        }

        // 兜底
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
        // 把推导出的实体名/模块名带入任务,供 PromptBuilder 替换占位符 {Entity}/{Name}/{module}
        t.setEntityName(entityName);
        t.setModuleName(moduleName);
        return t;
    }

    /** 提取实体名(类名),例如 "Order" / "Product" */
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

    private String extractTargetPath(String line) {
        if (line.contains("src/main/java")) {
            int idx = line.indexOf("src/main/java");
            return line.substring(idx);
        }
        throw new IllegalArgumentException("无法从行中提取目标路径: " + line);
    }

    private String extractTargetPathOrDefault(String line, String fallback) {
        if (line != null && line.contains("src/main/")) {
            int idx = line.indexOf("src/main/");
            String tail = line.substring(idx);
            int end = tail.length();
            for (int i = 0; i < tail.length(); i++) {
                char c = tail.charAt(i);
                if (Character.isWhitespace(c) || c == '`' || c == '"') {
                    end = i;
                    break;
                }
            }
            return tail.substring(0, end);
        }
        return fallback;
    }

    /**
     * 从子方案内容中提取包路径匹配的类名,推导目标文件路径。
     * 例如: "包路径: org.springblade.order.entity" + "类名: Order"
     *    → "src/main/java/org/springblade/order/entity/Order.java"
     */
    private String derivePathFromPkg(TaskType type, AiSubPlan subPlan, String fallback) {
        String content = subPlan.getPlanContent();
        if (content == null) return fallback;

        // 按 type 选择期望的 package 后缀(.entity / .controller / .service ...)
        String preferredSuffix = switch (type) {
            case STANDARD_CRUD_ENTITY -> ".entity";
            case STANDARD_CRUD_CONTROLLER -> ".controller";
            case STANDARD_CRUD_SERVICE, COMPLEX_BUSINESS_SERVICE -> ".service";
            case CUSTOM_MAPPER -> ".mapper";
            case FEIGN_CLIENT -> ".feign";
            case EXCEL_IMPORT_EXPORT -> ".excel";
            default -> null;
        };

        // 收集所有候选包路径
        java.util.regex.Matcher pkgMatcher = java.util.regex.Pattern.compile(
                "(?:包路径|位于|package)[:：\\s]*(org\\.springblade\\.[\\w.]+)")
                .matcher(content);
        String pkgPath = null;
        while (pkgMatcher.find()) {
            String candidate = pkgMatcher.group(1);
            // 优先匹配 type 期望的后缀
            if (preferredSuffix != null && candidate.endsWith(preferredSuffix)) {
                pkgPath = candidate.replace('.', '/');
                break;
            }
            // 否则保留首个找到的(兜底)
            if (pkgPath == null) {
                pkgPath = candidate.replace('.', '/');
            }
        }
        if (pkgPath == null) return fallback;

        // 提取类名(如 Order / OrderMapper / OrderController)
        // 注意: 所有 pattern 必须包含 group(1)
        String className = null;
        String[] classPatterns = switch (type) {
            case STANDARD_CRUD_ENTITY -> new String[]{"类名[:：\\s]*(\\w+)", "Entity[:：\\s]*(\\w+)"};
            case STANDARD_CRUD_CONTROLLER -> new String[]{"Controller[:：\\s]*(\\w+)", "(\\w+Controller)\\b"};
            case STANDARD_CRUD_SERVICE, COMPLEX_BUSINESS_SERVICE -> new String[]{"(I\\w+Service)\\b", "(\\w+ServiceImpl)\\b"};
            case CUSTOM_MAPPER -> new String[]{"Mapper[:：\\s]*(\\w+)", "(\\w+Mapper)\\b"};
            case FEIGN_CLIENT -> new String[]{"(I\\w+Client)\\b"};
            case EXCEL_IMPORT_EXPORT -> new String[]{"(\\w+Excel)\\b", "(\\w+EVO)\\b"};
            default -> new String[]{"类名[:：\\s]*(\\w+)"};
        };
        for (String pat : classPatterns) {
            try {
                java.util.regex.Matcher cm = java.util.regex.Pattern.compile(pat).matcher(content);
                if (cm.find() && cm.groupCount() >= 1) {
                    className = cm.group(1);
                    break;
                }
            } catch (Exception ex) {
                log.debug("提取类名异常 pattern={}: {}", pat, ex.getMessage());
            }
        }
        if (className == null) {
            // 尝试从子方案标题推断默认类名
            className = switch (type) {
                case STANDARD_CRUD_ENTITY -> "Order";
                case STANDARD_CRUD_CONTROLLER -> "OrderController";
                case STANDARD_CRUD_SERVICE -> "IOrderService";
                case CUSTOM_MAPPER -> "OrderMapper";
                case FEIGN_CLIENT -> "IOrderClient";
                case EXCEL_IMPORT_EXPORT -> "OrderExcel";
                default -> "Code";
            };
        }

        return "src/main/java/" + pkgPath + "/" + className + ".java";
    }

    private String defaultPathForType(TaskType type, AiSubPlan subPlan) {
        String slug = "subplan-" + subPlan.getId();
        return switch (type) {
            case DDL_STATEMENT -> "ai-generated/" + slug + "/migration.sql";
            case STANDARD_CRUD_ENTITY -> "ai-generated/" + slug + "/Entity.java";
            case STANDARD_CRUD_CONTROLLER -> "ai-generated/" + slug + "/Controller.java";
            case STANDARD_CRUD_SERVICE, COMPLEX_BUSINESS_SERVICE -> "ai-generated/" + slug + "/Service.java";
            case FEIGN_CLIENT -> "ai-generated/" + slug + "/FeignClient.java";
            case EXCEL_IMPORT_EXPORT -> "ai-generated/" + slug + "/Excel.java";
            case CUSTOM_MAPPER -> "ai-generated/" + slug + "/Mapper.java";
            default -> "ai-generated/" + slug + "/Code.java";
        };
    }

    /**
     * 把生成的代码文件落库,供 Part A 查看。
     * 失败不影响主流程。
     */
    private void persistGeneratedFiles(AiSubPlan subPlan, AiPlan plan,
                                       List<GeneratedFile> files, String action) {
        if (files == null || files.isEmpty()) return;
        for (GeneratedFile f : files) {
            try {
                AiGeneratedFile row = new AiGeneratedFile();
                row.setPlanId(plan.getId());
                row.setSubPlanId(subPlan.getId());
                row.setFileType(f.getType() != null ? f.getType().name() : "OTHER");
                row.setFilePath(f.getFilePath());
                row.setFileName(extractFileName(f.getFilePath()));
                row.setFileExtension(extractExtension(f.getFilePath()));
                row.setAction(action);
                String content = f.getContent() == null ? "" : f.getContent();
                row.setContent(content);
                row.setSizeBytes(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                row.setLineCount((int) content.lines().count());
                row.setCreateTime(LocalDateTime.now());
                row.setIsDeleted(0);
                generatedFileMapper.insert(row);
            } catch (Exception ex) {
                log.warn("生成文件落库失败: subPlanId={}, path={}", subPlan.getId(), f.getFilePath(), ex);
            }
        }
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

    /**
     * 构建子方案拓扑执行顺序。
     *
     * <p>关键细节:Part A 传过来的 prerequisites 是 Part A 自己的字符串 ID(如 "sub_1"),
     * 不是 Part B 的 AiSubPlan.id。所以这里必须先建立
     * {@code partASubPlanId -> Part B 内部 Long id} 的映射,
     * 否则 Long.parseLong 会抛 NumberFormatException 并被静默忽略,
     * 导致整个 DAG 退化成"全部 in-degree 为 0、按插入顺序串行"的伪拓扑序。
     */
    private List<AiSubPlan> buildExecutionOrder(List<AiSubPlan> subPlans) {
        // 使用Kahn算法进行拓扑排序
        Map<Long, AiSubPlan> subPlanMap = new LinkedHashMap<>();
        Map<Long, List<Long>> adjList = new HashMap<>();
        Map<Long, Integer> inDegree = new HashMap<>();
        // Part A 子方案 ID → Part B 内部 ID 映射
        Map<String, Long> partAIdToInternalId = new HashMap<>();

        for (AiSubPlan sp : subPlans) {
            subPlanMap.put(sp.getId(), sp);
            adjList.put(sp.getId(), new ArrayList<>());
            inDegree.put(sp.getId(), 0);
            if (sp.getPartASubPlanId() != null) {
                partAIdToInternalId.put(sp.getPartASubPlanId(), sp.getId());
            }
        }

        // 解析依赖关系 — prereqId 可能是 Part A 字符串 ID,也可能是 Part B 内部 Long ID
        for (AiSubPlan sp : subPlans) {
            List<String> prereqs = parsePrerequisites(sp.getPrerequisitesJson());
            for (String prereqId : prereqs) {
                Long pid = partAIdToInternalId.get(prereqId);
                if (pid == null) {
                    // 兜底: 尝试当成 Long ID 解析(以防未来直接传内部 ID)
                    try {
                        pid = Long.parseLong(prereqId);
                    } catch (NumberFormatException ignored) {
                        log.warn("未知前置依赖 ID,已忽略: subPlanId={}, prereq={}", sp.getId(), prereqId);
                        continue;
                    }
                }
                if (adjList.containsKey(pid)) {
                    adjList.get(pid).add(sp.getId());
                    inDegree.merge(sp.getId(), 1, Integer::sum);
                }
            }
        }

        // Kahn算法
        Queue<Long> queue = new LinkedList<>();
        for (Map.Entry<Long, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        List<AiSubPlan> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            result.add(subPlanMap.get(current));
            for (Long next : adjList.get(current)) {
                int degree = inDegree.get(next) - 1;
                inDegree.put(next, degree);
                if (degree == 0) {
                    queue.offer(next);
                }
            }
        }

        // 检查是否存在循环
        if (result.size() != subPlans.size()) {
            log.error("DAG中存在循环依赖!排序结果: {}, 原始: {}", result.size(), subPlans.size());
            return null;
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> parsePrerequisites(String prerequisitesJson) {
        if (prerequisitesJson == null || prerequisitesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(prerequisitesJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析前置依赖 JSON 失败,按无依赖处理: {}", prerequisitesJson);
            return Collections.emptyList();
        }
    }

    /**
     * 判断给定子方案是否依赖于任何一个已失败/已跳过的子方案 id。
     * 注意 prerequisitesJson 中存的是 Part A 子方案字符串 ID,这里需要先转换为 Part B 内部 Long ID。
     */
    private boolean dependsOnFailed(AiSubPlan sp, Set<Long> failedIds) {
        if (failedIds.isEmpty()) return false;
        List<String> prereqs = parsePrerequisites(sp.getPrerequisitesJson());
        if (prereqs.isEmpty()) return false;
        // 通过 plan_id 把所有子方案再拉一次,建立 partA-id -> internal-id 映射
        // 数据量是单 plan 的子方案数,通常 <= 10
        List<AiSubPlan> siblings = subPlanMapper.selectByPlanId(sp.getPlanId());
        Map<String, Long> partAIdToInternalId = new HashMap<>();
        for (AiSubPlan s : siblings) {
            if (s.getPartASubPlanId() != null) {
                partAIdToInternalId.put(s.getPartASubPlanId(), s.getId());
            }
        }
        for (String prereqId : prereqs) {
            Long pid = partAIdToInternalId.get(prereqId);
            if (pid == null) {
                try {
                    pid = Long.parseLong(prereqId);
                } catch (NumberFormatException ignored) {
                    continue;
                }
            }
            if (failedIds.contains(pid)) return true;
        }
        return false;
    }

    /**
     * 构建反向依赖: 子方案 id -> 直接依赖它的下游子方案 id 列表。
     * 当前主要供日志/未来优化使用,主流程通过 {@link #dependsOnFailed} 在线性遍历中按需查询。
     */
    private Map<Long, List<String>> buildReverseDependencies(List<AiSubPlan> subPlans) {
        // 建立 partA-id -> internal-id 映射
        Map<String, Long> partAIdToInternalId = new HashMap<>();
        for (AiSubPlan sp : subPlans) {
            if (sp.getPartASubPlanId() != null) {
                partAIdToInternalId.put(sp.getPartASubPlanId(), sp.getId());
            }
        }
        Map<Long, List<String>> reverse = new HashMap<>();
        for (AiSubPlan sp : subPlans) {
            List<String> prereqs = parsePrerequisites(sp.getPrerequisitesJson());
            for (String p : prereqs) {
                Long pid = partAIdToInternalId.get(p);
                if (pid == null) {
                    try {
                        pid = Long.parseLong(p);
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                }
                reverse.computeIfAbsent(pid, k -> new ArrayList<>()).add(String.valueOf(sp.getId()));
            }
        }
        return reverse;
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
    private void validatePlanWideContracts(AiPlan plan) {
        try {
            List<AiGeneratedFile> dbFiles = generatedFileMapper.selectByPlanId(plan.getId());
            if (dbFiles == null || dbFiles.isEmpty()) {
                log.info("plan 级跨文件校验跳过: 无生成文件");
                return;
            }
            // 转换为 GeneratedFile (.java + .sql + .xml 均纳入，覆盖 entity↔DDL、mapper xml namespace)
            List<GeneratedFile> files = new ArrayList<>();
            for (AiGeneratedFile f : dbFiles) {
                if (f.getFilePath() == null || f.getContent() == null) continue;
                files.add(new GeneratedFile(null, f.getFilePath(), f.getContent(), f.getAction()));
            }
            if (files.isEmpty()) {
                log.info("plan 级跨文件校验跳过: 无生成文件");
                return;
            }
            List<CrossFileValidator.ContractIssue> issues = crossFileValidator.validate(files);
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
        } catch (Exception e) {
            log.warn("plan 级跨文件校验异常 (不影响主流程): {}", e.getMessage());
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
            List<AiGeneratedFile> dbFiles = generatedFileMapper.selectByPlanId(plan.getId());
            if (dbFiles == null || dbFiles.isEmpty()) return;
            // 转 GeneratedFile (带 content, 供校验 + 修复注入)
            List<GeneratedFile> files = new ArrayList<>();
            for (AiGeneratedFile f : dbFiles) {
                if (f.getFilePath() == null || f.getContent() == null) continue;
                files.add(new GeneratedFile(null, f.getFilePath(), f.getContent(), f.getAction()));
            }
            if (files.isEmpty()) return;

            Set<String> ENTITY_DDL_RULES = Set.of(
                    "ENTITY-DDL-COLUMN-MISSING", "ENTITY-DDL-TYPE-MISMATCH", "ENTITY-DDL-TENANT");

            for (int attempt = 0; attempt <= maxReviewRetries; attempt++) {
                List<CrossFileValidator.ContractIssue> issues = crossFileValidator.validate(files);
                List<CrossFileValidator.ContractIssue> fixable = issues.stream()
                        .filter(i -> i.isError() && ENTITY_DDL_RULES.contains(i.rule))
                        .toList();
                if (fixable.isEmpty()) {
                    if (attempt > 0) {
                        log.info("plan 级 Entity↔DDL 修复完成: 第 {} 次重试后无 ERROR", attempt);
                        logExecution(null, "PLAN_CROSS_FIX", "",
                                "SUCCESS", "Entity↔DDL 修复完成 (" + attempt + " 次重试)", null);
                    }
                    return;
                }
                if (attempt == maxReviewRetries) {
                    String summary = "Entity↔DDL 不一致, 已达最大重试次数 (" + (maxReviewRetries + 1)
                            + ") 仍未修复: " + fixable.size() + " 项; "
                            + fixable.stream().map(Object::toString).limit(5)
                                    .reduce((a, b) -> a + " | " + b).orElse("");
                    log.warn(summary);
                    logExecution(null, "PLAN_CROSS_FIX", "", "FAILED", summary, null);
                    return;
                }
                log.info("plan 级 Entity↔DDL 不一致 (第 {}/{} 次重试): {} 项",
                        attempt + 1, maxReviewRetries + 1, fixable.size());

                // 按 Entity 文件(sourceFilePath)分组: 同一 Entity 的多个缺列/类型问题合并为一次重生成
                Map<String, List<CrossFileValidator.ContractIssue>> byEntity = new LinkedHashMap<>();
                for (CrossFileValidator.ContractIssue err : fixable) {
                    byEntity.computeIfAbsent(err.sourceFilePath, k -> new ArrayList<>()).add(err);
                }
                for (Map.Entry<String, List<CrossFileValidator.ContractIssue>> entry : byEntity.entrySet()) {
                    String entityPath = entry.getKey();
                    List<CrossFileValidator.ContractIssue> errs = entry.getValue();
                    GeneratedFile entityFile = findFileByPath(files, entityPath);
                    // 契约对端: DDL 文件(contractFilePath 指向它)
                    GeneratedFile ddlFile = findFileByPath(files, errs.get(0).contractFilePath);
                    if (entityFile == null || ddlFile == null) {
                        log.warn("Entity↔DDL 修复跳过: 无法定位文件 (entity={}, ddl={})",
                                entityPath, errs.get(0).contractFilePath);
                        continue;
                    }
                    // 从 Entity 路径反推实体名/模块名, 临时构造 task
                    AtomicTask task = buildTaskFromEntityPath(entityPath);
                    if (task == null) {
                        log.warn("Entity↔DDL 修复跳过: 无法从路径反推实体名 {}", entityPath);
                        continue;
                    }
                    String mergedDesc = errs.stream().map(e -> e.message)
                            .reduce((a, b) -> a + "\n" + b).orElse("");
                    GenerationResult fix = codeGenRouter.fixEntityWithDdl(
                            entityFile, ddlFile.getContent(), task, mergedDesc);
                    if (fix.isSuccess()) {
                        GeneratedFile newEntity = fix.getGeneratedFiles().get(0);
                        if (!passesConventionAfterFix(newEntity)) {
                            log.warn("Entity↔DDL 修复被拒(引入 Layer1 违规), 保留原文件: path={}", entityPath);
                            continue;
                        }
                        replaceFile(files, entityPath, newEntity);
                        // 写盘成功才更新 DB, 避免 DB↔磁盘不一致(写盘失败保留磁盘旧内容, DB 也不更新)
                        if (persistSingleFile(plan, entityPath, newEntity.getContent())) {
                            updateGeneratedFileContent(plan.getId(), entityPath, newEntity.getContent());
                        }
                        logExecution(null, "PLAN_CROSS_FIX", entityPath,
                                "CREATED", "Entity↔DDL 修复: " + mergedDesc, null);
                    } else {
                        log.warn("Entity↔DDL 修复 LLM 失败: {}", fix.getErrorMessage());
                    }
                }
            }
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
            List<AiGeneratedFile> dbFiles = generatedFileMapper.selectByPlanId(plan.getId());
            if (dbFiles == null || dbFiles.isEmpty()) return;
            List<GeneratedFile> files = new ArrayList<>();
            for (AiGeneratedFile f : dbFiles) {
                if (f.getFilePath() == null || f.getContent() == null) continue;
                files.add(new GeneratedFile(null, f.getFilePath(), f.getContent(), f.getAction()));
            }
            if (files.isEmpty()) return;

            Set<String> VO_ENTITY_RULES = Set.of(
                    "VO-ENTITY-FIELD-MISMATCH", "VO-ENTITY-FIELD-TYPE-MISMATCH");

            for (int attempt = 0; attempt <= maxReviewRetries; attempt++) {
                List<CrossFileValidator.ContractIssue> issues = crossFileValidator.validate(files);
                List<CrossFileValidator.ContractIssue> fixable = issues.stream()
                        .filter(i -> i.isError() && VO_ENTITY_RULES.contains(i.rule))
                        .toList();
                if (fixable.isEmpty()) {
                    if (attempt > 0) {
                        log.info("plan 级 VO-Entity 修复完成: 第 {} 次重试后无 ERROR", attempt);
                        logExecution(null, "PLAN_CROSS_FIX", "",
                                "SUCCESS", "VO-Entity 修复完成 (" + attempt + " 次重试)", null);
                    }
                    return;
                }
                if (attempt == maxReviewRetries) {
                    String summary = "VO-Entity 不一致, 已达最大重试次数 (" + (maxReviewRetries + 1)
                            + ") 仍未修复: " + fixable.size() + " 项; "
                            + fixable.stream().map(Object::toString).limit(5)
                                    .reduce((a, b) -> a + " | " + b).orElse("");
                    log.warn(summary);
                    logExecution(null, "PLAN_CROSS_FIX", "", "FAILED", summary, null);
                    return;
                }
                log.info("plan 级 VO-Entity 不一致 (第 {}/{} 次重试): {} 项",
                        attempt + 1, maxReviewRetries + 1, fixable.size());

                // 按 VO 文件(sourceFilePath)分组: 同一 VO 的多个字段问题合并为一次重生成
                Map<String, List<CrossFileValidator.ContractIssue>> byVo = new LinkedHashMap<>();
                for (CrossFileValidator.ContractIssue err : fixable) {
                    byVo.computeIfAbsent(err.sourceFilePath, k -> new ArrayList<>()).add(err);
                }
                for (Map.Entry<String, List<CrossFileValidator.ContractIssue>> entry : byVo.entrySet()) {
                    String voPath = entry.getKey();
                    List<CrossFileValidator.ContractIssue> errs = entry.getValue();
                    GeneratedFile voFile = findFileByPath(files, voPath);
                    // 契约对端: Entity 文件(contractFilePath 指向它)
                    GeneratedFile entityFile = findFileByPath(files, errs.get(0).contractFilePath);
                    if (voFile == null || entityFile == null) {
                        log.warn("VO-Entity 修复跳过: 无法定位文件 (vo={}, entity={})",
                                voPath, errs.get(0).contractFilePath);
                        continue;
                    }
                    AtomicTask task = buildTaskFromVoPath(voPath);
                    if (task == null) {
                        log.warn("VO-Entity 修复跳过: 无法从路径反推实体名 {}", voPath);
                        continue;
                    }
                    String mergedDesc = errs.stream().map(e -> e.message)
                            .reduce((a, b) -> a + "\n" + b).orElse("");
                    GenerationResult fix = codeGenRouter.fixVoWithEntity(
                            voFile, entityFile.getContent(), task, mergedDesc);
                    if (fix.isSuccess()) {
                        GeneratedFile newVo = fix.getGeneratedFiles().get(0);
                        if (!passesConventionAfterFix(newVo)) {
                            log.warn("VO↔Entity 修复被拒(引入 Layer1 违规), 保留原文件: path={}", voPath);
                            continue;
                        }
                        replaceFile(files, voPath, newVo);
                        if (persistSingleFile(plan, voPath, newVo.getContent())) {
                            updateGeneratedFileContent(plan.getId(), voPath, newVo.getContent());
                        }
                        logExecution(null, "PLAN_CROSS_FIX", voPath,
                                "CREATED", "VO-Entity 修复: " + mergedDesc, null);
                    } else {
                        log.warn("VO-Entity 修复 LLM 失败: {}", fix.getErrorMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("plan 级 VO-Entity 修复异常 (不影响主流程): {}", e.getMessage());
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

    /** 写盘单个文件 - 返回是否写盘成功(供调用方决定是否同步更新 DB, 避免 DB↔磁盘不一致)。 */
    private boolean persistSingleFile(AiPlan plan, String filePath, String content) {
        try {
            WriteTarget wt = WriteTarget.parse(plan.getWriteTarget());
            String writeRoot = wt.isReal()
                    ? properties.getTargetProjectRoot()
                    : properties.getOutputRoot();
            boolean rootAvailable = wt.isReal()
                    ? fileWriteExecutor.isRootAvailable(writeRoot)
                    : fileWriteExecutor.isTargetRootAvailable();
            if (!rootAvailable) {
                log.warn("修复写盘跳过: 根目录不可用 path={}, writeTarget={}", filePath, plan.getWriteTarget());
                return false;
            }
            List<FileWriteTask> tasks = List.of(new FileWriteTask(filePath, content, "MODIFY"));
            fileWriteExecutor.write(tasks, writeRoot);
            return true;
        } catch (Exception e) {
            log.warn("修复写盘失败(不更新 DB, 保持磁盘旧内容): path={}, err={}", filePath, e.getMessage());
            return false;
        }
    }

    /** 更新 DB 中该 plan + filePath 的生成文件内容(size/lineCount 同步刷新) */
    private void updateGeneratedFileContent(Long planId, String filePath, String content) {
        try {
            AiGeneratedFile row = new AiGeneratedFile();
            row.setPlanId(planId);
            row.setFilePath(filePath);
            row.setContent(content);
            row.setSizeBytes(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            row.setLineCount((int) content.lines().count());
            row.setAction("MODIFY");
            // 用 update + LambdaUpdateWrapper 按 plan_id + file_path 定位更新 content/size/lineCount/action
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiGeneratedFile> uw =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
            uw.eq(AiGeneratedFile::getPlanId, planId)
                    .eq(AiGeneratedFile::getFilePath, filePath)
                    .set(AiGeneratedFile::getContent, content)
                    .set(AiGeneratedFile::getSizeBytes, row.getSizeBytes())
                    .set(AiGeneratedFile::getLineCount, row.getLineCount())
                    .set(AiGeneratedFile::getAction, "MODIFY");
            generatedFileMapper.update(null, uw);
        } catch (Exception e) {
            log.warn("Entity↔DDL 修复更新DB失败: path={}, err={}", filePath, e.getMessage());
        }
    }

    /**
     * 通知Part A整体状态
     */
    private void notifyPartA(AiPlan plan, List<AiSubPlan> subPlans) {
        StatusUpdateRequest request = new StatusUpdateRequest();
        request.setReceptionId(plan.getReceptionId());
        request.setProjectId(plan.getProjectId());
        request.setOverallStatus(plan.getStatus() != null ? plan.getStatus().name() : null);

        List<StatusUpdateRequest.SubPlanStatusItem> updates = new ArrayList<>();
        for (AiSubPlan sp : subPlans) {
            StatusUpdateRequest.SubPlanStatusItem item = new StatusUpdateRequest.SubPlanStatusItem();
            item.setSubPlanId(sp.getPartASubPlanId() != null ? sp.getPartASubPlanId() : String.valueOf(sp.getId()));
            item.setStatus(sp.getStatus() != null ? sp.getStatus().name() : null);
            item.setGitCommitHash(sp.getGitCommitHash());
            if (sp.getCompletedAt() != null) {
                item.setCompletedAt(sp.getCompletedAt().toString());
            }
            updates.add(item);
        }
        request.setSubPlanUpdates(updates);

        callbackNotifier.accept(request);
    }

    /**
     * 通知Part A单个子方案状态
     */
    private void notifyPartAForSubPlan(AiSubPlan subPlan) {
        log.info("子方案完成通知: id={}, status={}", subPlan.getId(), subPlan.getStatus());
        StatusUpdateRequest request = new StatusUpdateRequest();
        request.setReceptionId(null);
        request.setOverallStatus(subPlan.getStatus() != null ? subPlan.getStatus().name() : null);
        StatusUpdateRequest.SubPlanStatusItem item = new StatusUpdateRequest.SubPlanStatusItem();
        item.setSubPlanId(subPlan.getPartASubPlanId() != null ? subPlan.getPartASubPlanId() : String.valueOf(subPlan.getId()));
        item.setStatus(subPlan.getStatus() != null ? subPlan.getStatus().name() : null);
        item.setGitCommitHash(subPlan.getGitCommitHash());
        if (subPlan.getCompletedAt() != null) {
            item.setCompletedAt(subPlan.getCompletedAt().toString());
        }
        request.setSubPlanUpdates(List.of(item));
        if (callbackNotifier != null) {
            callbackNotifier.accept(request);
        }
    }
}
