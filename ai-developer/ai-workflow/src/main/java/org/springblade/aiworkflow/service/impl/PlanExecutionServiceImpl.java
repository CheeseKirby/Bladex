package org.springblade.aiworkflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.agent.BladeXCodeAgent;
import org.springblade.aiworkflow.entity.AiExecutionLog;
import org.springblade.aiworkflow.entity.AiGeneratedFile;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.enums.PlanStatus;
import org.springblade.aiworkflow.enums.SubPlanStatus;
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

    /** 进行中的 receptionId,用于幂等保护,防止控制器/重试导致同一方案并发执行。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public PlanExecutionServiceImpl(AiPlanMapper planMapper, AiSubPlanMapper subPlanMapper,
                                    AiGeneratedFileMapper generatedFileMapper,
                                    AiExecutionLogMapper executionLogMapper,
                                    BladeXCodeAgent bladeXCodeAgent, ObjectMapper objectMapper) {
        this.planMapper = planMapper;
        this.subPlanMapper = subPlanMapper;
        this.generatedFileMapper = generatedFileMapper;
        this.executionLogMapper = executionLogMapper;
        this.bladeXCodeAgent = bladeXCodeAgent;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanReceiveResponse receivePlan(PlanReceiveRequest request) {
        // 使用完整 UUID (32位 hex) 作为 receptionId,避免截断带来的冲突风险。
        // 前缀 "rec-" 保留以保持现有日志/UI 习惯。
        String receptionId = "rec-" + UUID.randomUUID().toString().replace("-", "");

        // 保存方案
        AiPlan plan = new AiPlan();
        plan.setProjectId(request.getProjectId());
        plan.setProjectName(request.getProjectName());
        plan.setReceptionId(receptionId);
        plan.setStatus(PlanStatus.RECEIVED);
        plan.setSourceService(request.getMetadata() != null ? request.getMetadata().getSourceService() : null);
        if (request.getMasterPlan() != null) {
            plan.setMasterPlanContent(request.getMasterPlan().getContent());
        }
        plan.setCreateTime(LocalDateTime.now());
        planMapper.insert(plan);

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
        // 幂等保护: 同一 receptionId 进行中时,后续调用直接跳过,
        // 避免控制器重复触发 / 客户端重试导致并发执行同一工作流。
        if (!inFlight.add(receptionId)) {
            log.warn("工作流已在执行,跳过重复触发: receptionId={}", receptionId);
            return;
        }
        log.info("异步触发工作流执行: receptionId={}", receptionId);
        try {
            bladeXCodeAgent.executeWorkflow(receptionId);
        } catch (Throwable t) {
            // catch Throwable (含 OOM/Assertion/未知异常),确保数据库状态收敛,
            // 否则 EXECUTING 的子方案永远不会被标记 FAILED。
            log.error("工作流执行异常: receptionId={}", receptionId, t);
            markPlanFailed(receptionId, t.getMessage());
        } finally {
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

        Map<Long, List<AiExecutionLog>> logsBySub = allLogs.stream()
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
                    .map(log -> {
                        ExecutionTimelineVO.TimelineStep step = new ExecutionTimelineVO.TimelineStep();
                        step.setId(log.getId());
                        step.setStage(log.getStage());
                        step.setStatus(log.getStatus());
                        step.setAction(log.getAction());
                        step.setFilePath(log.getFilePath());
                        step.setReason(log.getActionReason());
                        step.setCreateTime(log.getCreateTime());
                        return step;
                    })
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
        vo.setSubPlanTimelines(timelines);
        return vo;
    }
}
