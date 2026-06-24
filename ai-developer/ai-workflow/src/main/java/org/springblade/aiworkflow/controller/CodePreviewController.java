package org.springblade.aiworkflow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springblade.aiworkflow.common.ApiResponse;
import org.springblade.aiworkflow.vo.CodePreviewVO;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
@RequestMapping("/api/code-preview")
@Tag(name = "代码预览", description = "预览生成的代码（不写入文件系统）— 占位接口,尚未实现")
public class CodePreviewController {

    /**
     * 预览生成代码 — 占位实现。
     * 当前主流程由 {@code BladeXCodeAgent} 在异步执行时直接生成并写入,
     * 该端点保留为后续 "先预览再确认" 工作流的扩展点。
     */
    @PostMapping("/preview")
    @Operation(summary = "预览生成代码 (未实现)")
    public ApiResponse<CodePreviewVO> preview(@RequestParam Long subPlanId) {
        return ApiResponse.fail(501, "代码预览功能尚未实现 (subPlanId=" + subPlanId + ")");
    }
}
