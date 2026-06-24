package org.springblade.aiworkflow.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 跨文件契约校验器 — Layer 2 审查机制。
 *
 * <p>{@link ConventionValidator}(Layer 1)只检查单文件内规范;
 * 本类解析一个子方案生成的<strong>全部 Java 文件</strong>,检查文件之间的调用契约是否闭合:
 * <ul>
 *   <li>Controller 调用的 Wrapper 方法(entity/entityVO)是否存在于 Wrapper 类;</li>
 *   <li>Controller/Service import 的 VO 类是否在生成的文件集合中, 包路径是否一致;</li>
 *   <li>Wrapper 是否提供了 entity(IVO)/entity(UVO) 转换方法;</li>
 *   <li>Feign 接口是否引用了不在生成集合中的 fallback 类。</li>
 * </ul>
 *
 * <p>设计取舍:不依赖完整 classpath(symbol solver 成本高、对 BladeX 平台类解析困难),
 * 只做"生成文件集合内部"的契约校验 — 这恰好覆盖 AI 生成时最常见的跨文件不一致问题。
 * 平台类(BaseEntity/R 等)由 ConventionValidator 的规则保证,这里不重复。
 *
 * <p>非阻断策略:发现契约不一致时记录为 WARN 级 issue(返回但不阻断写盘),
 * 因为跨文件修复需要重生成,留给后续 maxReviewRetries 重试时由 LLM 修复;
 * 若需阻断,调用方可根据 issues 里 ERROR 级判断。
 *
 * @author AI Developer
 */
@Slf4j
public class CrossFileValidator {

    /**
     * 校验一组生成的 Java 文件之间的契约一致性。
     *
     * @param files 同一子方案生成的全部 Java 文件 (GeneratedFile 列表, content 为源码)
     * @return 校验结果, issues 列表为空表示通过
     */
    public List<ContractIssue> validate(List<GeneratedFile> files) {
        List<ContractIssue> issues = new ArrayList<>();
        if (files == null || files.isEmpty()) return issues;

        JavaParser parser = new JavaParser();

        // 解析所有文件, 建立: 类名 -> (包路径, 方法签名集合, 是否接口) 索引
        Map<String, ClassInfo> index = new HashMap<>();
        List<CompilationUnit> units = new ArrayList<>();
        // DDL 表: module -> DdlTable; Mapper XML 文件供 namespace 校验
        Map<String, DdlTable> ddlTables = new HashMap<>();
        List<GeneratedFile> mapperXmls = new ArrayList<>();
        for (GeneratedFile f : files) {
            if (f.getContent() == null) continue;
            String fpath = f.getFilePath() == null ? "" : f.getFilePath();
            if (fpath.endsWith(".sql")) {
                parseDdl(f.getContent(), ddlTables, issues);
                continue;
            }
            if (fpath.endsWith(".xml")) {
                mapperXmls.add(f);
                continue;
            }
            if (!fpath.endsWith(".java")) continue;
            try {
                Optional<CompilationUnit> opt = parser.parse(f.getContent()).getResult();
                if (opt.isEmpty()) continue;
                CompilationUnit cu = opt.get();
                units.add(cu);
                String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    ClassInfo info = new ClassInfo(cid.getNameAsString(), pkg, cid.isInterface());
                    for (MethodDeclaration md : cid.getMethods()) {
                        info.methods.add(md.getNameAsString() + "(" + md.getParameters().size() + ")");
                    }
                    index.put(cid.getNameAsString(), info);
                }
            } catch (Exception e) {
                log.warn("跨文件校验解析失败: filePath={}, err={}", f.getFilePath(), e.getMessage());
            }
        }

        // 收集所有生成文件的类名集合(用于检查 import 引用是否落在集合内)
        Set<String> generatedClassNames = index.keySet();

        // 1. 检查 import: 是否有 import 指向"生成集合内应有但包路径不一致"的类
        //    典型: import org.springblade.order.vo.ivo.OrderIVO, 但 OrderIVO 实际包是 vo
        for (CompilationUnit cu : units) {
            String currentPkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            for (com.github.javaparser.ast.ImportDeclaration imp : cu.getImports()) {
                String imped = imp.getNameAsString();
                String simpleName = simpleName(imped);
                // 仅当被 import 的类在生成集合里才校验包路径一致性
                if (generatedClassNames.contains(simpleName)) {
                    ClassInfo target = index.get(simpleName);
                    if (target != null && !target.pkg.isEmpty()) {
                        String expectedPkg = target.pkg;
                        String importPkg = imped.substring(0, Math.max(0, imped.length() - simpleName.length() - 1));
                        if (!expectedPkg.equals(importPkg)) {
                            issues.add(new ContractIssue("WARN",
                                    cu.getPrimaryTypeName().orElse("?") + " import 的 " + simpleName
                                            + " 包路径不一致: import=" + importPkg + ", 实际=" + expectedPkg,
                                    "CROSS-IMPORT-PATH"));
                        }
                    }
                }
            }
            // 静默使用 currentPkg 避免 unused (保留供未来同包校验)
            if (currentPkg.isEmpty()) {
                log.debug("文件无 package 声明");
            }
        }

        // 1.5 检查是否臆造了 BladeX 平台 Cache 类 (LLM 常见幻觉)
        //     blade_hgsjy 实际有 SysCache/UserCache/RegionCache/ParamCache/DictCache 等, 但没有 DeptCache。
        //     检出对 system.cache.* 下 Cache 类的引用, 标记为 WARN 提醒人工确认。
        for (CompilationUnit cu : units) {
            String file = cu.getStorage().map(s -> s.getFileName().toString()).orElse("?");
            for (com.github.javaparser.ast.ImportDeclaration imp : cu.getImports()) {
                String imped = imp.getNameAsString();
                if (imped.startsWith("org.springblade.system.cache.")
                        && imped.endsWith("Cache")) {
                    String cacheClass = simpleName(imped);
                    // 已知合法的平台 Cache 白名单 (blade_hgsjy 实际存在)
                    java.util.Set<String> KNOWN = java.util.Set.of(
                            "DictCache", "DictBizCache", "UserCache", "SysCache",
                            "RegionCache", "ParamCache", "ApiScopeCache", "DataScopeCache");
                    if (!KNOWN.contains(cacheClass)) {
                        // 升级为 ERROR 作审计；实际阻断+LLM修复重试由 ConventionValidator(GEN-PLATFORM-CACHE)承担，
                        // 因 cross 段当前为"仅记录不阻断写盘"。
                        issues.add(new ContractIssue("ERROR",
                                file + " import 了 " + imped + ", 该 Cache 类在 BladeX 平台不存在 (疑似 LLM 臆造), 会编译失败。"
                                        + "建议: 改用 BeanUtil.copy 做基础转换, 翻译字段留 TODO 注释",
                                "CROSS-PLATFORM-CACHE-HALLUCINATION"));
                    }
                }
            }
        }

        // 2. 检查方法调用: Controller 调用的 Wrapper 方法是否在 Wrapper 类中存在
        //    典型: OrderController 调 OrderWrapper.build().entity(ivo), 但 Wrapper 没这方法
        for (CompilationUnit cu : units) {
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                String methodName = call.getNameAsString();
                // 只关注典型转换方法: entity / entityVO
                if (!"entity".equals(methodName) && !"entityVO".equals(methodName)) continue;
                // 找到调用所在类
                Optional<ClassOrInterfaceDeclaration> ownerOpt = call.findAncestor(ClassOrInterfaceDeclaration.class);
                if (ownerOpt.isEmpty()) continue;
                String callerClass = ownerOpt.get().getNameAsString();
                // 推断被调用的目标类名 (xxx.method 形式取 xxx)
                if (call.getScope().isPresent() && call.getScope().get() instanceof com.github.javaparser.ast.expr.MethodCallExpr) {
                    // 链式 build().entity(...) — 找 build 返回类型不易, 跳过精确推断, 改用全局 Wrapper 索引
                }
                // 检查所有 Wrapper 类是否包含该方法 (粒度放宽: 只要任一 Wrapper 有该方法即视为存在)
                boolean anyWrapperHasMethod = index.values().stream()
                        .filter(c -> c.name.endsWith("Wrapper"))
                        .anyMatch(w -> w.methods.contains(methodName + "(" + call.getArguments().size() + ")"));
                if (!anyWrapperHasMethod) {
                    // 不报为 ERROR(可能调用的不是 Wrapper), 仅 WARN 记录便于排查
                    issues.add(new ContractIssue("WARN",
                            callerClass + " 调用 " + methodName + "(" + call.getArguments().size() + " 个参数)"
                                    + " 但生成集合中没有任何 Wrapper 类提供此方法签名",
                            "CROSS-METHOD-MISSING"));
                }
            }
        }

        // 3. Feign 接口检查: 是否引用了不在集合中的 fallback 类
        for (CompilationUnit cu : units) {
            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!cid.isInterface()) continue;
                if (!cid.getNameAsString().startsWith("I") || !cid.getNameAsString().endsWith("Client")) continue;
                // 检查 @FeignClient 注解的 fallback 属性引用的类是否在生成集合
                for (var anno : cid.getAnnotations()) {
                    if (!"FeignClient".equals(anno.getNameAsString())) continue;
                    String annoStr = anno.toString();
                    // 简单提取 fallback = Xxx.class
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("fallback\\s*=\\s*(\\w+)\\.class").matcher(annoStr);
                    while (m.find()) {
                        String fallbackClass = m.group(1);
                        if (!generatedClassNames.contains(fallbackClass)) {
                            issues.add(new ContractIssue("ERROR",
                                    cid.getNameAsString() + " 的 @FeignClient 引用 fallback=" + fallbackClass
                                            + ", 但该类未在本次生成集合中, 会导致编译失败",
                                    "CROSS-FEIGN-FALLBACK-MISSING"));
                        }
                    }
                }
            }
        }

        // 4. Wrapper 必须有 entity(IVO) / entity(UVO)
        for (ClassOrInterfaceDeclaration cid : units.stream()
                .flatMap(u -> u.findAll(ClassOrInterfaceDeclaration.class).stream())
                .filter(c -> c.getNameAsString().endsWith("Wrapper"))
                .toList()) {
            ClassInfo info = index.get(cid.getNameAsString());
            if (info == null) continue;
            // 查找同模块的 IVO/UVO 类名
            String wrapperName = cid.getNameAsString();
            String entityPrefix = wrapperName.substring(0, wrapperName.length() - "Wrapper".length());
            String ivoName = entityPrefix + "IVO";
            String uvoName = entityPrefix + "UVO";
            if (generatedClassNames.contains(ivoName) && !info.methods.contains("entity(1)")) {
                issues.add(new ContractIssue("ERROR",
                        wrapperName + " 缺少 entity(" + ivoName + ") 转换方法, Controller 的 /save 端点会调用它",
                        "CROSS-WRAPPER-ENTITY-IVO"));
            }
            if (generatedClassNames.contains(uvoName) && !info.methods.contains("entity(1)")) {
                issues.add(new ContractIssue("ERROR",
                        wrapperName + " 缺少 entity(" + uvoName + ") 转换方法, Controller 的 /update 端点会调用它",
                        "CROSS-WRAPPER-ENTITY-UVO"));
            }
        }

        // 5. entity 字段 ↔ DDL 列对齐（检出可见，不阻断写盘；治本靠 PromptBuilder 多租户/字段对齐约束）
        checkEntityDdlAlignment(units, ddlTables, issues);

        // 6. Mapper XML namespace 必须等于集合中对应 Mapper 接口的全限定名
        checkMapperXmlNamespace(mapperXmls, index, issues);

        return issues;
    }

    // ─── DDL 解析与 entity↔DDL 对齐 ───

    /** 匹配 CREATE TABLE 名 + 列定义体 */
    private static final java.util.regex.Pattern DDL_CREATE_TABLE = java.util.regex.Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\".]?(\\w+)[`\".]?\\s*\\(([^;]*)\\)");
    /** 匹配 DDL 列定义: 列名 + 类型首词 */
    private static final java.util.regex.Pattern DDL_COLUMN = java.util.regex.Pattern.compile(
            "(?im)^\\s*`?(\\w+)`?\\s+(\\w+)");

    /** BaseEntity/TenantEntity 内置列（snake_case），对齐时排除 */
    private static final java.util.Set<String> BASE_ENTITY_COLUMNS = java.util.Set.of(
            "id", "create_user", "create_time", "update_user", "update_time",
            "status", "is_deleted", "tenant_id");

    /** DDL 约束/索引关键字行，列解析时跳过 */
    private static final java.util.Set<String> DDL_NON_COLUMN_KEYWORDS = java.util.Set.of(
            "primary", "key", "unique", "constraint", "index", "foreign", "fulltext", "check");

    private void parseDdl(String sql, Map<String, DdlTable> ddlTables, List<ContractIssue> issues) {
        if (sql == null || sql.isBlank()) return;
        try {
            java.util.regex.Matcher tm = DDL_CREATE_TABLE.matcher(sql);
            while (tm.find()) {
                String tableName = tm.group(1);
                String body = tm.group(2);
                String module = tableName.startsWith("blade_")
                        ? tableName.substring("blade_".length()) : tableName;
                DdlTable table = new DdlTable(tableName);
                java.util.regex.Matcher cm = DDL_COLUMN.matcher(body);
                while (cm.find()) {
                    String col = cm.group(1).toLowerCase(java.util.Locale.ROOT);
                    if (DDL_NON_COLUMN_KEYWORDS.contains(col)) continue;
                    table.columns.put(col, cm.group(2).toUpperCase(java.util.Locale.ROOT));
                }
                ddlTables.put(module, table);
            }
        } catch (Exception e) {
            log.warn("DDL 解析失败: {}", e.getMessage());
            issues.add(new ContractIssue("WARN", "DDL 解析异常: " + e.getMessage(), "ENTITY-DDL-PARSE"));
        }
    }

    private void checkEntityDdlAlignment(List<CompilationUnit> units,
                                           Map<String, DdlTable> ddlTables,
                                           List<ContractIssue> issues) {
        if (ddlTables.isEmpty()) return;
        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            if (!pkg.endsWith(".pojo.entity") && !pkg.contains(".pojo.entity.")) continue;
            String module = extractModuleFromPkg(pkg);
            DdlTable table = ddlTables.get(module);
            if (table == null) continue;
            String entityFile = cu.getStorage().map(s -> s.getFileName().toString()).orElse("?");
            boolean extendsTenant = cu.toString().contains("extends TenantEntity");

            // 提取 entity 业务字段: snake_case 列名 -> Java 类型
            // 跳过 static 字段(serialVersionUID 等常量),否则 camelToSnake 会产出
            // serial_version_u_i_d 这类"字段在 DDL 无对应列"的误报。
            Map<String, String> entityFields = new HashMap<>();
            for (FieldDeclaration fd : cu.findAll(FieldDeclaration.class)) {
                if (fd.isStatic()) continue;
                String fieldType = fd.getElementType().toString();
                for (var v : fd.getVariables()) {
                    entityFields.put(camelToSnake(v.getNameAsString()), fieldType);
                }
            }

            // DDL 业务列在 entity 缺失
            for (var entry : table.columns.entrySet()) {
                if (BASE_ENTITY_COLUMNS.contains(entry.getKey())) continue;
                if (!entityFields.containsKey(entry.getKey())) {
                    issues.add(new ContractIssue("ERROR",
                            entityFile + " 缺少 DDL 列 " + entry.getKey() + " (" + table.name + ")",
                            "ENTITY-DDL-COLUMN-MISSING"));
                }
            }
            // entity 业务字段在 DDL 缺列 + 类型不匹配
            for (var entry : entityFields.entrySet()) {
                String col = entry.getKey();
                if (BASE_ENTITY_COLUMNS.contains(col)) continue;
                String ddlType = table.columns.get(col);
                if (ddlType == null) {
                    issues.add(new ContractIssue("ERROR",
                            entityFile + " 字段 " + col + " 在 DDL 表 " + table.name + " 中无对应列",
                            "ENTITY-DDL-COLUMN-MISSING"));
                    continue;
                }
                String mapped = mapDdlTypeToJava(ddlType);
                if (mapped != null && !mapped.equalsIgnoreCase(entry.getValue())) {
                    issues.add(new ContractIssue("ERROR",
                            entityFile + " 字段 " + col + " 类型不匹配: DDL=" + ddlType
                                    + "(->" + mapped + "), Entity=" + entry.getValue(),
                            "ENTITY-DDL-TYPE-MISMATCH"));
                }
            }
            // 多租户: DDL 有 tenant_id 但 entity 非 TenantEntity
            if (table.columns.containsKey("tenant_id") && !extendsTenant) {
                issues.add(new ContractIssue("ERROR",
                        entityFile + " 对应表 " + table.name + " 含 tenant_id 列, 但 Entity 未 extends TenantEntity (多租户字段丢失)",
                        "ENTITY-DDL-TENANT"));
            }
        }
    }

    private void checkMapperXmlNamespace(List<GeneratedFile> mapperXmls,
                                           Map<String, ClassInfo> index,
                                           List<ContractIssue> issues) {
        java.util.regex.Pattern ns = java.util.regex.Pattern.compile("namespace\\s*=\\s*\"([^\"]+)\"");
        for (GeneratedFile xml : mapperXmls) {
            // 仅校验 Mapper XML，跳过 pom.xml 等其它 xml
            String path = xml.getFilePath();
            if (path == null || !path.endsWith("Mapper.xml")) continue;
            String content = xml.getContent();
            if (content == null) continue;
            java.util.regex.Matcher m = ns.matcher(content);
            if (!m.find()) {
                issues.add(new ContractIssue("WARN",
                        xml.getFilePath() + " 未声明 namespace", "CROSS-MAPPER-XML-NAMESPACE"));
                continue;
            }
            String namespace = m.group(1);
            boolean matched = index.values().stream()
                    .anyMatch(c -> c.name.endsWith("Mapper") && namespace.endsWith("." + c.name));
            if (!matched) {
                issues.add(new ContractIssue("ERROR",
                        xml.getFilePath() + " namespace=" + namespace + " 与生成集合中任何 Mapper 接口全限定名不匹配",
                        "CROSS-MAPPER-XML-NAMESPACE"));
            }
        }
    }

    private String extractModuleFromPkg(String pkg) {
        if (pkg == null) return null;
        String[] parts = pkg.split("\\.");
        // org.springblade.{module}.pojo.entity
        if (parts.length >= 5 && "org".equals(parts[0]) && "springblade".equals(parts[1])) {
            return parts[2];
        }
        return null;
    }

    private String camelToSnake(String camel) {
        if (camel == null || camel.isEmpty()) return camel;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String mapDdlTypeToJava(String ddlType) {
        if (ddlType == null) return null;
        String t = ddlType.toUpperCase(java.util.Locale.ROOT);
        if (t.startsWith("VARCHAR") || t.startsWith("CHAR") || t.startsWith("TEXT")) return "String";
        if (t.startsWith("BIGINT")) return "Long";
        if (t.startsWith("INT") || t.startsWith("TINYINT") || t.startsWith("SMALLINT") || t.startsWith("INTEGER")) return "Integer";
        if (t.startsWith("DATETIME") || t.startsWith("DATE") || t.startsWith("TIMESTAMP")) return "Date";
        if (t.startsWith("DECIMAL") || t.startsWith("NUMERIC")) return "BigDecimal";
        return null; // 未知类型不校验
    }

    /** DDL 表信息: 表名 + 列(snake_case 列名 -> 大写类型) */
    private static class DdlTable {
        final String name;
        final Map<String, String> columns = new HashMap<>();
        DdlTable(String name) { this.name = name; }
    }

    private static String simpleName(String qualifiedName) {
        int idx = qualifiedName.lastIndexOf('.');
        return idx >= 0 ? qualifiedName.substring(idx + 1) : qualifiedName;
    }

    /** 类信息索引 */
    private static class ClassInfo {
        final String name;
        final String pkg;
        final boolean isInterface;
        final Set<String> methods = new HashSet<>();

        ClassInfo(String name, String pkg, boolean isInterface) {
            this.name = name;
            this.pkg = pkg;
            this.isInterface = isInterface;
        }
    }

    /** 契约问题 */
    public static class ContractIssue {
        public final String severity; // ERROR / WARN
        public final String message;
        public final String rule;

        public ContractIssue(String severity, String message, String rule) {
            this.severity = severity;
            this.message = message;
            this.rule = rule;
        }

        public boolean isError() {
            return "ERROR".equalsIgnoreCase(severity);
        }

        @Override
        public String toString() {
            return "[" + severity + "] " + rule + ": " + message;
        }
    }
}
