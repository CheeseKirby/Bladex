package org.springblade.aiworkflow.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件写入任务
 *
 * @author AI Developer
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileWriteTask {

    /** 目标文件路径（相对于target-project-root） */
    private String targetPath;

    /** 文件内容 */
    private String content;

    /** 操作类型 */
    private String action;
}
