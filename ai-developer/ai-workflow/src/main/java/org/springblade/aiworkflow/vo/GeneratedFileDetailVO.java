package org.springblade.aiworkflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成文件详情(含内容)
 */
@Data
@Schema(description = "生成文件详情")
public class GeneratedFileDetailVO {

    @Schema(description = "文件 ID")
    private Long id;

    @Schema(description = "子方案 ID")
    private Long subPlanId;

    @Schema(description = "Part A 子方案 ID")
    private String partASubPlanId;

    @Schema(description = "子方案标题")
    private String subPlanTitle;

    @Schema(description = "文件类型")
    private String fileType;

    @Schema(description = "文件路径")
    private String filePath;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "扩展名")
    private String fileExtension;

    @Schema(description = "操作")
    private String action;

    @Schema(description = "字节数")
    private Integer sizeBytes;

    @Schema(description = "行数")
    private Integer lineCount;

    @Schema(description = "完整内容")
    private String content;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
