package org.springblade.aiworkflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 生成的代码文件
 *
 * <p>每次 BladeXCodeAgent 校验通过后,把文件内容落库,供 Part A 拉取查看。
 *
 * @author AI Developer
 */
@Data
@TableName("ai_workflow_generated_file")
public class AiGeneratedFile implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long planId;

    private Long subPlanId;

    private String fileType;

    private String filePath;

    private String fileName;

    private String fileExtension;

    /** CREATED / MODIFIED / SKIPPED */
    private String action;

    private String content;

    private Integer sizeBytes;

    private Integer lineCount;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
