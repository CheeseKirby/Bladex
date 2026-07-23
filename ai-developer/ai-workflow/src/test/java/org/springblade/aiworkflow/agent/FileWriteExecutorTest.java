package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileWriteExecutor 路径遍历防护测试 - L3: 安全命脉需回归测试。
 *
 * <p>覆盖: 绝对路径拒绝、.. 遍历拒绝、白名单外扩展名拒绝、正常写入成功。
 * 防止后续重构(如改 root 解析逻辑)无声破坏防护。
 */
class FileWriteExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRejectParentTraversal() {
        FileWriteExecutor exec = new FileWriteExecutor(tempDir.toString());
        FileWriteTask task = new FileWriteTask("../evil.java", "content", "CREATE");
        WriteResult r = exec.write(List.of(task));
        assertFalse(r.isSuccess(), ".. 遍历应被拒绝");
        assertNotNull(r.getErrorMessage());
        assertTrue(r.getErrorMessage().contains("路径遍历"), "错误信息应提及路径遍历: " + r.getErrorMessage());
    }

    @Test
    void shouldRejectDisallowedExtension() {
        FileWriteExecutor exec = new FileWriteExecutor(tempDir.toString());
        FileWriteTask task = new FileWriteTask("evil.exe", "content", "CREATE");
        WriteResult r = exec.write(List.of(task));
        assertFalse(r.isSuccess(), "白名单外扩展名应被拒绝");
        assertTrue(r.getErrorMessage().contains("扩展名"), "错误信息应提及扩展名: " + r.getErrorMessage());
    }

    @Test
    void shouldRejectAbsolutePath() {
        FileWriteExecutor exec = new FileWriteExecutor(tempDir.toString());
        // 跨平台绝对路径: Windows C:\..., Unix /...
        String abs = System.getProperty("os.name").toLowerCase().contains("win")
                ? "C:\\Windows\\evil.java" : "/etc/evil.java";
        FileWriteTask task = new FileWriteTask(abs, "content", "CREATE");
        WriteResult r = exec.write(List.of(task));
        assertFalse(r.isSuccess(), "绝对路径应被拒绝");
        assertTrue(r.getErrorMessage().contains("绝对路径"), "错误信息应提及绝对路径: " + r.getErrorMessage());
    }

    @Test
    void shouldWriteNormalFile() throws Exception {
        FileWriteExecutor exec = new FileWriteExecutor(tempDir.toString());
        FileWriteTask task = new FileWriteTask("Test.java", "content", "CREATE");
        WriteResult r = exec.write(List.of(task));
        assertTrue(r.isSuccess(), "正常相对路径 java 文件应写入成功: " + r.getErrorMessage());
        Path written = tempDir.resolve("Test.java");
        assertTrue(Files.exists(written), "文件应已写入");
        assertEquals("content", Files.readString(written));
    }
    @Test
    void preparedWriteRollsBackEveryPromotedFileAfterFinalGateFailure() throws Exception {
        FileWriteExecutor executor = new FileWriteExecutor(tempDir.toString());
        Path existing = tempDir.resolve("Existing.java");
        Files.writeString(existing, "old");

        FileWriteExecutor.PreparedWrite prepared = executor.prepareTransactionalWrite(List.of(
                new FileWriteTask("Existing.java", "new", "MODIFY"),
                new FileWriteTask("Created.java", "created", "CREATE")
        ), tempDir.toString());

        assertTrue(prepared.apply().isSuccess());
        assertEquals("new", Files.readString(existing));
        assertTrue(Files.exists(tempDir.resolve("Created.java")));

        WriteResult rollback = prepared.rollback();
        assertTrue(rollback.isSuccess(), rollback.getErrorMessage());
        assertEquals("old", Files.readString(existing));
        assertFalse(Files.exists(tempDir.resolve("Created.java")));
    }

    @Test
    void committedPreparedWriteCannotBeRolledBack() throws Exception {
        FileWriteExecutor executor = new FileWriteExecutor(tempDir.toString());
        FileWriteExecutor.PreparedWrite prepared = executor.prepareTransactionalWrite(
                List.of(new FileWriteTask("Committed.java", "content", "CREATE")), tempDir.toString());

        assertTrue(prepared.apply().isSuccess());
        prepared.commit();

        assertFalse(prepared.rollback().isSuccess());
        assertEquals("content", Files.readString(tempDir.resolve("Committed.java")));
    }

}
