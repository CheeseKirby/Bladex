package org.springblade.aiworkflow.agent;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编译验证器 — Layer 3审查机制
 *
 * <p>在目标项目中执行Maven编译验证，解析编译错误。
 * 支持Unix和Windows路径格式。
 *
 * @author AI Developer
 */
@Slf4j
public class BuildVerifier {

    private static final int TIMEOUT_SECONDS = 120;

    // 匹配 [ERROR] /path/to/File.java:[line] error: message (Unix)
    // 匹配 [ERROR] C:\path\to\File.java:[line] error: message (Windows)
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "\\[ERROR\\]\\s+([A-Za-z]:[^:]+|/[^:]+):\\[(\\d+)[^]]*\\]\\s+error:\\s+(.+)");

    private final String targetProjectRoot;

    public BuildVerifier(String targetProjectRoot) {
        this.targetProjectRoot = targetProjectRoot;
    }

    /**
     * 在目标项目中执行Maven编译验证
     *
     * @param affectedModules 受影响的模块列表
     * @return 编译结果
     */
    public BuildResult verify(List<String> affectedModules) {
        if (affectedModules == null || affectedModules.isEmpty()) {
            return BuildResult.success();
        }

        String modules = String.join(",", affectedModules);
        String command = String.format("mvn compile -pl %s -am -DskipTests -q", modules);

        log.info("执行编译验证: cd {} && {}", targetProjectRoot, command);
        ProcessResult result = executeCommand(command, new File(targetProjectRoot));

        if (result.exitCode == 0) {
            log.info("编译验证通过");
            return BuildResult.success();
        }

        List<BuildResult.BuildError> errors = parseBuildErrors(result.output);
        log.warn("编译验证失败，发现 {} 个错误", errors.size());
        for (BuildResult.BuildError error : errors) {
            log.warn("  {}:{} - {}", error.getFile(), error.getLine(), error.getMessage());
        }

        return BuildResult.failure(errors);
    }

    /**
     * 执行命令行命令
     */
    private ProcessResult executeCommand(String command, File workingDir) {
        try {
            ProcessBuilder builder = new ProcessBuilder();
            // 根据操作系统选择shell
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                builder.command("cmd", "/c", command);
            } else {
                builder.command("sh", "-c", command);
            }
            builder.directory(workingDir);
            builder.redirectErrorStream(true);

            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(-1, "编译超时（超过" + TIMEOUT_SECONDS + "秒）");
            }

            return new ProcessResult(process.exitValue(), output.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("编译命令被中断", e);
            return new ProcessResult(-1, "编译命令被中断");
        } catch (Exception e) {
            log.error("执行编译命令异常", e);
            return new ProcessResult(-1, "执行编译命令异常: " + e.getMessage());
        }
    }

    /**
     * 解析编译错误输出
     */
    private List<BuildResult.BuildError> parseBuildErrors(String buildOutput) {
        List<BuildResult.BuildError> errors = new ArrayList<>();
        Matcher matcher = ERROR_PATTERN.matcher(buildOutput);
        while (matcher.find()) {
            errors.add(new BuildResult.BuildError(
                    matcher.group(1),
                    Integer.parseInt(matcher.group(2)),
                    matcher.group(3)
            ));
        }
        return errors;
    }

    /**
     * 进程执行结果
     */
    private record ProcessResult(int exitCode, String output) {
    }
}
