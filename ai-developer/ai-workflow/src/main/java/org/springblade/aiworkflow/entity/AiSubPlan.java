package org.springblade.aiworkflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springblade.aiworkflow.enums.SubPlanStatus;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI工作流-子方案
 *
 * @author AI Developer
 */
@Data
@TableName("ai_workflow_sub_plan")
@Schema(description = "AI工作流-子方案")
public class AiSubPlan implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "关联方案ID")
    private Long planId;

    @Schema(description = "序号")
    private Integer subPlanIndex;

    @Schema(description = "Part A原始子方案ID（用于跨系统关联）")
    private String partASubPlanId;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "子方案内容(Markdown)")
    private String planContent;

    @Schema(description = "前置依赖(JSON)")
    private String prerequisitesJson;

    @Schema(description = "状态: QUEUED/EXECUTING/COMPLETED/FAILED")
    private SubPlanStatus status;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "Git提交哈希")
    private String gitCommitHash;

    @Schema(description = "开始时间")
    private LocalDateTime startedAt;

    @Schema(description = "完成时间")
    private LocalDateTime completedAt;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableLogic
    @Schema(description = "逻辑删除")
    private Integer isDeleted;
}
