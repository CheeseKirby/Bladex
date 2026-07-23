package org.springblade.aiworkflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI工作流-执行日志
 *
 * @author AI Developer
 */
@Data
@TableName("ai_workflow_execution_log")
@Schema(description = "AI工作流-执行日志")
public class AiExecutionLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "Associated plan ID for plan-level events")
    private Long planId;

    @Schema(description = "关联子方案ID")
    private Long subPlanId;

    @Schema(description = "阶段: CHANGE_EVALUATION/CODE_GENERATION/VALIDATION/BUILD_VERIFY/SELF_REVIEW")
    private String stage;

    @Schema(description = "目标文件路径")
    private String filePath;

    @Schema(description = "操作: CREATED/MODIFIED/SKIPPED/ROLLED_BACK")
    private String action;

    @Schema(description = "操作原因")
    private String actionReason;

    @Schema(description = "LLM提示词")
    private String llmPrompt;

    @Schema(description = "LLM响应")
    private String llmResponse;

    @Schema(description = "校验结果(JSON)")
    private String validationResult;

    @Schema(description = "编译输出")
    private String buildOutput;

    @Schema(description = "结果: SUCCESS/FAILED/SKIPPED")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableLogic
    @Schema(description = "逻辑删除")
    private Integer isDeleted;
}
