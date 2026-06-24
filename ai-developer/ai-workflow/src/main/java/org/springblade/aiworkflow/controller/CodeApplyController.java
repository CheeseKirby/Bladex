package org.springblade.aiworkflow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springblade.aiworkflow.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
@RequestMapping("/api/code-apply")
@Tag(name = "代码应用", description = "将生成的代码写入文件系统 — 占位接口,尚未实现")
public class CodeApplyController {

    /**
     * 应用代码 — 占位实现。
     * 当前主流程由 {@code BladeXCodeAgent} 在异步执行时直接通过 FileWriteExecutor 写入,
     * 该端点保留给 "先预览再写入" 工作流的人工确认环节使用。
     */
    @PostMapping("/apply")
    @Operation(summary = "应用代码到文件系统 (未实现)")
    public ApiResponse<String> apply(@RequestParam Long subPlanId) {
        return ApiResponse.fail(501, "代码应用功能尚未实现 (subPlanId=" + subPlanId + ")");
    }
}
