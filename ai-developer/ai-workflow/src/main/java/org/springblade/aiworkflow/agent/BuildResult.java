package org.springblade.aiworkflow.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 编译验证结果
 *
 * @author AI Developer
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BuildResult {

    /** 是否编译通过 */
    private boolean passes;

    /** 编译错误列表 */
    private List<BuildError> errors = new ArrayList<>();

    public static BuildResult success() {
        return new BuildResult(true, new ArrayList<>());
    }

    public static BuildResult failure(List<BuildError> errors) {
        return new BuildResult(false, errors);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BuildError {

        /** 文件路径 */
        private String file;

        /** 行号（null表示无法确定行号） */
        private Integer line;

        /** 错误消息 */
        private String message;
    }
}
