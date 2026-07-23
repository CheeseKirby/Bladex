package org.springblade.aiworkflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.agent.BladeXCodeAgent;
import org.springblade.aiworkflow.agent.GenerationIdentity;
import org.springblade.aiworkflow.agent.GenerationIdentityResolver;
import org.springblade.aiworkflow.agent.ReferenceFrameworkProfile;
import org.springblade.aiworkflow.agent.ProjectWriteLockManager;
import org.springblade.aiworkflow.config.AiWorkflowProperties;
import org.springblade.aiworkflow.entity.AiExecutionLog;
import org.springblade.aiworkflow.entity.AiGeneratedFile;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.enums.PlanStatus;
import org.springblade.aiworkflow.enums.SubPlanStatus;
import org.springblade.aiworkflow.enums.WriteTarget;
import org.springblade.aiworkflow.mapper.AiExecutionLogMapper;
import org.springblade.aiworkflow.mapper.AiGeneratedFileMapper;
import org.springblade.aiworkflow.mapper.AiPlanMapper;
import org.springblade.aiworkflow.mapper.AiSubPlanMapper;
import org.springblade.aiworkflow.service.IPlanExecutionService;
import org.springblade.aiworkflow.vo.ExecutionStatusVO;
import org.springblade.aiworkflow.vo.ExecutionTimelineVO;
import org.springblade.aiworkflow.vo.GeneratedFileDetailVO;
import org.springblade.aiworkflow.vo.GeneratedFileSummaryVO;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;
import org.springblade.aiworkflow.vo.PlanReceiveResponse;
import org.springblade.aiworkflow.vo.SubPlanDetailVO;
import org.springblade.aiworkflow.validation.CanonicalJsonHasher;
import org.springblade.aiworkflow.validation.PlanRequestValidator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 方案执行服务实现。
 *
 * <p>本类承担三类职责(为保持向后兼容暂未拆分):
 * 1. 方案接收(receivePlan) — 在独立事务内落库;
 * 2. 异步执行(executeAsync) — 由 {@link org.springframework.scheduling.annotation.Async} 提交到 aiWorkflowExecutor;
 * 3. 查询(状态/详情/文件/时间线) — 只读 DB。
 *
 * <p>关键修复:
 * - 使用完整 UUID 作为 receptionId,避免截断后的冲突;
 * - 用 ConcurrentHashMap 做 in-flight 互斥,杜绝同一 receptionId 重复触发执行;
 * - 异步执行入口对任意 Throwable 兜底,失败时把 plan + 所有 EXECUTING 子方案一起标 FAILED,
 *   避免任务因为 RuntimeException 卡死在 EXECUTING 永不恢复。
 */
@Slf4j
@Service
public class PlanExecutionServiceImpl implements IPlanExecutionService {

    private final AiPlanMapper planMapper;
    private final AiSubPlanMapper subPlanMapper;
    private final AiGeneratedFileMapper generatedFileMapper;
    private final AiExecutionLogMapper executionLogMapper;
    private final BladeXCodeAgent bladeXCodeAgent;
    private final ObjectMapper objectMapper;
    private final ProjectWriteLockManager projectWriteLockManager;
    private final AiWorkflowProperties properties;
    private final PlanRequestValidator planRequestValidator;

    /** 进行中的 receptionId,用于幂等保护,防止控制器/重试导致同一方案并发执行。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public PlanExecutionServiceImpl(AiPlanMapper planMapper, AiSubPlanMapper subPlanMapper,
                                    AiGeneratedFileMapper generatedFileMapper,
                                    AiExecutionLogMapper executionLogMapper,
                                    BladeXCodeAgent bladeXCodeAgent, ObjectMapper objectMapper,
                                    ProjectWriteLockManager projectWriteLockManager,
                                    AiWorkflowProperties properties,
                                    PlanRequestValidator planRequestValidator) {
        this.planMapper = planMapper;
        this.subPlanMapper = subPlanMapper;
        this.generatedFileMapper = generatedFileMapper;
        this.executionLogMapper = executionLogMapper;
        this.bladeXCodeAgent = bladeXCodeAgent;
        this.objectMapper = objectMapper;
        this.projectWriteLockManager = projectWriteLockManager;
        this.properties = properties;
        this.planRequestValidator = planRequestValidator;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanReceiveResponse receivePlan(PlanReceiveRequest request) {
        planRequestValidator.validate(request);
        // Database-backed idempotency closes the check-then-insert race across processes.
        // FAILED rows release their key lazily on the next retry; MySQL/H2 unique indexes permit multiple NULLs.
        String idempotencyKey = CanonicalJsonHasher.idempotencyKey(request);
        List<AiPlan> existingPlans = planMapper.selectList(new LambdaQueryWrapper<AiPlan>()
                .eq(AiPlan::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (!existingPlans.isEmpty()) {
            AiPlan existing = existingPlans.get(0);
            if (existing.getStatus() != PlanStatus.FAILED) {
                log.info("Duplicate plan intake resolved by idempotency key: receptionId={}, projectId={}",
                        existing.getReceptionId(), request.getProjectId());
                return existingResponse(existing);
            }
            planMapper.update(null, new UpdateWrapper<AiPlan>()
                    .eq("id", existing.getId())
                    .eq("idempotency_key", idempotencyKey)
                    .eq("status", PlanStatus.FAILED.name())
                    .set("idempotency_key", null));
        }

        // 使用完整 UUID (32位 hex) 作为 receptionId,避免截断带来的冲突风险。
        // 前缀 "rec-" 保留以保持现有日志/UI 习惯。
        String receptionId = "rec-" + UUID.randomUUID().toString().replace("-", "");

        // 保存方案
        AiPlan plan = new AiPlan();
        plan.setProjectId(request.getProjectId());
        plan.setProjectName(request.getProjectName());
        plan.setReceptionId(receptionId);
        plan.setIdempotencyKey(idempotencyKey);
        plan.setStatus(PlanStatus.RECEIVED);
        plan.setSourceService(request.getMetadata() != null ? request.getMetadata().getSourceService() : null);
        GenerationIdentity generationIdentity = request.getCanonicalContract() != null
                ? request.getCanonicalContract().generationIdentity()
                : GenerationIdentityResolver.resolve(request);
        try {
            plan.setGenerationIdentityJson(objectMapper.writeValueAsString(generationIdentity));
            if (request.getCanonicalContract() != null) {
                plan.setCanonicalContractJson(objectMapper.writeValueAsString(request.getCanonicalContract()));
            }
            if (request.getReviewManifest() != null) {
                plan.setReviewManifestJson(objectMapper.writeValueAsString(request.getReviewManifest()));
            }
            plan.setBundleHash(request.getBundleHash());
            plan.setBundleSignature(request.getBundleSignature());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize canonical plan metadata", e);
        }
        plan.setCompileVerificationStatus("NOT_RUN");
        if (request.getMasterPlan() != null) {
            plan.setMasterPlanContent(request.getMasterPlan().getContent());
        }
        // 阶段2: 写入目标(空/非法→ISOLATED,安全默认)。决定后续写盘 root 与是否查重。
        plan.setWriteTarget(WriteTarget.parse(request.getWriteTarget()).getCode());
        plan.setCreateTime(LocalDateTime.now());
        try {
            planMapper.insert(plan);
        } catch (DuplicateKeyException duplicate) {
            AiPlan winner = planMapper.selectByIdempotencyKeyForUpdate(idempotencyKey);
            if (winner == null) throw duplicate;
            log.info("Concurrent duplicate plan intake resolved to winner: receptionId={}, projectId={}",
                    winner.getReceptionId(), request.getProjectId());
            return existingResponse(winner);
        }

        // 保存子方案
        Map<String, String> subPlanStatuses = new LinkedHashMap<>();
        if (request.getSubPlans() != null) {
            for (PlanReceiveRequest.SubPlanVO sp : request.getSubPlans()) {
                AiSubPlan subPlan = new AiSubPlan();
                subPlan.setPlanId(plan.getId());
                subPlan.setPartASubPlanId(sp.getId());
                subPlan.setSubPlanIndex(sp.getIndex());
                subPlan.setTitle(sp.getTitle());
                subPlan.setPlanContent(sp.getContent());
                subPlan.setStatus(SubPlanStatus.QUEUED);
                subPlan.setCreateTime(LocalDateTime.now());

                // 序列化前置依赖
                if (sp.getPrerequisites() != null && !sp.getPrerequisites().isEmpty()) {
                    try {
                        subPlan.setPrerequisitesJson(objectMapper.writeValueAsString(sp.getPrerequisites()));
                    } catch (JsonProcessingException e) {
                        log.warn("序列化前置依赖失败 (sub_plan_id={}): {}", sp.getId(), e.getMessage());
                        subPlan.setPrerequisitesJson("[]");
                    }
                } else {
                    subPlan.setPrerequisitesJson("[]");
                }
                try {
                    subPlan.setDeliverableIdsJson(objectMapper.writeValueAsString(
                            sp.getDeliverableIds() == null ? List.of() : sp.getDeliverableIds()));
                    subPlan.setReferencedElementIdsJson(objectMapper.writeValueAsString(
                            sp.getReferencedElementIds() == null ? List.of() : sp.getReferencedElementIds()));
                    subPlan.setInputTypesJson(objectMapper.writeValueAsString(
                            sp.getInputTypes() == null ? List.of() : sp.getInputTypes()));
                    subPlan.setOutputTypesJson(objectMapper.writeValueAsString(
                            sp.getOutputTypes() == null ? List.of() : sp.getOutputTypes()));
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("Unable to serialize canonical sub-plan metadata: " + sp.getId(), e);
                }
                subPlan.setContractHash(sp.getContractHash());

                subPlanMapper.insert(subPlan);
                subPlanStatuses.put(sp.getId(), SubPlanStatus.QUEUED.name());
            }
        }

        // 构建响应
        PlanReceiveResponse response = new PlanReceiveResponse();
        response.setReceptionId(receptionId);
        response.setStatus(PlanStatus.RECEIVED.name());
        response.setSubPlanStatuses(subPlanStatuses);

        log.info("方案接收完成: receptionId={}, subPlanCount={}", receptionId, subPlanStatuses.size());
        return response;
    }

    private PlanReceiveResponse existingResponse(AiPlan plan) {
        PlanReceiveResponse response = new PlanReceiveResponse();
        response.setReceptionId(plan.getReceptionId());
        response.setStatus(plan.getStatus() == null ? PlanStatus.RECEIVED.name() : plan.getStatus().name());
        Map<String, String> statuses = new LinkedHashMap<>();
        if (plan.getId() != null) {
            List<AiSubPlan> subPlans = subPlanMapper.selectByPlanId(plan.getId());
            if (subPlans != null) {
                for (AiSubPlan subPlan : subPlans) {
                    String id = subPlan.getPartASubPlanId() == null
                            ? String.valueOf(subPlan.getId()) : subPlan.getPartASubPlanId();
                    statuses.put(id, subPlan.getStatus() == null
                            ? SubPlanStatus.QUEUED.name() : subPlan.getStatus().name());
                }
            }
        }
        response.setSubPlanStatuses(statuses);
        return response;
    }

    @Override
    public ExecutionStatusVO getStatus(String receptionId) {
        AiPlan plan = planMapper.selectOne(
                new LambdaQueryWrapper<AiPlan>().eq(AiPlan::getReceptionId, receptionId));
        if (plan == null) {
            throw new IllegalArgumentException("方案不存在: " + receptionId);
        }

        List<AiSubPlan> subPlans = subPlanMapper.selectByPlanId(plan.getId());

        ExecutionStatusVO vo = new ExecutionStatusVO();
        vo.setReceptionId(receptionId);
        vo.setProjectId(plan.getProjectId());
        vo.setOverallStatus(plan.getStatus() != null ? plan.getStatus().name() : null);
        vo.setSubPlanUpdates(subPlans.stream().map(sp -> {
            ExecutionStatusVO.SubPlanStatusItem item = new ExecutionStatusVO.SubPlanStatusItem();
            item.setSubPlanId(sp.getPartASubPlanId() != null ? sp.getPartASubPlanId() : String.valueOf(sp.getId()));
            item.setStatus(sp.getStatus() != null ? sp.getStatus().name() : null);
            item.setGitCommitHash(sp.getGitCommitHash());
            if (sp.getCompletedAt() != null) {
                item.setCompletedAt(sp.getCompletedAt().toString());
            }
            return item;
        }).collect(Collectors.toList()));

        return vo;
    }

    @Override
    @Async("aiWorkflowExecutor")
    public void executeAsync(String receptionId) {
        if (!inFlight.add(receptionId)) {
            log.warn("Workflow is already executing in this process; duplicate trigger skipped: receptionId={}", receptionId);
            return;
        }

        java.util.concurrent.locks.ReentrantLock writeLock = null;
        boolean claimAcquired = false;
        try {
            int claimed = planMapper.update(null, new UpdateWrapper<AiPlan>()
                    .eq("reception_id", receptionId)
                    .eq("status", PlanStatus.RECEIVED.name())
                    .set("status", PlanStatus.EXECUTING.name())
                    .set("update_time", LocalDateTime.now()));
            if (claimed != 1) {
                log.warn("Workflow execution claim was not acquired; duplicate or non-RECEIVED trigger skipped: receptionId={}",
                        receptionId);
                return;
            }
            claimAcquired = true;
            log.info("Workflow execution claim acquired: receptionId={}", receptionId);

            AiPlan plan = planMapper.selectOne(
                    new LambdaQueryWrapper<AiPlan>().eq(AiPlan::getReceptionId, receptionId));
            if (plan == null) {
                throw new IllegalStateException("Claimed plan disappeared before execution: " + receptionId);
            }
            if (WriteTarget.parse(plan.getWriteTarget()).isReal()) {
                if (projectWriteLockManager == null) {
                    throw new IllegalStateException("Project write lock manager is unavailable for REAL execution");
                }
                writeLock = projectWriteLockManager.lockFor(properties.getTargetProjectRoot());
                writeLock.lock();
                log.info("REAL project write lock acquired: receptionId={}, root={}",
                        receptionId, properties.getTargetProjectRoot());
            }
            bladeXCodeAgent.executeWorkflow(receptionId);
        } catch (Throwable error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (claimAcquired) {
                log.error("Workflow execution failed after database claim: receptionId={}", receptionId, error);
                markPlanFailed(receptionId, error.getMessage());
            } else {
                log.error("Workflow execution claim failed: receptionId={}", receptionId, error);
            }
        } finally {
            if (writeLock != null && writeLock.isHeldByCurrentThread()) writeLock.unlock();
            inFlight.remove(receptionId);
        }
    }

    /**
     * 把方案与未完成的子方案统一置为 FAILED,在 executeAsync 兜底路径使用。
     */
    private void markPlanFailed(String receptionId, String errorMessage) {
        try {
            AiPlan plan = planMapper.selectOne(
                    new LambdaQueryWrapper<AiPlan>().eq(AiPlan::getReceptionId, receptionId));
            if (plan == null) return;
            if (plan.getStatus() != PlanStatus.COMPLETED && plan.getStatus() != PlanStatus.FAILED) {
                plan.setStatus(PlanStatus.FAILED);
                planMapper.updateById(plan);
            }
            // 把所有尚未终态的子方案也一并标 FAILED,避免 UI 卡在 EXECUTING/QUEUED
            List<AiSubPlan> subPlans = subPlanMapper.selectByPlanId(plan.getId());
            for (AiSubPlan sp : subPlans) {
                if (sp.getStatus() == null
                        || sp.getStatus() == SubPlanStatus.QUEUED
                        || sp.getStatus() == SubPlanStatus.EXECUTING) {
                    sp.setStatus(SubPlanStatus.FAILED);
                    if (sp.getErrorMessage() == null) {
                        sp.setErrorMessage("工作流异常终止: " + (errorMessage == null ? "(no message)" : errorMessage));
                    }
                    if (sp.getCompletedAt() == null) {
                        sp.setCompletedAt(LocalDateTime.now());
                    }
                    subPlanMapper.updateById(sp);
                }
            }
        } catch (Exception ex) {
            log.error("标记方案 FAILED 失败: receptionId={}", receptionId, ex);
        }
    }

    @Override
    public SubPlanDetailVO getSubPlanDetail(Long subPlanId) {
        AiSubPlan subPlan = subPlanMapper.selectById(subPlanId);
        if (subPlan == null) {
            throw new IllegalArgumentException("子方案不存在: " + subPlanId);
        }
        SubPlanDetailVO vo = new SubPlanDetailVO();
        vo.setId(subPlan.getId());
        vo.setPlanId(subPlan.getPlanId());
        vo.setSubPlanIndex(subPlan.getSubPlanIndex());
        vo.setTitle(subPlan.getTitle());
        vo.setStatus(subPlan.getStatus() != null ? subPlan.getStatus().name() : null);
        vo.setGitCommitHash(subPlan.getGitCommitHash());
        vo.setStartedAt(subPlan.getStartedAt());
        vo.setCompletedAt(subPlan.getCompletedAt());
        return vo;
    }

    @Override
    public List<GeneratedFileSummaryVO> listGeneratedFiles(String receptionId) {
        AiPlan plan = planMapper.selectOne(
                new LambdaQueryWrapper<AiPlan>().eq(AiPlan::getReceptionId, receptionId));
        if (plan == null) {
            throw new IllegalArgumentException("方案不存在: " + receptionId);
        }
        List<AiGeneratedFile> files = generatedFileMapper.selectByPlanIdWithoutContent(plan.getId());
        // 提前拉一次子方案,便于附标题/Part A 子方案 ID
        Map<Long, AiSubPlan> subPlanMap = subPlanMapper.selectByPlanId(plan.getId()).stream()
                .collect(Collectors.toMap(AiSubPlan::getId, sp -> sp));
        return files.stream().map(f -> {
            GeneratedFileSummaryVO vo = new GeneratedFileSummaryVO();
            vo.setId(f.getId());
            vo.setSubPlanId(f.getSubPlanId());
            AiSubPlan sp = subPlanMap.get(f.getSubPlanId());
            if (sp != null) {
                vo.setPartASubPlanId(sp.getPartASubPlanId());
                vo.setSubPlanTitle(sp.getTitle());
            }
            vo.setFileType(f.getFileType());
            vo.setFilePath(f.getFilePath());
            vo.setFileName(f.getFileName());
            vo.setFileExtension(f.getFileExtension());
            vo.setAction(f.getAction());
            vo.setSizeBytes(f.getSizeBytes());
            vo.setLineCount(f.getLineCount());
            vo.setCreateTime(f.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public GeneratedFileDetailVO getGeneratedFileDetail(Long fileId) {
        AiGeneratedFile f = generatedFileMapper.selectById(fileId);
        if (f == null || (f.getIsDeleted() != null && f.getIsDeleted() == 1)) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        AiSubPlan sp = subPlanMapper.selectById(f.getSubPlanId());
        GeneratedFileDetailVO vo = new GeneratedFileDetailVO();
        vo.setId(f.getId());
        vo.setSubPlanId(f.getSubPlanId());
        if (sp != null) {
            vo.setPartASubPlanId(sp.getPartASubPlanId());
            vo.setSubPlanTitle(sp.getTitle());
        }
        vo.setFileType(f.getFileType());
        vo.setFilePath(f.getFilePath());
        vo.setFileName(f.getFileName());
        vo.setFileExtension(f.getFileExtension());
        vo.setAction(f.getAction());
        vo.setSizeBytes(f.getSizeBytes());
        vo.setLineCount(f.getLineCount());
        vo.setContent(f.getContent());
        vo.setCreateTime(f.getCreateTime());
        return vo;
    }

    @Override
    public ExecutionTimelineVO getTimeline(String receptionId) {
        AiPlan plan = planMapper.selectOne(
                new LambdaQueryWrapper<AiPlan>().eq(AiPlan::getReceptionId, receptionId));
        if (plan == null) {
            throw new IllegalArgumentException("方案不存在: " + receptionId);
        }

        List<AiSubPlan> subPlans = subPlanMapper.selectByPlanId(plan.getId());
        List<AiExecutionLog> allLogs = executionLogMapper.selectByPlanId(plan.getId());
        List<AiGeneratedFile> allFiles = generatedFileMapper.selectByPlanIdWithoutContent(plan.getId());

        List<AiExecutionLog> planLogs = allLogs.stream()
                .filter(logEntry -> logEntry.getSubPlanId() == null)
                .toList();
        Map<Long, List<AiExecutionLog>> logsBySub = allLogs.stream()
                .filter(logEntry -> logEntry.getSubPlanId() != null)
                .collect(Collectors.groupingBy(AiExecutionLog::getSubPlanId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, Long> fileCountBySub = allFiles.stream()
                .collect(Collectors.groupingBy(AiGeneratedFile::getSubPlanId, Collectors.counting()));

        List<ExecutionTimelineVO.SubPlanTimeline> timelines = new ArrayList<>();
        int completed = 0;
        int failed = 0;
        for (AiSubPlan sp : subPlans) {
            ExecutionTimelineVO.SubPlanTimeline line = new ExecutionTimelineVO.SubPlanTimeline();
            line.setSubPlanId(sp.getId());
            line.setPartASubPlanId(sp.getPartASubPlanId());
            line.setIndex(sp.getSubPlanIndex());
            line.setTitle(sp.getTitle());
            line.setStatus(sp.getStatus() != null ? sp.getStatus().name() : null);
            line.setErrorMessage(sp.getErrorMessage());
            line.setStartedAt(sp.getStartedAt());
            line.setCompletedAt(sp.getCompletedAt());
            line.setFileCount(Optional.ofNullable(fileCountBySub.get(sp.getId())).orElse(0L).intValue());

            List<ExecutionTimelineVO.TimelineStep> steps = Optional.ofNullable(logsBySub.get(sp.getId()))
                    .orElseGet(Collections::emptyList)
                    .stream()
                    .map(this::toTimelineStep)
                    .collect(Collectors.toList());
            line.setSteps(steps);
            timelines.add(line);

            if (sp.getStatus() == SubPlanStatus.COMPLETED) completed++;
            if (sp.getStatus() == SubPlanStatus.FAILED) failed++;
        }

        ExecutionTimelineVO vo = new ExecutionTimelineVO();
        vo.setReceptionId(receptionId);
        vo.setOverallStatus(plan.getStatus() != null ? plan.getStatus().name() : null);
        vo.setTotalSubPlans(subPlans.size());
        vo.setCompletedSubPlans(completed);
        vo.setFailedSubPlans(failed);
        vo.setOutputDirectory(plan.getOutputDirectory());
        vo.setCompileVerificationStatus(plan.getCompileVerificationStatus());
        vo.setQualityErrorCount(plan.getQualityErrorCount());
        vo.setQualityWarningCount(plan.getQualityWarningCount());
        try {
            if (plan.getGenerationIdentityJson() != null) {
                GenerationIdentity identity = objectMapper.readValue(plan.getGenerationIdentityJson(), GenerationIdentity.class);
                vo.setModuleName(identity.moduleName());
                vo.setEntityName(identity.entityName());
                vo.setTableName(identity.tableName());
                vo.setBasePackage(identity.basePackage());
            }
            if (plan.getReferenceProfileJson() != null) {
                ReferenceFrameworkProfile profile = objectMapper.readValue(
                        plan.getReferenceProfileJson(), ReferenceFrameworkProfile.class);
                vo.setFrameworkVersion(profile.bladeXVersion());
                vo.setJavaVersion(profile.javaVersion());
            }
        } catch (JsonProcessingException e) {
            log.warn("Unable to deserialize generation quality metadata for receptionId={}: {}",
                    receptionId, e.getMessage());
        }
        vo.setPlanSteps(planLogs.stream().map(this::toTimelineStep).toList());
        vo.setSubPlanTimelines(timelines);
        return vo;
    }

    private ExecutionTimelineVO.TimelineStep toTimelineStep(AiExecutionLog logEntry) {
        ExecutionTimelineVO.TimelineStep step = new ExecutionTimelineVO.TimelineStep();
        step.setId(logEntry.getId());
        step.setStage(logEntry.getStage());
        step.setStatus(logEntry.getStatus());
        step.setAction(logEntry.getAction());
        step.setFilePath(logEntry.getFilePath());
        step.setReason(logEntry.getActionReason());
        step.setCreateTime(logEntry.getCreateTime());
        return step;
    }
}
