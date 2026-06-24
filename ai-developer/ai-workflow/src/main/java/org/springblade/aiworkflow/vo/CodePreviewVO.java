package org.springblade.aiworkflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "代码预览")
public class CodePreviewVO {

    @Schema(description = "子方案ID")
    private Long subPlanId;

    @Schema(description = "预览文件列表")
    private List<FilePreview> files;

    @Data
    @Schema(description = "文件预览")
    public static class FilePreview {
        @Schema(description = "文件路径")
        private String filePath;
        @Schema(description = "文件内容")
        private String content;
        @Schema(description = "操作: CREATE/MODIFY/SKIP")
        private String action;
    }
}
