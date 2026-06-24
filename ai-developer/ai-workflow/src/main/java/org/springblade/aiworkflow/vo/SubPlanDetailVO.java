package org.springblade.aiworkflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "子方案详情")
public class SubPlanDetailVO {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "关联方案ID")
    private Long planId;

    @Schema(description = "序号")
    private Integer subPlanIndex;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "Git提交哈希")
    private String gitCommitHash;

    @Schema(description = "开始时间")
    private LocalDateTime startedAt;

    @Schema(description = "完成时间")
    private LocalDateTime completedAt;
}
