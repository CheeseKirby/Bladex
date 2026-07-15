package org.springblade.aiworkflow.agent;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven compile verifier.
 *
 * <p>Process output is consumed on a separate daemon thread. The caller waits for the process
 * with a real timeout before waiting for stdout EOF, preventing a hung Maven process from
 * blocking the workflow forever.
 */
@Slf4j
public class BuildVerifier {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Pattern SAFE_MODULE = Pattern.compile("[A-Za-z0-9_./-]+");
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "\\[ERROR\\]\\s+([A-Za-z]:[^:]+|/[^:]+):\\[(\\d+)[^]]*]\\s+error:\\s+(.+)");

    private final String targetProjectRoot;
    private final Duration timeout;

    public BuildVerifier(String targetProjectRoot) {
        this(targetProjectRoot, DEFAULT_TIMEOUT);
    }

    BuildVerifier(String targetProjectRoot, Duration timeout) {
        this.targetProjectRoot = targetProjectRoot;
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? DEFAULT_TIMEOUT : timeout;
    }

    public BuildResult verify(List<String> affectedModules) {
        if (affectedModules == null || affectedModules.isEmpty()) {
            return BuildResult.success();
        }
        if (affectedModules.stream().anyMatch(module -> module == null || !SAFE_MODULE.matcher(module).matches())) {
            return BuildResult.failure(List.of(new BuildResult.BuildError(
                    null, null, "Unsafe Maven module path: " + affectedModules)));
        }

        String modules = String.join(",", affectedModules);
        List<String> command = buildMavenCommand(modules);
        log.info("Running compile verification: cwd={}, command={}", targetProjectRoot, command);
        ProcessResult result = executeCommand(command, new File(targetProjectRoot));

        if (result.exitCode() == 0) {
            log.info("Compile verification passed");
            return BuildResult.success();
        }

        List<BuildResult.BuildError> errors = parseBuildErrors(result.output());
        if (errors.isEmpty()) {
            errors.add(new BuildResult.BuildError(null, null, summarizeFailure(result.output())));
        }
        log.warn("Compile verification failed with {} parsed error(s)", errors.size());
        return BuildResult.failure(errors);
    }

    private List<String> buildMavenCommand(String modules) {
        if (isWindows()) {
            // Maven is normally mvn.cmd on Windows. Module values are whitelist-validated above.
            return List.of("cmd.exe", "/d", "/s", "/c", "mvn", "compile", "-pl", modules,
                    "-am", "-DskipTests", "-q");
        }
        return List.of("mvn", "compile", "-pl", modules, "-am", "-DskipTests", "-q");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    ProcessResult executeCommand(List<String> command, File workingDir) {
        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "build-verifier-output");
            thread.setDaemon(true);
            return thread;
        });
        Process process = null;
        Future<String> outputFuture = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDir);
            builder.redirectErrorStream(true);

            process = builder.start();
            Process startedProcess = process;
            outputFuture = outputReaderExecutor.submit(() -> readOutput(startedProcess));

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyProcessTree(process);
                String output = collectOutput(outputFuture, OUTPUT_DRAIN_TIMEOUT);
                return new ProcessResult(-1,
                        "Build timed out after " + timeout.toMillis() + " ms\n" + output);
            }

            String output = collectOutput(outputFuture, OUTPUT_DRAIN_TIMEOUT);
            return new ProcessResult(process.exitValue(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) destroyProcessTree(process);
            return new ProcessResult(-1, "Build command interrupted");
        } catch (Exception e) {
            if (process != null && process.isAlive()) destroyProcessTree(process);
            log.error("Compile command failed", e);
            return new ProcessResult(-1, "Compile command failed: " + e.getMessage());
        } finally {
            if (outputFuture != null && !outputFuture.isDone()) outputFuture.cancel(true);
            outputReaderExecutor.shutdownNow();
        }
    }

    private String readOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }

    private String collectOutput(Future<String> outputFuture, Duration wait)
            throws InterruptedException, ExecutionException {
        try {
            return outputFuture.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            return "(process output stream did not close in time)";
        }
    }

    private void destroyProcessTree(Process process) {
        if (isWindows()) {
            // ProcessHandle.descendants() may block during Windows process teardown.
            Process taskkill = null;
            try {
                taskkill = new ProcessBuilder("taskkill", "/PID", String.valueOf(process.pid()), "/T", "/F")
                        .redirectErrorStream(true)
                        .start();
                if (!taskkill.waitFor(2, TimeUnit.SECONDS)) taskkill.destroyForcibly();
            } catch (Exception e) {
                log.debug("taskkill failed for pid={}: {}", process.pid(), e.getMessage());
            }
        } else {
            try {
                process.descendants().forEach(handle -> {
                    try {
                        handle.destroyForcibly();
                    } catch (Exception ignored) {
                        // Best effort.
                    }
                });
            } catch (Exception e) {
                log.debug("Process-tree discovery failed for pid={}: {}", process.pid(), e.getMessage());
            }
        }
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<BuildResult.BuildError> parseBuildErrors(String buildOutput) {
        List<BuildResult.BuildError> errors = new ArrayList<>();
        Matcher matcher = ERROR_PATTERN.matcher(buildOutput == null ? "" : buildOutput);
        while (matcher.find()) {
            errors.add(new BuildResult.BuildError(
                    matcher.group(1), Integer.parseInt(matcher.group(2)), matcher.group(3)));
        }
        return errors;
    }

    private String summarizeFailure(String output) {
        if (output == null || output.isBlank()) return "Maven compile failed without parseable output";
        String compact = output.strip();
        return compact.length() <= 2_000 ? compact : compact.substring(0, 2_000) + "...";
    }

    static record ProcessResult(int exitCode, String output) {
    }
}
