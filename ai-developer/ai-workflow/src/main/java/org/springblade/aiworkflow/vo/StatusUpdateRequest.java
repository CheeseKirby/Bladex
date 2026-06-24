package org.springblade.aiworkflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Part A回调的状态更新请求
 *
 * @author AI Developer
 */
@Data
@Schema(description = "状态更新请求(回调Part A)")
public class StatusUpdateRequest {

    @NotBlank(message = "接收编号不能为空")
    @Schema(description = "接收编号")
    private String receptionId;

    @NotBlank(message = "项目ID不能为空")
    @Schema(description = "项目ID")
    private String projectId;

    @Schema(description = "整体状态")
    private String overallStatus;

    @Schema(description = "子方案状态更新列表")
    private List<SubPlanStatusItem> subPlanUpdates;

    @Data
    @Schema(description = "子方案状态项")
    public static class SubPlanStatusItem {
        @Schema(description = "子方案ID(Part A原始ID)")
        private String subPlanId;
        @Schema(description = "状态")
        private String status;
        @Schema(description = "Git提交哈希")
        private String gitCommitHash;
        @Schema(description = "完成时间")
        private String completedAt;
    }
}
