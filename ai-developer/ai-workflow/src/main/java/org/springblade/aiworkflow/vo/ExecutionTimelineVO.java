package org.springblade.aiworkflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行进度时间线 — 按子方案分组的执行日志,供 Part A 可视化展示
 */
@Data
@Schema(description = "执行进度时间线")
public class ExecutionTimelineVO {

    @Schema(description = "接收编号")
    private String receptionId;

    @Schema(description = "整体状态")
    private String overallStatus;

    @Schema(description = "子方案数量")
    private int totalSubPlans;

    @Schema(description = "已完成子方案数")
    private int completedSubPlans;

    @Schema(description = "失败子方案数")
    private int failedSubPlans;

    @Schema(description = "按子方案分组的步骤列表")
    private List<SubPlanTimeline> subPlanTimelines;

    @Data
    @Schema(description = "单个子方案的时间线")
    public static class SubPlanTimeline {

        @Schema(description = "数据库 ID")
        private Long subPlanId;

        @Schema(description = "Part A 原始子方案 ID")
        private String partASubPlanId;

        @Schema(description = "序号")
        private Integer index;

        @Schema(description = "标题")
        private String title;

        @Schema(description = "状态: QUEUED/EXECUTING/COMPLETED/FAILED")
        private String status;

        @Schema(description = "失败原因 (status=FAILED 时)")
        private String errorMessage;

        @Schema(description = "已生成文件数")
        private int fileCount;

        @Schema(description = "开始时间")
        private LocalDateTime startedAt;

        @Schema(description = "完成时间")
        private LocalDateTime completedAt;

        @Schema(description = "执行步骤")
        private List<TimelineStep> steps;
    }

    @Data
    @Schema(description = "时间线单步")
    public static class TimelineStep {

        @Schema(description = "日志 ID")
        private Long id;

        @Schema(description = "阶段: CHANGE_EVALUATION / VALIDATION / CODE_GENERATION / FILE_WRITE / ...")
        private String stage;

        @Schema(description = "结果: SUCCESS/FAILED/SKIPPED")
        private String status;

        @Schema(description = "操作: CREATED/MODIFIED/SKIPPED/ROLLED_BACK")
        private String action;

        @Schema(description = "目标文件路径(如有)")
        private String filePath;

        @Schema(description = "原因/详情描述")
        private String reason;

        @Schema(description = "创建时间")
        private LocalDateTime createTime;
    }
}
