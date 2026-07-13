package org.springblade.aiworkflow.agent;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 安全文件写入执行器
 *
 * <p>三阶段写入:快照 → 写入 → 回滚(失败时)。所有文件路径相对于 target-project-root(默认隔离区 ai-generated-modules)。
 *
 * <p>路径安全策略(防止路径遍历 / SSRF-on-disk):
 * <ul>
 *   <li>拒绝绝对路径(Windows / Unix 任意一种);</li>
 *   <li>拒绝路径片段为 {@code ..};</li>
 *   <li>{@code resolve + normalize} 后 startsWith(rootPath) 必须为真;</li>
 *   <li>父目录如果已经存在,会取其 realPath 再次校验 startsWith,从而捕获符号链接逃逸;</li>
 *   <li>白名单扩展名: java/sql/xml/yml/yaml/properties/md/json/sh/bat 之外的文件一律拒绝。</li>
 * </ul>
 *
 * <p>另外:第 0 步把每个 task 解析得到的安全 path 缓存到 Map,后续 snapshot / write / rollback 阶段
 * 全部从 Map 取,杜绝多次 resolve 不一致带来的 TOCTOU 漏洞。
 *
 * @author AI Developer
 */
@Slf4j
public class FileWriteExecutor {

    /** 允许写入的文件后缀(白名单)- L7: 收窄到 BladeX 后端生成所需, 避免非后端文件(html/css/vue/ts 等)落盘。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "java", "sql", "xml", "yml", "yaml", "properties"
    );

    private final String targetProjectRoot;

    public FileWriteExecutor(String targetProjectRoot) {
        this.targetProjectRoot = targetProjectRoot;
    }

    /**
     * 安全写入文件(默认根),支持全部回滚。委托 {@link #write(List, String)}。
     * 所有路径必须位于 target-project-root 之内,防止路径遍历攻击。
     */
    public WriteResult write(List<FileWriteTask> tasks) {
        return write(tasks, this.targetProjectRoot);
    }

    /**
     * 安全写入文件(指定根),支持全部回滚。阶段2 支持 per-request 切换写盘目标
     * (ISOLATED→outputRoot / REAL→targetProjectRoot),root 作为参数传入,不改单例字段,
     * 多 plan 并发写不同 root 互不干扰(线程安全)。
     *
     * <p>所有路径必须位于 rootOverride 之内,防止路径遍历攻击。rootOverride 为空则回退默认根。
     */
    public WriteResult write(List<FileWriteTask> tasks, String rootOverride) {
        String rootStr = (rootOverride != null && !rootOverride.isBlank()) ? rootOverride : this.targetProjectRoot;
        // 0. 验证 rootPath 本身可访问。独立输出目录可能尚未存在，先自动创建根目录再取 realPath。
        //    路径遍历 / 符号链接逃逸的防护不变（resolve+normalize+startsWith 在下面 per-task 校验）。
        Path rootPath;
        try {
            Path root = Paths.get(rootStr);
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(root);
                log.info("已创建输出根目录: {}", root);
            }
            rootPath = root.toRealPath();
        } catch (IOException e) {
            return WriteResult.failure("输出根目录无法创建或访问: " + rootStr + " - " + e.getMessage());
        }

        // 一次性把每个 task 的 "安全绝对路径" 解析出来并缓存,后续阶段全部复用,
        // 避免重新 resolve 时因 normalize 顺序/符号链接出现解析不一致。
        Map<FileWriteTask, Path> safePathByTask = new LinkedHashMap<>();
        for (FileWriteTask task : tasks) {
            String target = task.getTargetPath();
            if (target == null || target.isBlank()) {
                return WriteResult.failure("文件路径为空");
            }
            if (task.getContent() == null) {
                return WriteResult.failure("文件内容为空: " + target);
            }
            // 拒绝绝对路径
            Path candidate;
            try {
                candidate = Paths.get(target);
            } catch (Exception e) {
                return WriteResult.failure("路径非法: " + target);
            }
            if (candidate.isAbsolute()) {
                return WriteResult.failure("禁止使用绝对路径: " + target);
            }
            // 拒绝 .. 片段
            for (Path seg : candidate) {
                if ("..".equals(seg.toString())) {
                    return WriteResult.failure("路径遍历检测: " + target);
                }
            }
            // 校验后缀白名单
            String ext = extractExtension(target);
            if (ext == null || !ALLOWED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
                return WriteResult.failure("不允许的文件扩展名: " + target);
            }
            // resolve + normalize 再次校验
            Path resolved = rootPath.resolve(candidate).normalize();
            if (!resolved.startsWith(rootPath)) {
                return WriteResult.failure("路径遍历检测: " + target);
            }
            // 父目录若已存在,取 realPath 再校验一次,捕获符号链接逃逸
            Path parent = resolved.getParent();
            if (parent != null && Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Path realParent = parent.toRealPath();
                    if (!realParent.startsWith(rootPath)) {
                        return WriteResult.failure("路径遍历检测(符号链接): " + target);
                    }
                } catch (IOException e) {
                    return WriteResult.failure("父目录无法访问: " + target);
                }
            }
            safePathByTask.put(task, resolved);
        }

        // 第一阶段:快照所有目标文件
        Map<FileWriteTask, byte[]> snapshots = new LinkedHashMap<>();
        for (Map.Entry<FileWriteTask, Path> entry : safePathByTask.entrySet()) {
            FileWriteTask task = entry.getKey();
            Path safe = entry.getValue();
            try {
                if (Files.exists(safe, LinkOption.NOFOLLOW_LINKS)) {
                    snapshots.put(task, Files.readAllBytes(safe));
                } else {
                    snapshots.put(task, null);
                }
            } catch (IOException e) {
                return WriteResult.failure("快照阶段失败: " + task.getTargetPath() + " - " + e.getMessage());
            }
        }

        // 第二阶段:逐个写入
        List<String> writtenFiles = new ArrayList<>();
        try {
            for (Map.Entry<FileWriteTask, Path> entry : safePathByTask.entrySet()) {
                FileWriteTask task = entry.getKey();
                Path safe = entry.getValue();
                Path parentDir = safe.getParent();
                if (parentDir != null && !Files.exists(parentDir, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(parentDir);
                    log.info("已创建目录: {}", parentDir);
                }
                Files.write(safe, task.getContent().getBytes(StandardCharsets.UTF_8));
                writtenFiles.add(task.getTargetPath());
                log.info("已写入: {}", task.getTargetPath());
            }
            return WriteResult.success(writtenFiles);

        } catch (IOException e) {
            log.error("写入失败,开始回滚: {}", e.getMessage());

            // 第三阶段:回滚所有已修改的文件 — 使用缓存的安全路径
            for (Map.Entry<FileWriteTask, byte[]> entry : snapshots.entrySet()) {
                FileWriteTask task = entry.getKey();
                Path safe = safePathByTask.get(task);
                byte[] previous = entry.getValue();
                try {
                    if (previous == null) {
                        if (Files.exists(safe, LinkOption.NOFOLLOW_LINKS)) {
                            Files.delete(safe);
                            log.info("已回滚删除: {}", task.getTargetPath());
                        }
                    } else {
                        Files.write(safe, previous);
                        log.info("已回滚恢复: {}", task.getTargetPath());
                    }
                } catch (IOException rollbackError) {
                    log.error("回滚失败,文件可能处于不一致状态: {}", task.getTargetPath(), rollbackError);
                }
            }
            return WriteResult.failure("写入失败已回滚: " + e.getMessage());
        }
    }

    public String getTargetProjectRoot() {
        return targetProjectRoot;
    }

    /**
     * 默认根(隔离区 outputRoot)是否可访问。不存在时自动创建,避免独立目录首次使用即被判定不可用。
     * 保持原有 ISOLATED 模式行为(向后兼容)。
     */
    public boolean isTargetRootAvailable() {
        if (targetProjectRoot == null || targetProjectRoot.isBlank()) return false;
        try {
            Path p = Paths.get(targetProjectRoot);
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(p);
            }
            return Files.isDirectory(p.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 指定根是否可访问。阶段2 用于判断 REAL 模式的目标项目根是否可写。
     * 与默认根不同:真实项目根不存在时<strong>不自动创建</strong>(避免误造目标项目目录),
     * 仅在已存在且是目录时返回 true。
     */
    public boolean isRootAvailable(String root) {
        if (root == null || root.isBlank()) return false;
        try {
            Path p = Paths.get(root);
            // 真实项目根不自动创建 — 必须已存在(避免误造)
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return false;
            return Files.isDirectory(p.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static String extractExtension(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : null;
    }
}
