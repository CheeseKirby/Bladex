package org.springblade.aiworkflow.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.enums.ClassType;
import org.springblade.aiworkflow.vo.ProjectScanVO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * 参考项目索引器 — 阶段2增强。
 *
 * <p>与 {@link ExistingProjectIndex} 区别:**root 可变**(用户通过前端设置参考项目路径)。
 * 用途:REAL 模式生成代码时,从参考项目找同类代码,提取结构化摘要注入 prompt,
 * 让生成的新模块贴合现有风格。参考项目**只读**,不写入(写入目标仍是 blade_hgsjy)。
 *
 * <p>设计:
 * <ul>
 *   <li>root 可变:{@code setPath} 切换参考项目,清缓存重新扫描;</li>
 *   <li>懒加载:setPath 不立即扫,首次调 scan 才扫;</li>
 *   <li>结构化摘要:{@link #buildStructuredSummary} 解决大类截断丢信息问题(字段/签名全在,去方法体);</li>
 *   <li>容错:单文件解析失败跳过,不中断扫描。</li>
 * </ul>
 */
@Slf4j
public class ReferenceProjectIndex {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "target", ".git", ".idea", "node_modules", "ai-generated", "ai-generated-modules");
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_FILES_GUARD = 20_000;
    /** 小类阈值:源码 < 此值直接放完整代码,不走摘要 */
    private static final int SMALL_CLASS_THRESHOLD = 2000;

    /** 参考项目根路径(可变),null 表示未设置。所有访问 synchronized 同步(volatile 不够保证多字段一致性) */
    private String rootPath;
    private Path projectRoot;
    private ProjectScanVO cachedVo;
    private List<IndexedClassInfo> cachedFlat;

    /** 设置参考项目路径。先清缓存再改 root,避免并发读到"新 root + 旧 cache"组合。null/空表示取消参考 */
    public synchronized void setPath(String path) {
        // 先清缓存,再改 root — 确保读方法不会拿到"新 root + 旧 cache"的错配
        this.cachedVo = null;
        this.cachedFlat = null;
        this.rootPath = (path == null || path.isBlank()) ? null : path.trim();
        this.projectRoot = (this.rootPath != null)
                ? Paths.get(this.rootPath).toAbsolutePath().normalize() : null;
        if (this.rootPath != null && !Files.isDirectory(this.projectRoot)) {
            log.warn("参考项目路径不存在或不是目录: {}", this.rootPath);
        }
    }

    /** 是否已扫描就绪(可作参考) */
    public synchronized boolean isReady() {
        return cachedFlat != null && !cachedFlat.isEmpty();
    }

    /** 当前路径(null 表示未设置) */
    public synchronized String getPath() {
        return rootPath;
    }

    /**
     * 触发扫描。
     *
     * @param force true 强制重扫;false 有缓存返回缓存
     * @return 扫描结果 VO
     * @throws IllegalArgumentException 根不存在/非目录/文件数超限
     */
    public synchronized ProjectScanVO scan(boolean force) {
        if (rootPath == null) {
            throw new IllegalArgumentException("参考项目路径未设置");
        }
        if (!force && cachedVo != null) return cachedVo;
        if (!Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("参考项目根不存在或不是目录: " + rootPath);
        }
        ProjectScanVO vo = doScan();
        cachedVo = vo;
        cachedFlat = vo.getClasses();
        return vo;
    }

    public synchronized ProjectScanVO getCachedScan() {
        return cachedVo;
    }

    public synchronized List<IndexedClassInfo> getCachedClasses() {
        return cachedFlat != null ? cachedFlat : List.of();
    }

    /**
     * 找同类参考代码。
     *
     * @param type          目标类型(生成 Entity 就找现有 Entity)
     * @param excludeModule 排除的模块(保留参数兼容,参考项目独立于目标,实际不排除 — 传 null 即可)
     * @return 任一同类(不再按路径长度排序 — 短路径不等于小类,启发式不准),无匹配返回 empty
     */
    public synchronized Optional<IndexedClassInfo> findReferenceExample(ClassType type, String excludeModule) {
        if (cachedFlat == null) return Optional.empty();
        // 参考项目独立于生成目标,不存在"刚生成的"代码,无需排除同模块 — excludeModule 忽略
        // 排除 PLATFORM 侧基础设施类(auth/gateway/common 的类风格不代表业务模块),优先业务模块
        return cachedFlat.stream()
                .filter(c -> c.type() == type)
                .filter(c -> c.side() == null || !"PLATFORM".equals(c.side()))
                .findFirst();
    }

    /**
     * 读参考项目单个文件源码。带 isWithinRoot 防护。
     *
     * @param relativePath 相对参考项目根的路径
     * @return 源码内容,失败返回 null
     */
    public String readSourceContent(String relativePath) {
        if (projectRoot == null || relativePath == null) return null;
        try {
            Path file = projectRoot.resolve(relativePath).normalize();
            if (!isWithinRoot(file, projectRoot)) return null;
            if (!Files.isRegularFile(file)) return null;
            if (Files.size(file) > MAX_FILE_BYTES) return null;
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读参考项目文件失败: {}, err={}", relativePath, e.getMessage());
            return null;
        }
    }

    /**
     * 构建项目适配摘要 — 阶段2增强"适配并接入参考项目"。
     *
     * <p>提取参考项目的结构约定,让新模块的 pom/配置/包结构/启动类都与参考项目一致,
     * 能直接接入参考项目编译运行(不只是贴合代码风格)。
     *
     * <p>提取内容:
     * <ul>
     *   <li>父 pom: groupId / revision 版本 / 已有 modules(避免重复)</li>
     *   <li>模块命名约定: blade-{module}-api / blade-{module}(service) 双模块结构</li>
     *   <li>子 pom 依赖风格: parent / blade-core-boot / blade-starter-* 等标准依赖</li>
     *   <li>配置风格: bootstrap.yml 的 nacos namespace / application-dev.yml 模式</li>
     *   <li>包路径约定: org.springblade.{module}.xxx</li>
     *   <li>Application 启动类风格: @SpringBootApplication + BladeApplication.run</li>
     * </ul>
     *
     * @return 适配摘要文本(注入 prompt);参考项目未设置/提取失败返回 null
     */
    public synchronized String buildAdaptationSummary() {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return null;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("== 参考项目结构适配(新模块的 pom/配置/包结构/启动类须与之一致,以接入参考项目编译)==\n");

            // 1. 父 pom: groupId / revision / modules
            String parentPom = readSourceContent("pom.xml");
            if (parentPom != null) {
                extractParentPomInfo(parentPom, sb);
            }

            // 2. 模块命名约定 + 子 pom 依赖风格(取一个 api + 一个 service 示例)
            extractModuleConvention(sb);

            // 3. 配置风格(bootstrap.yml + application-dev.yml)
            extractConfigConvention(sb);

            // 4. Application 启动类风格(取一个示例)
            extractApplicationConvention(sb);

            // 5. 项目结构分析(模块树/现有模块/包结构/衔接点) - 让 Part A 总方案理清新模块与参考项目的衔接
            extractProjectStructure(sb);

            return sb.toString();
        } catch (Exception e) {
            log.warn("构建项目适配摘要失败: {}", e.getMessage());
            return null;
        }
    }

    /** 提取父 pom: groupId / revision / java.version / spring-boot / 已有 modules */
    private void extractParentPomInfo(String pom, StringBuilder sb) {
        sb.append("[父 pom]\n");
        // groupId
        java.util.regex.Matcher gm = java.util.regex.Pattern
                .compile("<groupId>([^<]+)</groupId>").matcher(pom);
        if (gm.find()) sb.append("groupId: ").append(gm.group(1)).append("\n");
        // revision(BladeX 版本)
        java.util.regex.Matcher rm = java.util.regex.Pattern
                .compile("<revision>([^<]+)</revision>").matcher(pom);
        if (rm.find()) sb.append("bladex revision: ").append(rm.group(1)).append("\n");
        // java.version(关键: 决定能否用 record/sealed/var 等新语法)
        java.util.regex.Matcher jm = java.util.regex.Pattern
                .compile("<java\\.version>([^<]+)</java\\.version>").matcher(pom);
        String javaVer = jm.find() ? jm.group(1) : null;
        if (javaVer != null) sb.append("java.version: ").append(javaVer).append("\n");
        // spring-boot.version(SB3 用 jakarta, SB2 用 javax)
        java.util.regex.Matcher sm2 = java.util.regex.Pattern
                .compile("<spring-boot\\.version>([^<]+)</spring-boot\\.version>").matcher(pom);
        if (sm2.find()) sb.append("spring-boot.version: ").append(sm2.group(1)).append("\n");
        // spring-cloud.version
        java.util.regex.Matcher cm = java.util.regex.Pattern
                .compile("<spring-cloud\\.version>([^<]+)</spring-cloud\\.version>").matcher(pom);
        if (cm.find()) sb.append("spring-cloud.version: ").append(cm.group(1)).append("\n");
        // 已有 modules
        java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("<modules>([\\s\\S]*?)</modules>").matcher(pom);
        if (mm.find()) {
            String modulesBlock = mm.group(1);
            java.util.regex.Matcher sm = java.util.regex.Pattern
                    .compile("<module>([^<]+)</module>").matcher(modulesBlock);
            List<String> mods = new ArrayList<>();
            while (sm.find()) mods.add(sm.group(1));
            sb.append("已有 modules: ").append(String.join(", ", mods)).append("\n");
        }
        // 版本适配约束(注入 prompt 让 LLM 按此版本生成)
        sb.append("[版本适配约束(生成代码必须遵守)]\n");
        if (javaVer != null) {
            int major = parseJavaMajor(javaVer);
            if (major > 0 && major < 17) {
                sb.append("- Java ").append(major).append(": 禁用 record/sealed/var/switch表达式/文本块等 Java 17+ 语法\n");
            } else if (major >= 17) {
                sb.append("- Java ").append(major).append(": 可用 Java 17 语法\n");
            }
        }
        // jakarta vs javax: 扫参考项目现有 import 判定(SB3=jakarta, SB2=javax)
        String servletApi = detectJakartaOrJavax();
        if (servletApi != null) {
            sb.append("- Servlet/validation 注解用 ").append(servletApi).append(".* (").append(servletApi.equals("jakarta") ? "Spring Boot 3.x" : "Spring Boot 2.x").append(")\n");
        }
        // Swagger 版本: v2(io.swagger.annotations.@ApiModel/@ApiModelProperty) vs v3(io.swagger.v3.oas.annotations.@Schema)
        String swagger = detectSwaggerVersion();
        if (swagger != null) {
            if ("v2".equals(swagger)) {
                sb.append("- Swagger 用 v2: import io.swagger.annotations.ApiModel/ApiModelProperty, 类用 @ApiModel(value=\"X对象\"), 字段用 @ApiModelProperty(value=\"描述\")\n");
                sb.append("  (禁用 v3 的 io.swagger.v3.oas.annotations.@Schema)\n");
            } else {
                sb.append("- Swagger 用 v3: import io.swagger.v3.oas.annotations.media.Schema, 类/字段用 @Schema(description=\"描述\")\n");
            }
        }
        sb.append("\n");
    }

    /** 解析 java.version 字符串取主版本号("17"→17, "1.8"→8, "11"→11) */
    private int parseJavaMajor(String javaVer) {
        try {
            String v = javaVer.trim();
            if (v.startsWith("1.")) return Integer.parseInt(v.substring(2, v.indexOf('.', 2) < 0 ? v.length() : v.indexOf('.', 2)));
            return Integer.parseInt(v.contains(".") ? v.substring(0, v.indexOf('.')) : v);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 扫参考项目现有 .java 的 import,判定 jakarta 还是 javax(SB3=jakarta, SB2=javax) */
    private String detectJakartaOrJavax() {
        if (projectRoot == null) return null;
        boolean hasJakarta = false;
        boolean hasJavax = false;
        try (Stream<Path> walk = Files.walk(projectRoot, 10)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("target"))
                    ::iterator) {
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    if (content.contains("import jakarta.")) hasJakarta = true;
                    if (content.contains("import javax.")) hasJavax = true;
                    if (hasJakarta && hasJavax) break;
                } catch (IOException ignored) { }
            }
        } catch (IOException e) {
            return null;
        }
        log.info("参考项目 jakarta/javax 检测: jakarta={}, javax={}", hasJakarta, hasJavax);
        return hasJakarta ? "jakarta" : (hasJavax ? "javax" : null);
    }

    /** 扫参考项目现有 .java 的 import,判定 Swagger v2 还是 v3 */
    private String detectSwaggerVersion() {
        if (projectRoot == null) return null;
        boolean hasV2 = false;
        boolean hasV3 = false;
        try (Stream<Path> walk = Files.walk(projectRoot, 10)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("target"))
                    ::iterator) {
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    if (content.contains("import io.swagger.annotations.")) hasV2 = true;
                    if (content.contains("import io.swagger.v3.oas.annotations.")) hasV3 = true;
                    if (hasV2 && hasV3) break;
                } catch (IOException ignored) { }
            }
        } catch (IOException e) {
            return null;
        }
        log.info("参考项目 Swagger 检测: v2={}, v3={}", hasV2, hasV3);
        // v2 优先(老项目 BladeX 2.x 用 v2);都有则 v2(参考项目以 v2 为主)
        return hasV2 ? "v2" : (hasV3 ? "v3" : null);
    }

    /** 提取模块命名约定 + 子 pom 依赖风格(取参考项目里一个 api + 一个 service 示例) */
    private void extractModuleConvention(StringBuilder sb) {
        sb.append("[模块结构约定]\n");
        sb.append("- 双模块: blade-{module}-api(API 模块,放 entity/vo/feign) + blade-{module}(service 模块,放 controller/service/mapper/wrapper)\n");
        sb.append("- 新模块父 pom 的 parent: api 模块用 blade-service-api, service 模块用 blade-service\n");

        // 取一个 service 子 pom 看依赖风格
        Path servicePom = findFirstPom(projectRoot.resolve("blade-service"));
        if (servicePom != null) {
            String pomContent = readSourceContent(projectRoot.relativize(servicePom).toString().replace('\\', '/'));
            if (pomContent != null) {
                sb.append("- service 模块标准依赖(参考):\n");
                java.util.regex.Matcher dm = java.util.regex.Pattern
                        .compile("<artifactId>(blade-[^<]+)</artifactId>").matcher(pomContent);
                List<String> deps = new ArrayList<>();
                while (dm.find()) deps.add(dm.group(1));
                if (!deps.isEmpty()) sb.append("  ").append(String.join(", ", deps)).append("\n");
            }
        }
        sb.append("\n");
    }

    /** 提取配置风格(bootstrap.yml 的 nacos namespace) */
    private void extractConfigConvention(StringBuilder sb) {
        sb.append("[配置风格]\n");
        Path bootstrap = findFirstFile(projectRoot, "bootstrap.yml");
        if (bootstrap != null) {
            String content = readSourceContent(projectRoot.relativize(bootstrap).toString().replace('\\', '/'));
            if (content != null) {
                java.util.regex.Matcher nm = java.util.regex.Pattern
                        .compile("namespace:\\s*([^\\s]+)").matcher(content);
                if (nm.find()) sb.append("- nacos namespace: ").append(nm.group(1)).append("\n");
            }
        }
        sb.append("- bootstrap.yml + application-dev.yml 双配置文件\n");

        // 多租户配置(影响新表是否需 tenant_id + Entity 基类选择)
        Path appDev = findFirstFile(projectRoot, "application-dev.yml");
        if (appDev != null) {
            String appContent = readSourceContent(projectRoot.relativize(appDev).toString().replace('\\', '/'));
            if (appContent != null) {
                java.util.regex.Matcher tm = java.util.regex.Pattern
                        .compile("tenant.*enable:\\s*(\\w+)").matcher(appContent);
                if (tm.find()) {
                    String tenantEnabled = tm.group(1);
                    sb.append("- 多租户: tenant.enable=").append(tenantEnabled).append("\n");
                    if ("true".equalsIgnoreCase(tenantEnabled)) {
                        sb.append("  (新表必须含 tenant_id 列, Entity extends TenantEntity)\n");
                    } else {
                        sb.append("  (新表无需 tenant_id, Entity extends BaseEntity)\n");
                    }
                }
            }
        }
        sb.append("\n");
    }

    /** 提取 Application 启动类风格 */
    private void extractApplicationConvention(StringBuilder sb) {
        sb.append("[启动类风格]\n");
        Path app = findFirstFile(projectRoot, "Application.java");
        if (app != null) {
            String content = readSourceContent(projectRoot.relativize(app).toString().replace('\\', '/'));
            if (content != null) {
                // 看有没有 BladeApplication.run
                if (content.contains("BladeApplication")) {
                    sb.append("- 使用 BladeApplication.run(源, BladeX 标配)\n");
                } else if (content.contains("SpringApplication.run")) {
                    sb.append("- 使用 SpringApplication.run\n");
                }
            }
        }
        sb.append("- @SpringBootApplication + @EnableFeignClients 等标准注解\n");
        sb.append("\n");
    }

    /**
     * 提取项目结构分析 - 模块树/现有模块/包结构示例/衔接点。
     * 让 Part A 总方案理清新模块与参考项目的衔接(不扫具体代码,只了解结构)。
     */
    private void extractProjectStructure(StringBuilder sb) {
        sb.append("[项目结构分析]\n");

        // 1. 父 pom modules(模块树)
        String parentPom = readSourceContent("pom.xml");
        if (parentPom != null) {
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("<module>([^<]+)</module>").matcher(parentPom);
            List<String> modules = new ArrayList<>();
            while (mm.find()) modules.add(mm.group(1));
            sb.append("- 父 pom modules: ").append(modules.isEmpty() ? "(无)" : String.join(", ", modules)).append("\n");
        }

        // 2. 现有模块清单(blade-service-api/blade-*-api + blade-service/blade-*)
        List<String> apiModules = listChildModules("blade-service-api", "-api");
        List<String> serviceModules = listChildModules("blade-service", "");
        if (!apiModules.isEmpty()) {
            sb.append("- 现有 API 模块: ").append(String.join(", ", apiModules)).append("\n");
        }
        if (!serviceModules.isEmpty()) {
            sb.append("- 现有 Service 模块: ").append(String.join(", ", serviceModules)).append("\n");
        }

        // 3. 包结构示例(取第一个 service 模块的 org.springblade.{module} 包层次)
        if (!serviceModules.isEmpty()) {
            String firstModule = serviceModules.get(0);
            List<String> packages = listPackages(firstModule);
            if (!packages.isEmpty()) {
                sb.append("- 包结构示例(").append(firstModule).append("): ").append(String.join("/", packages)).append("\n");
            }
        }

        // 4. 衔接点(新模块如何接入参考项目)
        sb.append("- 新模块接入:\n");
        sb.append("  * 父 pom <modules> 注册 blade-service-api/blade-{module}-api + blade-service/blade-{module}\n");
        sb.append("  * Nacos 服务名: blade-{module}\n");
        sb.append("  * Feign client value: blade-{module}(与 Nacos 服务名一致, 不带 -service)\n");
        sb.append("  * 新模块包路径: org.springblade.{module}.pojo.entity / .pojo.vo / .controller / .service / .mapper / .wrapper\n");
        sb.append("  * Mapper XML 放 src/main/java 同包(需 pom 配置 resources 过滤 *.xml)\n");
        sb.append("\n");
    }

    /** 列出 blade-{parentDir} 下的子模块目录名(排除父目录自身) */
    private List<String> listChildModules(String parentDir, String suffix) {
        List<String> modules = new ArrayList<>();
        Path dir = projectRoot.resolve(parentDir);
        if (!Files.isDirectory(dir)) return modules;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("blade-") && name.endsWith(suffix) && !name.equals(parentDir))
                    .forEach(modules::add);
        } catch (IOException e) {
            log.warn("列出模块目录失败: {}", e.getMessage());
        }
        return modules;
    }

    /** 列出某 service 模块的包层次(如 controller/service/mapper/wrapper) */
    private List<String> listPackages(String module) {
        List<String> packages = new ArrayList<>();
        String moduleName = module.startsWith("blade-") ? module.substring(6) : module;
        Path pkgPath = projectRoot.resolve("blade-service").resolve(module)
                .resolve("src/main/java/org/springblade/" + moduleName);
        if (!Files.isDirectory(pkgPath)) return packages;
        try (Stream<Path> s = Files.list(pkgPath)) {
            s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .forEach(packages::add);
        } catch (IOException e) {
            log.warn("列出包目录失败: {}", e.getMessage());
        }
        return packages;
    }

    /** 在目录下找第一个 pom.xml */
    private Path findFirstPom(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.walk(dir, 2)) {
            return s.filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(Files::isRegularFile)
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** 在目录树下找第一个指定文件名的文件 */
    private Path findFirstFile(Path dir, String fileName) {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.walk(dir, 4)) {
            return s.filter(p -> p.getFileName().toString().equals(fileName))
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("target"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 构建结构化摘要 — 解决大类截断丢信息。
     *
     * <p>小类(源码 < {@link #SMALL_CLASS_THRESHOLD})直接返回完整代码;
     * 大类用 JavaParser 提取紧凑摘要:package + 关键 imports + 类注解 + 类声明 +
     * **全部字段(含注解)** + **方法签名(去方法体)**。字段/签名全在,prompt 长度可控。
     *
     * @param relativePath 相对参考项目根的路径
     * @return 完整代码(小类)或结构化摘要(大类),失败返回 null
     */
    public String buildStructuredSummary(String relativePath) {
        String content = readSourceContent(relativePath);
        if (content == null) return null;
        // 小类直接放完整
        if (content.length() < SMALL_CLASS_THRESHOLD) return content;
        // 大类:提取摘要
        try {
            JavaParser parser = new JavaParser();
            Optional<CompilationUnit> opt = parser.parse(content).getResult();
            if (opt.isEmpty()) {
                // 解析失败,降级截断(带省略号)
                return content.substring(0, SMALL_CLASS_THRESHOLD) + "\n// ...(内容过长,解析失败,已截断)";
            }
            CompilationUnit cu = opt.get();
            StringBuilder sb = new StringBuilder();
            // package
            cu.getPackageDeclaration().ifPresent(p -> sb.append(p).append("\n\n"));
            // 关键 imports(过滤 java.lang 等常见的)
            for (ImportDeclaration imp : cu.getImports()) {
                String name = imp.getNameAsString();
                if (!name.startsWith("java.lang") && !name.startsWith("java.io")) {
                    sb.append(imp).append("\n");
                }
            }
            sb.append("\n");
            // 类
            for (ClassOrInterfaceDeclaration cid : cu.getTypes().stream()
                    .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                    .map(t -> (ClassOrInterfaceDeclaration) t)
                    .toList()) {
                // 类注解
                for (var anno : cid.getAnnotations()) sb.append(anno).append("\n");
                // 类声明
                sb.append(cid.isInterface() ? "interface " : "class ").append(cid.getNameAsString());
                if (!cid.getExtendedTypes().isEmpty()) {
                    sb.append(" extends ").append(cid.getExtendedTypes().stream()
                            .map(t -> t.getNameAsString()).reduce((a, b) -> a + ", " + b).orElse(""));
                }
                if (!cid.getImplementedTypes().isEmpty()) {
                    sb.append(" implements ").append(cid.getImplementedTypes().stream()
                            .map(t -> t.getNameAsString()).reduce((a, b) -> a + ", " + b).orElse(""));
                }
                sb.append(" {\n\n");
                // 字段(含注解,完整保留 — 字段短但信息量大)
                for (FieldDeclaration fd : cid.getFields()) {
                    sb.append("  ").append(fd.toString().trim()).append("\n");
                }
                sb.append("\n");
                // 方法签名(含注解,去方法体)— 保留 @GetMapping/@Transactional 等关键风格信息
                for (MethodDeclaration md : cid.getMethods()) {
                    sb.append("  ").append(md.getDeclarationAsString(true, true, false)).append(";\n");
                }
                sb.append("}\n");
            }
            String summary = sb.toString();
            // 摘要仍超长则截断(极端情况)
            if (summary.length() > SMALL_CLASS_THRESHOLD * 2) {
                return summary.substring(0, SMALL_CLASS_THRESHOLD * 2) + "\n// ...(摘要超长,已截断)";
            }
            return summary;
        } catch (Exception e) {
            log.warn("构建结构化摘要失败: {}, err={}", relativePath, e.getMessage());
            return content.substring(0, SMALL_CLASS_THRESHOLD) + "\n// ...(摘要失败,已截断)";
        }
    }

    // ─── 内部:扫描实现(逻辑同 ExistingProjectIndex,root 可变) ───

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
                                    "文件数超过上限 " + MAX_FILES_GUARD);
                        }
                        IndexedClassInfo info = parseFile(p, projectRoot);
                        if (info != null) flat.add(info);
                        else skippedFiles.incrementAndGet();
                    });
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException("扫描参考项目失败: " + e.getMessage());
        }

        long duration = System.currentTimeMillis() - start;
        ProjectScanVO vo = buildVo(flat, totalFiles.get(), skippedFiles.get(), duration);
        log.info("参考项目扫描完成: root={}, totalFiles={}, indexedClasses={}, 耗时={}ms",
                rootPath, totalFiles.get(), flat.size(), duration);
        return vo;
    }

    private IndexedClassInfo parseFile(Path file, Path root) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) return null;
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JavaParser parser = new JavaParser();
            Optional<CompilationUnit> opt = parser.parse(content).getResult();
            if (opt.isEmpty()) return null;
            CompilationUnit cu = opt.get();
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            for (ClassOrInterfaceDeclaration cid : cu.getTypes().stream()
                    .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                    .map(t -> (ClassOrInterfaceDeclaration) t)
                    .toList()) {
                return buildInfo(cid, pkg, file, root);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private IndexedClassInfo buildInfo(ClassOrInterfaceDeclaration cid, String pkg, Path file, Path root) {
        String relPath = root.relativize(file).toString().replace('\\', '/');
        ClassType type = ClassType.fromDeclaration(cid, pkg);
        String module = deriveModule(relPath, pkg);
        String side = deriveSide(relPath, pkg);
        String mavenModulePath = deriveMavenModulePath(relPath);

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

        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldDeclaration fd : cid.getFields()) {
            if (fd.isStatic()) continue;
            String fieldType = fd.getElementType().toString();
            for (VariableDeclarator v : fd.getVariables()) {
                fields.put(v.getNameAsString(), fieldType);
            }
        }

        return new IndexedClassInfo(
                cid.getNameAsString(), pkg, type, cid.isInterface(),
                module, side, mavenModulePath, relPath,
                extractTableName(cid), methods, fields,
                cid.findCompilationUnit()
                        .map(cu -> cu.getImports().stream().map(ImportDeclaration::getNameAsString).toList())
                        .orElse(List.of()));
    }

    private String extractTableName(ClassOrInterfaceDeclaration cid) {
        for (var anno : cid.getAnnotations()) {
            if (!"TableName".equals(anno.getNameAsString())) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"([^\"]+)\"").matcher(anno.toString());
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private String deriveModule(String relPath, String pkg) {
        String m = BladeXModuleLayout.moduleOfPath(relPath);
        if (m != null) return m;
        if (pkg != null && pkg.startsWith("org.springblade.")) {
            String[] parts = pkg.split("\\.");
            if (parts.length >= 3) return parts[2];
        }
        return null;
    }

    private String deriveSide(String relPath, String pkg) {
        String s = BladeXModuleLayout.sideOfPath(relPath);
        if (!"OTHER".equals(s)) return s;
        if (relPath.startsWith("blade-auth/") || relPath.startsWith("blade-gateway/")
                || relPath.startsWith("blade-common/")) return "PLATFORM";
        if (relPath.startsWith("blade-ops")) return "OPS";
        return "OTHER";
    }

    private String deriveMavenModulePath(String relPath) {
        String[] parts = relPath.split("/");
        if (parts.length >= 2 && parts[0].startsWith("blade-")) {
            return parts[0] + "/" + parts[1];
        }
        if (parts.length >= 1 && parts[0].startsWith("blade-")) {
            return parts[0];
        }
        return null;
    }

    /** 路径是否在排除目录内(跳过扫描根自身名字,避免参考路径本身叫 target 等被全过滤) */
    private boolean isInExcludedDir(Path p) {
        Path rootNorm = projectRoot != null ? projectRoot.getFileName() : null;
        for (Path segment : p) {
            String name = segment.toString();
            // 跳过扫描根自身的名字(如参考路径本身是 .../target/xxx,根段 target 不算排除)
            if (rootNorm != null && rootNorm.equals(segment)) continue;
            if (EXCLUDED_DIRS.contains(name)) return true;
        }
        return false;
    }

    private boolean isWithinRoot(Path child, Path root) {
        Path normalized = child.toAbsolutePath().normalize();
        return normalized.startsWith(root.toAbsolutePath().normalize());
    }

    private ProjectScanVO buildVo(List<IndexedClassInfo> flat, int totalFiles, int skippedFiles, long duration) {
        ProjectScanVO vo = new ProjectScanVO();
        ProjectScanVO.ScanMeta meta = new ProjectScanVO.ScanMeta();
        meta.setProjectRoot(rootPath);
        meta.setScannedAt(LocalDateTime.ofInstant(java.time.Instant.now(), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        meta.setTotalFiles(totalFiles);
        meta.setIndexedClasses(flat.size());
        meta.setSkippedFiles(skippedFiles);
        meta.setDurationMillis(duration);
        meta.setFromCache(false);
        vo.setMeta(meta);
        vo.setClasses(flat);
        // 按模块分组
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
