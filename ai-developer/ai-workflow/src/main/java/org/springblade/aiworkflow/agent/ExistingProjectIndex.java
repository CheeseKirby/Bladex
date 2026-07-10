package org.springblade.aiworkflow.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.config.AiWorkflowProperties;
import org.springblade.aiworkflow.enums.ClassType;
import org.springblade.aiworkflow.vo.ProjectScanVO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 已有 BladeX 项目结构索引器 — 阶段1 核心。
 *
 * <p>给定项目根路径(复用 {@code ai-workflow.target-project-root}),递归扫所有 .java,
 * 用 JavaParser 解析(无 symbol solver,与 {@link CrossFileValidator} 一致),为每个顶层类型
 * 生成 {@link IndexedClassInfo},按逻辑模块分组缓存,供后续阶段(增/改/删)查询。
 *
 * <p>设计:
 * <ul>
 *   <li>懒加载:构造时不扫,首次调 {@link #scan} 才扫,保持启动快;</li>
 *   <li>内存缓存:volatile,force=false 命中缓存直接返回;</li>
 *   <li>安全:Files.walk 不跟随符号链接 + isWithinRoot 防路径遍历 + 排除 target/.git 等;</li>
 *   <li>容错:单文件解析失败跳过,不中断整体扫描。</li>
 * </ul>
 *
 * <p>只读:不修改扫描目标的任何文件。
 */
@Slf4j
public class ExistingProjectIndex {

    /** 扫描时跳过的目录(target/.git 等是构建/版本产物,非"已有 BladeX 代码") */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "target", ".git", ".idea", "node_modules", "ai-generated", "ai-generated-modules");
    /** 单文件大小上限:超过 1MB 跳过(防止异常大文件拖垮扫描) */
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    /** 文件总数上限:超过 2 万直接 400(防止误扫超大仓库) */
    private static final int MAX_FILES_GUARD = 20_000;

    private final Path projectRoot;
    private final String projectRootStr;

    /** 缓存:扫描结果 VO + 扁平列表 + 时间戳。volatile 保证多线程可见性 */
    private volatile ProjectScanVO cachedVo;
    private volatile List<IndexedClassInfo> cachedFlat;
    private volatile Instant cachedAt;
    /** 并发守卫:扫描进行中标志(简单防重入,不阻塞读缓存) */
    private volatile boolean scanning;

    public ExistingProjectIndex(AiWorkflowProperties properties) {
        String root = properties.getTargetProjectRoot();
        this.projectRoot = Paths.get(root).toAbsolutePath().normalize();
        this.projectRootStr = projectRoot.toString();
        if (!Files.isDirectory(projectRoot)) {
            // 构造时不抛(保持启动成功),scan 时再校验返回 400
            log.warn("ExistingProjectIndex: 目标项目根不存在或不是目录: {} (扫描时将返回 400)", projectRootStr);
        }
    }

    /**
     * 触发扫描。
     *
     * @param force true 强制重扫;false 有缓存则返回缓存
     * @return 扫描结果 VO
     * @throws IllegalArgumentException 根不存在/非目录/文件数超限
     */
    public synchronized ProjectScanVO scan(boolean force) {
        if (!force && cachedVo != null) {
            ProjectScanVO cached = cachedVo;
            cached.getMeta().setFromCache(true);
            return cached;
        }
        if (scanning) {
            // 并发重入:返回已有缓存(可能为 null,但 synchronized 已串行化,实际极少到这里)
            log.info("扫描进行中,返回已有缓存");
            return cachedVo;
        }
        if (!Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("目标项目根不存在或不是目录: " + projectRootStr);
        }
        scanning = true;
        try {
            ProjectScanVO vo = doScan();
            cachedVo = vo;
            cachedFlat = vo.getClasses();
            cachedAt = Instant.now();
            return vo;
        } finally {
            scanning = false;
        }
    }

    /** 返回缓存 VO(不触发扫描),未扫描返回 null */
    public ProjectScanVO getCachedScan() {
        return cachedVo;
    }

    /** 返回缓存扁平列表(不触发扫描),未扫描返回空列表 */
    public List<IndexedClassInfo> getCachedClasses() {
        return cachedFlat != null ? cachedFlat : List.of();
    }

    /** 按简单类名查(首个匹配) */
    public Optional<IndexedClassInfo> findBySimpleName(String simpleName) {
        if (simpleName == null || cachedFlat == null) return Optional.empty();
        return cachedFlat.stream().filter(c -> simpleName.equals(c.simpleName())).findFirst();
    }

    /**
     * 内存过滤(不重扫)。
     *
     * @param module  模块名过滤(null 忽略)
     * @param type    ClassType 名过滤(null 忽略)
     * @param nameLike 类名包含子串(null 忽略)
     */
    public List<IndexedClassInfo> filter(String module, ClassType type, String nameLike) {
        List<IndexedClassInfo> base = getCachedClasses();
        return base.stream()
                .filter(c -> module == null || module.equals(c.module()))
                .filter(c -> type == null || type == c.type())
                .filter(c -> nameLike == null || nameLike.isBlank()
                        || (c.simpleName() != null && c.simpleName().contains(nameLike)))
                .toList();
    }

    // ─── 内部:扫描实现 ───

    private ProjectScanVO doScan() {
        long start = System.currentTimeMillis();
        List<IndexedClassInfo> flat = new ArrayList<>();
        AtomicInteger totalFiles = new AtomicInteger(0);
        AtomicInteger skippedFiles = new AtomicInteger(0);

        try (Stream<Path> walk = Files.walk(projectRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !isInExcludedDir(p))
                    .filter(p -> isWithinRoot(p, projectRoot))
                    .forEach(p -> {
                        totalFiles.incrementAndGet();
                        if (totalFiles.get() > MAX_FILES_GUARD) {
                            throw new IllegalArgumentException(
                                    "文件数超过上限 " + MAX_FILES_GUARD + ",请确认扫描的是单个 BladeX 项目");
                        }
                        IndexedClassInfo info = parseFile(p, projectRoot);
                        if (info != null) {
                            flat.add(info);
                        } else {
                            skippedFiles.incrementAndGet();
                        }
                    });
        } catch (IllegalArgumentException e) {
            throw e; // 上限校验向上抛 → 400
        } catch (IOException e) {
            log.warn("扫描目录失败: {}", projectRootStr, e);
            throw new IllegalArgumentException("扫描目录失败: " + e.getMessage());
        }

        long duration = System.currentTimeMillis() - start;
        ProjectScanVO vo = buildVo(flat, totalFiles.get(), skippedFiles.get(), duration);
        log.info("项目扫描完成: root={}, totalFiles={}, indexedClasses={}, skipped={}, 耗时={}ms",
                projectRootStr, totalFiles.get(), flat.size(), skippedFiles.get(), duration);
        return vo;
    }

    /** 解析单个 .java 文件,返回首个顶层类型的索引(多顶层类型只取首个,保持清单整洁)。失败返回 null。 */
    private IndexedClassInfo parseFile(Path file, Path root) {
        try {
            // 超大文件跳过
            if (Files.size(file) > MAX_FILE_BYTES) {
                log.warn("文件超大跳过({} bytes): {}", Files.size(file), root.relativize(file));
                return null;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JavaParser parser = new JavaParser();
            Optional<CompilationUnit> opt = parser.parse(content).getResult();
            if (opt.isEmpty()) return null;
            CompilationUnit cu = opt.get();
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            // 只取顶层类型(cu.getTypes()),不递归内部类
            for (ClassOrInterfaceDeclaration cid : cu.getTypes().stream()
                    .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                    .map(t -> (ClassOrInterfaceDeclaration) t)
                    .toList()) {
                return buildInfo(cid, pkg, file, root);
            }
            return null;
        } catch (Exception e) {
            log.warn("扫描解析失败: file={}, err={}", root.relativize(file), e.getMessage());
            return null;
        }
    }

    /** 从解析出的类声明构建索引条目 */
    private IndexedClassInfo buildInfo(ClassOrInterfaceDeclaration cid, String pkg, Path file, Path root) {
        String relPath = root.relativize(file).toString().replace('\\', '/');
        ClassType type = ClassType.fromDeclaration(cid, pkg);
        String module = deriveModule(relPath, pkg);
        String side = deriveSide(relPath, pkg);
        String mavenModulePath = deriveMavenModulePath(relPath);

        // public 非静态非构造方法签名: name(Type1,Type2)
        List<String> methods = new ArrayList<>();
        for (MethodDeclaration md : cid.getMethods()) {
            if (!md.isPublic() || md.isStatic() || md.isConstructorDeclaration()) continue;
            StringBuilder sig = new StringBuilder(md.getNameAsString()).append("(");
            boolean first = true;
            for (var p : md.getParameters()) {
                if (!first) sig.append(",");
                sig.append(p.getType().toString().replaceAll("\\s+", ""));
                first = false;
            }
            sig.append(")");
            methods.add(sig.toString());
        }

        // 非静态字段: name -> type(排除 serialVersionUID)
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldDeclaration fd : cid.getFields()) {
            if (fd.isStatic()) continue;
            String fieldType = fd.getElementType().toString();
            for (VariableDeclarator v : fd.getVariables()) {
                fields.put(v.getNameAsString(), fieldType);
            }
        }

        return new IndexedClassInfo(
                cid.getNameAsString(),
                pkg,
                type,
                cid.isInterface(),
                module,
                side,
                mavenModulePath,
                relPath,
                extractTableName(cid),
                methods,
                fields,
                collectImports(cid)
        );
    }

    /** 收集全限定 import 列表 */
    private List<String> collectImports(ClassOrInterfaceDeclaration cid) {
        return cid.findCompilationUnit()
                .map(cu -> cu.getImports().stream().map(ImportDeclaration::getNameAsString).toList())
                .orElse(List.of());
    }

    /** 从 @TableName 注解提取表名,无则 null(复用 CrossFileValidator 同款正则) */
    private String extractTableName(ClassOrInterfaceDeclaration cid) {
        for (var anno : cid.getAnnotations()) {
            if (!"TableName".equals(anno.getNameAsString())) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"([^\"]+)\"").matcher(anno.toString());
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** 推导逻辑模块名:优先用 BladeXModuleLayout,失败 fallback 到 Java 包路径 parts[2] */
    private String deriveModule(String relPath, String pkg) {
        String m = BladeXModuleLayout.moduleOfPath(relPath);
        if (m != null) return m;
        // fallback: org.springblade.{module}.xxx
        if (pkg != null && pkg.startsWith("org.springblade.")) {
            String[] parts = pkg.split("\\.");
            if (parts.length >= 3) return parts[2];
        }
        return null;
    }

    /** 推导归属侧:优先用 BladeXModuleLayout,失败 fallback 到 Maven 模块前缀 */
    private String deriveSide(String relPath, String pkg) {
        String s = BladeXModuleLayout.sideOfPath(relPath);
        if (!"OTHER".equals(s)) return s;
        // fallback: 按路径前缀判断 blade-auth/gateway/common → PLATFORM, blade-ops* → OPS
        if (relPath.startsWith("blade-auth/") || relPath.startsWith("blade-gateway/")
                || relPath.startsWith("blade-common/")) return "PLATFORM";
        if (relPath.startsWith("blade-ops")) return "OPS";
        return "OTHER";
    }

    /** 推导 Maven 模块路径(如 blade-service/blade-order),无则 null */
    private String deriveMavenModulePath(String relPath) {
        // 取路径第一段/第二段:如 blade-service/blade-order/... → blade-service/blade-order
        String[] parts = relPath.split("/");
        if (parts.length >= 2 && parts[0].startsWith("blade-")) {
            return parts[0] + "/" + parts[1];
        }
        if (parts.length >= 1 && parts[0].startsWith("blade-")) {
            return parts[0];
        }
        return null;
    }

    /** 路径是否在排除目录内(target/.git 等) */
    private boolean isInExcludedDir(Path p) {
        // 扫描根自身不排除:当 target-project-root 恰为 ai-generated-modules 等排除名时,
        // 只排除其下的 target/.git 等子目录,不把根本身计入(否则全量被过滤,扫到 0 文件)。
        String rootName = projectRoot.getFileName() != null ? projectRoot.getFileName().toString() : "";
        for (Path segment : p) {
            String name = segment.toString();
            if (name.equals(rootName)) continue;
            if (EXCLUDED_DIRS.contains(name)) return true;
        }
        return false;
    }

    /** 路径遍历防护:规范化后必须在根之内 */
    private boolean isWithinRoot(Path child, Path root) {
        Path normalized = child.toAbsolutePath().normalize();
        return normalized.startsWith(root.toAbsolutePath().normalize());
    }

    /** 组装 VO:扁平列表 + 按模块分组 */
    private ProjectScanVO buildVo(List<IndexedClassInfo> flat, int totalFiles, int skippedFiles, long duration) {
        ProjectScanVO vo = new ProjectScanVO();
        ProjectScanVO.ScanMeta meta = new ProjectScanVO.ScanMeta();
        meta.setProjectRoot(projectRootStr);
        meta.setScannedAt(LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        meta.setTotalFiles(totalFiles);
        meta.setIndexedClasses(flat.size());
        meta.setSkippedFiles(skippedFiles);
        meta.setDurationMillis(duration);
        meta.setFromCache(false);
        vo.setMeta(meta);
        vo.setClasses(flat);

        // 按模块分组(module 为 null 的归到 "(未识别)")
        Map<String, List<IndexedClassInfo>> byModule = new LinkedHashMap<>();
        for (IndexedClassInfo c : flat) {
            String m = c.module() != null ? c.module() : "(未识别)";
            byModule.computeIfAbsent(m, k -> new ArrayList<>()).add(c);
        }
        List<ProjectScanVO.ModuleGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<IndexedClassInfo>> e : byModule.entrySet()) {
            ProjectScanVO.ModuleGroup g = new ProjectScanVO.ModuleGroup();
            g.setModule(e.getKey());
            g.setClassCount(e.getValue().size());
            Map<String, Integer> typeCounts = new LinkedHashMap<>();
            for (IndexedClassInfo c : e.getValue()) {
                typeCounts.merge(c.type().name(), 1, Integer::sum);
            }
            g.setTypeCounts(typeCounts);
            g.setClasses(e.getValue());
            groups.add(g);
        }
        vo.setModules(groups);
        return vo;
    }
}
