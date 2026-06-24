package org.springblade.aiworkflow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.common.ApiResponse;
import org.springblade.aiworkflow.service.IPlanExecutionService;
import org.springblade.aiworkflow.vo.ExecutionStatusVO;
import org.springblade.aiworkflow.vo.ExecutionTimelineVO;
import org.springblade.aiworkflow.vo.GeneratedFileDetailVO;
import org.springblade.aiworkflow.vo.GeneratedFileSummaryVO;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;
import org.springblade.aiworkflow.vo.PlanReceiveResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/api/plans")
@Validated
@Tag(name = "方案接收", description = "接收Part A传来的开发方案")
public class PlanReceiverController {

    private final IPlanExecutionService planExecutionService;

    /**
     * 接收并立即排队执行开发方案。
     *
     * <p>调用流程:
     * 1. {@link IPlanExecutionService#receivePlan} 在自身事务中落库,方法返回时事务已提交;
     * 2. 控制器在事务外调用 {@link IPlanExecutionService#executeAsync},此时异步线程一定能读到刚插入的数据;
     * 3. Part A 拿到 receptionId 后可通过 {@link #status(String)} 轮询执行状态。
     *
     * <p>这里**必须**保持 receivePlan 与 executeAsync 是两次独立调用 — 不要把 executeAsync 内联进 receivePlan,
     * 否则 @Async 自调用会被 Spring AOP 跳过(代理不生效),工作流将变成同步执行,Part A 的 POST 会一直阻塞到全部完成。
     */
    @PostMapping("/receive")
    @Operation(summary = "接收并触发执行开发方案")
    public ApiResponse<PlanReceiveResponse> receive(
            @Valid @RequestBody PlanReceiveRequest request) {
        PlanReceiveResponse response = planExecutionService.receivePlan(request);
        // 方案落库的事务已提交,可以安全异步触发
        try {
            planExecutionService.executeAsync(response.getReceptionId());
            log.info("方案已接收并加入执行队列: receptionId={}", response.getReceptionId());
        } catch (Exception ex) {
            // 触发失败不阻塞 receive 响应,Part A 仍可通过 /status 查看 RECEIVED 状态
            // 也可手动通过 /api/execution/trigger?receptionId=... 重试
            log.error("触发异步执行失败: receptionId={}", response.getReceptionId(), ex);
        }
        return ApiResponse.ok(response);
    }

    @GetMapping("/status")
    @Operation(summary = "查询方案状态")
    public ApiResponse<ExecutionStatusVO> status(
            @RequestParam @NotBlank(message = "接收编号不能为空") String receptionId) {
        ExecutionStatusVO status = planExecutionService.getStatus(receptionId);
        return ApiResponse.ok(status);
    }

    @GetMapping("/{receptionId}/files")
    @Operation(summary = "列出该方案下所有生成的代码文件(不含内容)")
    public ApiResponse<List<GeneratedFileSummaryVO>> listFiles(
            @PathVariable @NotBlank(message = "接收编号不能为空") String receptionId) {
        return ApiResponse.ok(planExecutionService.listGeneratedFiles(receptionId));
    }

    @GetMapping("/files/{fileId}")
    @Operation(summary = "获取单个生成文件完整内容")
    public ApiResponse<GeneratedFileDetailVO> fileDetail(@PathVariable Long fileId) {
        return ApiResponse.ok(planExecutionService.getGeneratedFileDetail(fileId));
    }

    @GetMapping("/{receptionId}/timeline")
    @Operation(summary = "执行进度时间线(各子方案 + 步骤)")
    public ApiResponse<ExecutionTimelineVO> timeline(
            @PathVariable @NotBlank(message = "接收编号不能为空") String receptionId) {
        return ApiResponse.ok(planExecutionService.getTimeline(receptionId));
    }
}
