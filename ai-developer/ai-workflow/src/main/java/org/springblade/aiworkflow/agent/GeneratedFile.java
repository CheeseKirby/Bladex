package org.springblade.aiworkflow.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springblade.aiworkflow.enums.TaskType;

/**
 * 生成的文件
 *
 * @author AI Developer
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedFile {

    /** 文件类型 */
    private TaskType type;

    /** 目标文件路径（相对于target-project-root） */
    private String filePath;

    /** 生成的文件内容 */
    private String content;

    /** 操作: CREATE/MODIFY/SKIP */
    private String action;

    public static GeneratedFile create(TaskType type, String filePath, String content) {
        return new GeneratedFile(type, filePath, content, "CREATE");
    }

    public static GeneratedFile modify(TaskType type, String filePath, String content) {
        return new GeneratedFile(type, filePath, content, "MODIFY");
    }
}
