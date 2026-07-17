package org.springblade.aiworkflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springblade.aiworkflow.enums.PlanStatus;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI工作流-方案
 *
 * @author AI Developer
 */
@Data
@TableName("ai_workflow_plan")
@Schema(description = "AI工作流-方案")
public class AiPlan implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "Part A项目ID")
    private String projectId;

    @Schema(description = "项目名称")
    private String projectName;

    @Schema(description = "总方案内容(Markdown)")
    private String masterPlanContent;

    @Schema(description = "接收编号")
    private String receptionId;

    @Schema(description = "状态: RECEIVED/EXECUTING/COMPLETED/FAILED")
    private PlanStatus status;

    @Schema(description = "来源服务")
    private String sourceService;

    @Schema(description = "Canonical generation identity JSON")
    private String generationIdentityJson;

    @Schema(description = "Reference framework profile JSON")
    private String referenceProfileJson;

    @Schema(description = "Per-reception isolated output directory")
    private String outputDirectory;

    @Schema(description = "Compile verification status")
    private String compileVerificationStatus;

    /**
     * 写入目标 — 阶段2:ISOLATED(落隔离区) / REAL(落目标项目根)。
     * 空/非法按 ISOLATED。决定 executeSubPlan 的写盘 root 与是否查重。
     */
    @Schema(description = "写入目标: ISOLATED / REAL")
    private String writeTarget;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableLogic
    @Schema(description = "逻辑删除")
    private Integer isDeleted;
}
