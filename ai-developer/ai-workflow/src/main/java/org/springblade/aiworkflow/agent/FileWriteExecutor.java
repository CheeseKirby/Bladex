package org.springblade.aiworkflow.agent;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Safe file writer with path confinement and reversible plan-wide writes. */
@Slf4j
public class FileWriteExecutor {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "java", "sql", "xml", "yml", "yaml", "properties"
    );

    private final String targetProjectRoot;

    public FileWriteExecutor(String targetProjectRoot) {
        this.targetProjectRoot = targetProjectRoot;
    }

    public WriteResult write(List<FileWriteTask> tasks) {
        return write(tasks, this.targetProjectRoot);
    }

    /** Writes one batch and rolls the complete batch back when any write fails. */
    public WriteResult write(List<FileWriteTask> tasks, String rootOverride) {
        try {
            PreparedWrite prepared = prepareTransactionalWrite(tasks, rootOverride, true);
            WriteResult result = prepared.apply();
            if (result.isSuccess()) prepared.commit();
            return result;
        } catch (IllegalArgumentException | IOException error) {
            return WriteResult.failure(error.getMessage());
        }
    }

    /**
     * Prepares a reversible write against an existing REAL project root.
     * The caller must invoke exactly one of {@link PreparedWrite#commit()} or {@link PreparedWrite#rollback()}
     * after a successful {@link PreparedWrite#apply()}.
     */
    public PreparedWrite prepareTransactionalWrite(List<FileWriteTask> tasks, String rootOverride) {
        try {
            return prepareTransactionalWrite(tasks, rootOverride, false);
        } catch (IOException error) {
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }

    private PreparedWrite prepareTransactionalWrite(List<FileWriteTask> tasks, String rootOverride,
                                                     boolean createRoot) throws IOException {
        String rootString = rootOverride != null && !rootOverride.isBlank()
                ? rootOverride : targetProjectRoot;
        if (rootString == null || rootString.isBlank()) {
            throw new IllegalArgumentException("Output root is blank");
        }
        Path configuredRoot = Paths.get(rootString);
        if (!Files.exists(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
            if (!createRoot) {
                throw new IllegalArgumentException("Target project root does not exist: " + rootString);
            }
            Files.createDirectories(configuredRoot);
            log.info("Created output root: {}", configuredRoot);
        }
        Path rootPath = configuredRoot.toRealPath();
        if (!Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Output root is not a directory: " + rootString);
        }

        Map<FileWriteTask, Path> safePaths = new LinkedHashMap<>();
        Set<Path> uniqueTargets = new LinkedHashSet<>();
        List<FileWriteTask> safeTasks = tasks == null ? List.of() : tasks;
        for (FileWriteTask task : safeTasks) {
            if (task == null) throw new IllegalArgumentException("File write task is null");
            String target = task.getTargetPath();
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("File path is blank");
            }
            if (task.getContent() == null) {
                throw new IllegalArgumentException("File content is null: " + target);
            }
            Path candidate;
            try {
                candidate = Paths.get(target);
            } catch (Exception error) {
                throw new IllegalArgumentException("Invalid file path: " + target, error);
            }
            if (candidate.isAbsolute()) {
                throw new IllegalArgumentException("Absolute file paths are forbidden (\u7edd\u5bf9\u8def\u5f84): " + target);
            }
            for (Path segment : candidate) {
                if ("..".equals(segment.toString())) {
                    throw new IllegalArgumentException("Path traversal detected (\u8def\u5f84\u904d\u5386): " + target);
                }
            }
            String extension = extractExtension(target);
            if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Disallowed file extension (\u6269\u5c55\u540d): " + target);
            }
            Path resolved = rootPath.resolve(candidate).normalize();
            if (!resolved.startsWith(rootPath)) {
                throw new IllegalArgumentException("Path traversal detected (\u8def\u5f84\u904d\u5386): " + target);
            }
            if (!uniqueTargets.add(resolved)) {
                throw new IllegalArgumentException("Duplicate file target in one write batch: " + target);
            }
            if (Files.isSymbolicLink(resolved)) {
                throw new IllegalArgumentException("Symbolic-link file targets are forbidden: " + target);
            }
            Path parent = resolved.getParent();
            Path existingAncestor = parent;
            while (existingAncestor != null && !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
                existingAncestor = existingAncestor.getParent();
            }
            if (existingAncestor != null && !existingAncestor.toRealPath().startsWith(rootPath)) {
                throw new IllegalArgumentException("Symbolic-link path escape detected: " + target);
            }
            safePaths.put(task, resolved);
        }

        Map<FileWriteTask, byte[]> snapshots = new LinkedHashMap<>();
        for (Map.Entry<FileWriteTask, Path> entry : safePaths.entrySet()) {
            Path path = entry.getValue();
            snapshots.put(entry.getKey(), Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    ? Files.readAllBytes(path) : null);
        }
        return new PreparedWrite(rootPath, safePaths, snapshots);
    }

    public String getTargetProjectRoot() {
        return targetProjectRoot;
    }

    /** Default isolated output root is created when absent. */
    public boolean isTargetRootAvailable() {
        if (targetProjectRoot == null || targetProjectRoot.isBlank()) return false;
        try {
            Path path = Paths.get(targetProjectRoot);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(path);
            return Files.isDirectory(path.toRealPath());
        } catch (IOException error) {
            return false;
        }
    }

    /** REAL target roots must already exist and are never created implicitly. */
    public boolean isRootAvailable(String root) {
        if (root == null || root.isBlank()) return false;
        try {
            Path path = Paths.get(root);
            return Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(path.toRealPath());
        } catch (IOException error) {
            return false;
        }
    }

    private static String extractExtension(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : null;
    }

    /** In-memory plan-wide snapshot retained until final validation and compile verification complete. */
    public static final class PreparedWrite {
        private final Path rootPath;
        private final Map<FileWriteTask, Path> safePaths;
        private final Map<FileWriteTask, byte[]> snapshots;
        private boolean applied;
        private boolean closed;

        private PreparedWrite(Path rootPath, Map<FileWriteTask, Path> safePaths,
                              Map<FileWriteTask, byte[]> snapshots) {
            this.rootPath = rootPath;
            this.safePaths = safePaths;
            this.snapshots = snapshots;
        }

        public synchronized WriteResult apply() {
            if (closed) return WriteResult.failure("Prepared write is already closed");
            if (applied) return WriteResult.failure("Prepared write was already applied");
            List<String> written = new ArrayList<>();
            try {
                for (Map.Entry<FileWriteTask, Path> entry : safePaths.entrySet()) {
                    Path parent = entry.getValue().getParent();
                    if (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(parent);
                    }
                    if (parent != null && !parent.toRealPath().startsWith(rootPath)) {
                        throw new IOException("Symbolic-link path escape detected during write: "
                                + entry.getKey().getTargetPath());
                    }
                    if (Files.isSymbolicLink(entry.getValue())) {
                        throw new IOException("Symbolic-link target appeared during write: "
                                + entry.getKey().getTargetPath());
                    }
                    Files.write(entry.getValue(), entry.getKey().getContent().getBytes(StandardCharsets.UTF_8));
                    written.add(entry.getKey().getTargetPath());
                }
                applied = true;
                return WriteResult.success(written);
            } catch (IOException error) {
                WriteResult rollback = rollbackInternal();
                closed = true;
                String suffix = rollback.isSuccess() ? "" : "; rollback error: " + rollback.getErrorMessage();
                return WriteResult.failure("Write failed and was rolled back: " + error.getMessage() + suffix);
            }
        }

        public synchronized WriteResult rollback() {
            if (closed) return WriteResult.failure("Prepared write is already closed");
            WriteResult result = rollbackInternal();
            closed = true;
            return result;
        }

        private WriteResult rollbackInternal() {
            List<String> restored = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (Map.Entry<FileWriteTask, byte[]> entry : snapshots.entrySet()) {
                Path path = safePaths.get(entry.getKey());
                try {
                    byte[] previous = entry.getValue();
                    if (previous == null) {
                        Files.deleteIfExists(path);
                    } else {
                        Path parent = path.getParent();
                        if (parent != null) Files.createDirectories(parent);
                        Files.write(path, previous);
                    }
                    restored.add(entry.getKey().getTargetPath());
                } catch (IOException error) {
                    errors.add(entry.getKey().getTargetPath() + ": " + error.getMessage());
                }
            }
            return errors.isEmpty() ? WriteResult.success(restored)
                    : WriteResult.failure(String.join(" | ", errors));
        }

        public synchronized void commit() {
            if (closed) return;
            closed = true;
            snapshots.clear();
        }
    }
}
