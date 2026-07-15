package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildVerifierTest {

    @TempDir
    Path tempDir;

    @Test
    void commandTimeoutMustNotWaitForStdoutEof() {
        BuildVerifier verifier = new BuildVerifier(tempDir.toString(), Duration.ofMillis(300));
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        String classpath = Path.of("target", "test-classes").toAbsolutePath()
                + File.pathSeparator + Path.of("target", "classes").toAbsolutePath();
        List<String> command = List.of(javaExecutable, "-cp", classpath, SlowProcess.class.getName());

        BuildVerifier.ProcessResult result = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> verifier.executeCommand(command, tempDir.toFile()));

        assertEquals(-1, result.exitCode());
        assertTrue(result.output().contains("Build timed out"), result.output());
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    public static class SlowProcess {
        public static void main(String[] args) throws Exception {
            System.out.println("started");
            System.out.flush();
            Thread.sleep(30_000);
        }
    }
}
