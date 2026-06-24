package org.springblade.aiworkflow.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件写入结果
 *
 * @author AI Developer
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WriteResult {

    /** 是否成功 */
    private boolean success;

    /** 错误消息 */
    private String errorMessage;

    /** 已写入的文件路径列表 */
    private List<String> writtenFiles = new ArrayList<>();

    public static WriteResult success(List<String> writtenFiles) {
        return new WriteResult(true, null, writtenFiles);
    }

    public static WriteResult failure(String errorMessage) {
        return new WriteResult(false, errorMessage, new ArrayList<>());
    }
}
