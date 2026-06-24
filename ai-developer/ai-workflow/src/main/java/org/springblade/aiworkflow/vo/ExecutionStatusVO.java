package org.springblade.aiworkflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "执行状态")
public class ExecutionStatusVO {

    @Schema(description = "接收编号")
    private String receptionId;

    @Schema(description = "项目ID")
    private String projectId;

    @Schema(description = "整体状态")
    private String overallStatus;

    @Schema(description = "子方案状态更新列表")
    private List<SubPlanStatusItem> subPlanUpdates;

    @Data
    @Schema(description = "子方案状态项")
    public static class SubPlanStatusItem {
        @Schema(description = "子方案ID")
        private String subPlanId;
        @Schema(description = "状态")
        private String status;
        @Schema(description = "Git提交哈希")
        private String gitCommitHash;
        @Schema(description = "完成时间")
        private String completedAt;
    }
}
