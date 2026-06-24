package org.springblade.aiworkflow.agent;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 改动评估器 — 评估代码改动是否正确且必要
 *
 * <p>满足需求B4：改动时仔细评估正确性与必要性。
 *
 * @author AI Developer
 */
@Slf4j
public class ChangeEvaluator {

    private static final java.util.regex.Pattern CLASS_NAME_PATTERN =
            java.util.regex.Pattern.compile("class\\s+(\\w+)");

    /** 产物输出根目录（独立 ai-generated-modules），用于判断目标文件是否已存在 */
    private final String outputRoot;

    public ChangeEvaluator(String outputRoot) {
        this.outputRoot = outputRoot;
    }

    /**
     * 评估原子任务 — 在 LLM 生成之前调用,所以此时 task.expectedContent 通常为 null。
     *
     * <p>策略:
     * <ul>
     *   <li>目标文件不存在 → CREATE</li>
     *   <li>目标文件存在但 expectedContent 已有内容,且与已有功能相似 → SKIP</li>
     *   <li>目标文件存在但内容会被改写 → MODIFY (带 warning)</li>
     * </ul>
     */
    public ChangeEvaluation evaluate(AtomicTask task) {
        File targetFile = new File(outputRoot, task.getTargetPath());

        // 1. 文件不存在 → 创建(这是生成新代码的正常路径,不应被跳过)
        if (!targetFile.exists()) {
            ChangeEvaluation eval = ChangeEvaluation.create("创建新文件: " + task.getTargetPath());
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                eval.addWarning("父目录不存在,将在写入时自动创建: " + parentDir.getPath());
            }
            return eval;
        }

        // 2. 文件已存在 — 只有当 expectedContent 已有内容且与现有文件功能相似时才跳过
        try {
            String existingContent = Files.readString(targetFile.toPath(), StandardCharsets.UTF_8);

            if (task.getExpectedContent() != null && !task.getExpectedContent().isBlank()
                    && isFunctionallySimilar(existingContent, task.getExpectedContent())) {
                return ChangeEvaluation.skip("文件已存在且功能相同,无需修改: " + task.getTargetPath());
            }

            ChangeEvaluation eval = ChangeEvaluation.modify("文件已存在但需要修改: " + task.getTargetPath());
            eval.addWarning("修改已有文件,请检查是否与现有业务逻辑冲突");
            return eval;

        } catch (IOException e) {
            log.warn("读取已存在文件失败: {}", targetFile.getAbsolutePath(), e);
            return ChangeEvaluation.create("读取文件失败,将创建新文件: " + task.getTargetPath());
        }
    }

    /**
     * 简单判断两个内容是否功能相似（类名和方法签名相同）
     */
    private boolean isFunctionallySimilar(String existing, String generated) {
        if (existing == null || generated == null) return false;

        // 提取类名
        String existingClassName = extractClassName(existing);
        String generatedClassName = extractClassName(generated);

        if (existingClassName == null || !existingClassName.equals(generatedClassName)) {
            return false;
        }

        // 简单对比：如果内容长度差异小于5%，认为相似
        int lengthDiff = Math.abs(existing.length() - generated.length());
        int maxLen = Math.max(existing.length(), generated.length());
        return (double) lengthDiff / maxLen < 0.05;
    }

    private String extractClassName(String code) {
        java.util.regex.Matcher m = CLASS_NAME_PATTERN.matcher(code);
        return m.find() ? m.group(1) : null;
    }
}
