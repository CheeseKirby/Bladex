package org.springblade.aiworkflow.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码生成结果
 *
 * @author AI Developer
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerationResult {

    /** 生成策略: TEMPLATE / LLM */
    private String strategy;

    /** 是否成功 */
    private boolean success;

    /** 错误消息 */
    private String errorMessage;

    /** 生成的文件列表 */
    private List<GeneratedFile> generatedFiles = new ArrayList<>();

    public static GenerationResult templateSuccess(List<GeneratedFile> files) {
        return new GenerationResult("TEMPLATE", true, null, files);
    }

    public static GenerationResult llmSuccess(List<GeneratedFile> files) {
        return new GenerationResult("LLM", true, null, files);
    }

    public static GenerationResult failure(String strategy, String errorMessage) {
        return new GenerationResult(strategy, false, errorMessage, new ArrayList<>());
    }
}
