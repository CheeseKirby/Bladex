package org.springblade.aiworkflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "方案接收请求")
public class PlanReceiveRequest {

    @NotBlank(message = "项目ID不能为空")
    @Schema(description = "Part A项目ID")
    private String projectId;

    @NotBlank(message = "项目名称不能为空")
    @Schema(description = "项目名称")
    private String projectName;

    @Valid
    @Schema(description = "总方案")
    private MasterPlanVO masterPlan;

    @Valid
    @Schema(description = "子方案列表")
    private List<SubPlanVO> subPlans;

    @Schema(description = "元数据")
    private MetadataVO metadata;

    @Data
    @Schema(description = "总方案")
    public static class MasterPlanVO {
        @Schema(description = "方案ID")
        private String id;
        @Schema(description = "版本号")
        private Integer version;
        @Schema(description = "方案内容(Markdown)")
        private String content;
    }

    @Data
    @Schema(description = "子方案")
    public static class SubPlanVO {
        @Schema(description = "子方案ID")
        private String id;
        @Schema(description = "序号")
        private Integer index;
        @Schema(description = "标题")
        private String title;
        @Schema(description = "子方案内容(Markdown)")
        private String content;
        @Schema(description = "前置依赖子方案ID列表")
        private List<String> prerequisites;
    }

    @Data
    @Schema(description = "元数据")
    public static class MetadataVO {
        @Schema(description = "来源服务")
        private String sourceService;
        @Schema(description = "生成模型")
        private String generatedBy;
        @Schema(description = "传输时间")
        private String transmittedAt;
    }
}
