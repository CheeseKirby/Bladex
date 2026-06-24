package org.springblade.aiworkflow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import org.springblade.aiworkflow.common.ApiResponse;
import org.springblade.aiworkflow.service.IPlanExecutionService;
import org.springblade.aiworkflow.vo.SubPlanDetailVO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
@RequestMapping("/api/execution")
@Validated
@Tag(name = "方案执行", description = "触发和控制方案执行")
public class PlanExecutionController {

    private final IPlanExecutionService planExecutionService;

    @PostMapping("/trigger")
    @Operation(summary = "触发执行方案")
    public ApiResponse<String> trigger(
            @RequestParam @NotBlank(message = "接收编号不能为空") String receptionId) {
        planExecutionService.executeAsync(receptionId);
        return ApiResponse.okMessage("方案已加入执行队列");
    }

    @GetMapping("/sub-plan/{subPlanId}")
    @Operation(summary = "查询子方案详情")
    public ApiResponse<SubPlanDetailVO> subPlanDetail(@PathVariable Long subPlanId) {
        return ApiResponse.ok(planExecutionService.getSubPlanDetail(subPlanId));
    }
}
