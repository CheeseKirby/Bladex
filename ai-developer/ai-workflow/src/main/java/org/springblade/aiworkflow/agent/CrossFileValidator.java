package org.springblade.aiworkflow.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    /** JavaParser 线程安全可复用(L10), 避免每次 validate 都 new。 */
    private static final JavaParser PARSER = new JavaParser();

    /**
     * 校验一组生成的 Java 文件之间的契约一致性。
     *
     * @param files 同一子方案生成的全部 Java 文件 (GeneratedFile 列表, content 为源码)
     * @return 校验结果, issues 列表为空表示通过
     */
    public List<ContractIssue> validate(List<GeneratedFile> files) {
        return validate(files, false);
    }

    /**
     * 跨文件契约校验。
     * @param planWide true=plan 级(所有文件齐, 检 vo import 闭合); false=子方案级(跨子方案 VO 不全,
     *                 跳过 vo import 避免误报 -- 实测子方案 #4 Controller 引用 #2 的 EVO/QVO, 子方案级误报)
     */
    public List<ContractIssue> validate(List<GeneratedFile> files, boolean planWide) {
        List<ContractIssue> issues = new ArrayList<>();
        if (files == null || files.isEmpty()) return issues;

        JavaParser parser = PARSER;

        // 解析所有文件, 建立: 类名 -> (包路径, 方法签名集合, 是否接口) 索引
        Map<String, ClassInfo> index = new HashMap<>();
        List<CompilationUnit> units = new ArrayList<>();
        // CompilationUnit -> 源文件路径, 供跨文件修复定位"出错文件"用
        Map<CompilationUnit, String> cuToFilePath = new HashMap<>();
        // DDL 表: module -> DdlTable; Mapper XML 文件供 namespace 校验
        Map<String, DdlTable> ddlTables = new HashMap<>();
        List<GeneratedFile> mapperXmls = new ArrayList<>();
        for (GeneratedFile f : files) {
            if (f.getContent() == null) continue;
            String fpath = f.getFilePath() == null ? "" : f.getFilePath();
            if (fpath.endsWith(".sql")) {
                parseDdl(f.getContent(), f.getFilePath(), ddlTables, issues);
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
                cuToFilePath.put(cu, f.getFilePath());
                String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    ClassInfo info = new ClassInfo(cid.getNameAsString(), pkg, cid.isInterface());
                    info.filePath = f.getFilePath();
                    for (MethodDeclaration md : cid.getMethods()) {
                        String sig = md.getNameAsString() + "(" + md.getParameters().size() + ")";
                        info.methods.add(sig);
                        // 细签名: 参数类型去空格拼接, 供规则8做参数类型一致性比对
                        StringBuilder fullSig = new StringBuilder(md.getNameAsString()).append("(");
                        boolean first = true;
                        for (var p : md.getParameters()) {
                            if (!first) fullSig.append(",");
                            fullSig.append(p.getType().toString().replaceAll("\\s+", ""));
                            first = false;
                        }
                        fullSig.append(")");
                        info.methodFullSigs.add(fullSig.toString());
                        // 返回类型(去空格, 保留泛型), 供 B8 resultMap type 与方法返回元素类型比对
                        info.methodReturnTypes.put(sig,
                                md.getType().toString().replaceAll("\\s+", ""));
                    }
                    // 字段收集(跳过 static 如 serialVersionUID), 供 B1/B2/B3 VO↔Entity 字段一致性比对
                    for (FieldDeclaration fd : cid.getFields()) {
                        if (fd.isStatic()) continue;
                        String ftype = fd.getElementType().toString().replaceAll("\\s+", "");
                        for (VariableDeclarator v : fd.getVariables()) {
                            info.fields.put(v.getNameAsString(), ftype);
                        }
                    }
                    // extends 检测(区分 BaseEntity / TenantEntity), 供 B6 状态机识别 Entity
                    for (var et : cid.getExtendedTypes()) {
                        String n = et.getNameAsString();
                        if ("TenantEntity".equals(n)) info.extendsTenantEntity = true;
                        if ("BaseEntity".equals(n)) info.extendsBaseEntity = true;
                    }
                    // @TableName 提取(仅 Entity 有), 供识别 Entity + B6 状态机字段检测
                    for (var anno : cid.getAnnotations()) {
                        if (!"TableName".equals(anno.getNameAsString())) continue;
                        java.util.regex.Matcher tm = java.util.regex.Pattern
                                .compile("\"([^\"]+)\"").matcher(anno.toString());
                        if (tm.find()) info.tableName = tm.group(1);
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
                            issues.add(new ContractIssue(planWide ? "ERROR" : "WARN",
                                    cu.getPrimaryTypeName().orElse("?") + " import 的 " + simpleName
                                            + " 包路径不一致: import=" + importPkg + ", 实际=" + expectedPkg,
                                    "CROSS-IMPORT-PATH", cuToFilePath.get(cu), target.filePath));
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
        //     BladeX 平台实际有 SysCache/UserCache/RegionCache/ParamCache/DictCache 等, 但没有 DeptCache。
        //     检出对 system.cache.* 下 Cache 类的引用, 标记为 WARN 提醒人工确认。
        for (CompilationUnit cu : units) {
            String file = cu.getStorage().map(s -> s.getFileName().toString()).orElse("?");
            for (com.github.javaparser.ast.ImportDeclaration imp : cu.getImports()) {
                String imped = imp.getNameAsString();
                if (imped.startsWith("org.springblade.system.cache.")
                        && imped.endsWith("Cache")) {
                    String cacheClass = simpleName(imped);
                    // 已知合法的平台 Cache 白名单 (BladeX 平台实际存在)
                    if (!ConventionValidator.KNOWN_PLATFORM_CACHES.contains(cacheClass)) {
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
        checkEntityDdlAlignment(units, cuToFilePath, ddlTables, issues);

        // 6. Mapper XML namespace 必须等于集合中对应 Mapper 接口的全限定名
        checkMapperXmlNamespace(mapperXmls, index, issues);

        // 7. Controller → Service 调用一致性: Controller 注入的 I*Service 字段上调用的方法,
        //    方法名+参数个数必须在对应 Service 接口定义中存在(父类 BaseService 方法除外)。
        //    典型: Controller 调 xxxService.warningList()(0参), 但 Service 定义 warningList(String)(1参)
        //    → 编译失败。LLM 跨文件生成时 Controller/Service 分别生成, 易出现方法签名漂移。
        checkControllerServiceCalls(units, cuToFilePath, index, issues);

        // 8. ServiceImpl ↔ IService 声明一致性: ServiceImpl 的 @Override 自定义方法
        //    必须在对应 IService 接口中声明(父类 BaseService 方法除外)。
        //    典型: LLM 生成空接口 ICourseService 但 CourseServiceImpl 堆了 8 个 @Override 方法
        //    → 编译失败。Controller/Service/ServiceImpl 分文件生成, 接口与实现易漂移。
        checkServiceInterfaceImplConsistency(units, cuToFilePath, index, issues);

        // 9. 引用闭合: ServiceImpl import 的本模块 Mapper(*Mapper) / Service(I*Service)
        //    必须在生成集合内, 否则编译失败(类找不到)。
        //    典型: CourseServiceImpl import RecordMapper 但本次未生成 Record 实体配套文件。
        checkImportClosure(units, cuToFilePath, index, issues);

        // 9b. VO 类 import 闭合: 任何文件 import pojo.vo.{XxxVO/IVO/UVO/QVO/EVO} 必须在生成集合内。
        //     checkImportClosure 只检 mapper/service 包且跳过 Mapper 接口, 漏检 Controller/Wrapper/Mapper
        //     引用未生成的 IVO/UVO/QVO/EVO(实测 specialperiod 只生成 VO, 引用 IVO/UVO/QVO/EVO 漏检)。
        if (planWide) {
            checkVoImportClosure(units, cuToFilePath, index, issues);
        }

        // 10. VO/IVO/UVO 业务字段 ↔ Entity 字段一致性(B1/B2/B3, ERROR 可修复)
        checkVoEntityFieldConsistency(units, cuToFilePath, index, issues);

        // 11. Mapper XML resultMap type ↔ Mapper 方法返回元素类型(B8, ERROR)
        checkResultMapReturnType(mapperXmls, index, issues);

        // 12. Feign 接口无实现类(B5, WARN 不阻断)
        checkFeignImplExists(index, issues);

        // 13. /list 与 Mapper 自定义分页不一致(B7, WARN)
        checkListMapperPageConsistency(units, cuToFilePath, index, issues);

        // 14. Controller 绕过 Service 业务校验方法(B4, WARN)
        checkControllerCallsServiceValidation(units, cuToFilePath, index, issues);

        // 15. 状态机字段无推进机制(B6, WARN)
        checkStatusMachineDriver(units, cuToFilePath, index, files, issues);

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

    /** BaseEntity/TenantEntity 内置字段(camelCase), VO↔Entity 字段比对时排除 */
    private static final java.util.Set<String> BASE_ENTITY_FIELDS = java.util.Set.of(
            "id", "createUser", "createTime", "updateUser", "updateTime",
            "status", "isDeleted", "tenantId", "createDept");

    /** BladeX BaseService/BaseServiceImpl 标准方法, 调用合法但不在生成的 IService 定义内。
     *  三处校验(checkControllerServiceCalls/checkServiceInterfaceImplConsistency/checkControllerCallsServiceValidation)共用, 避免漂移。 */
    private static final Set<String> BASE_SERVICE_METHODS = Set.of(
            "save", "saveBatch", "saveOrUpdate", "saveOrUpdateBatch",
            "updateById", "update", "updateBatchById",
            "removeById", "removeByIds", "remove", "removeBatchByIds",
            "deleteLogic", "deleteLogicBatch",
            "getById", "getOne", "getMap", "getObj",
            "list", "listByIds", "page", "count",
            "lambdaQuery", "query");

    /** DDL 约束/索引关键字行，列解析时跳过 */
    private static final java.util.Set<String> DDL_NON_COLUMN_KEYWORDS = java.util.Set.of(
            "primary", "key", "unique", "constraint", "index", "foreign", "fulltext", "check");

    private void parseDdl(String sql, String ddlFilePath, Map<String, DdlTable> ddlTables, List<ContractIssue> issues) {
        if (sql == null || sql.isBlank()) return;
        try {
            java.util.regex.Matcher tm = DDL_CREATE_TABLE.matcher(sql);
            while (tm.find()) {
                String tableName = tm.group(1);
                String body = tm.group(2);
                DdlTable table = new DdlTable(tableName);
                table.filePath = ddlFilePath;
                table.content = sql;
                java.util.regex.Matcher cm = DDL_COLUMN.matcher(body);
                while (cm.find()) {
                    String col = cm.group(1).toLowerCase(java.util.Locale.ROOT);
                    if (DDL_NON_COLUMN_KEYWORDS.contains(col)) continue;
                    table.columns.put(col, cm.group(2).toUpperCase(java.util.Locale.ROOT));
                }
                // key 用完整表名(小写), 与 entity 的 @TableName 注解直接匹配,
                // 不再按表名去 blade_ 前缀分词(多词表名如 blade_training_course 会与包路径 module 不一致)
                ddlTables.put(tableName.toLowerCase(java.util.Locale.ROOT), table);
            }
        } catch (Exception e) {
            log.warn("DDL 解析失败: {}", e.getMessage());
            issues.add(new ContractIssue("WARN", "DDL 解析异常: " + e.getMessage(), "ENTITY-DDL-PARSE"));
        }
    }

    /** 从 entity 的 @TableName("xxx") 注解提取表名, 用于与 DDL 直接匹配。无注解返回 null。 */
    private String extractTableNameAnnotation(CompilationUnit cu) {
        for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            for (var anno : cid.getAnnotations()) {
                if (!"TableName".equals(anno.getNameAsString())) continue;
                String annoStr = anno.toString();
                // @TableName("blade_xxx") 或 @TableName(value = "blade_xxx")
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"([^\"]+)\"").matcher(annoStr);
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    private void checkEntityDdlAlignment(List<CompilationUnit> units,
                                           Map<CompilationUnit, String> cuToFilePath,
                                           Map<String, DdlTable> ddlTables,
                                           List<ContractIssue> issues) {
        if (ddlTables.isEmpty()) return;
        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            if (!pkg.endsWith(".pojo.entity") && !pkg.contains(".pojo.entity.")) continue;

            // 用 @TableName 注解直接匹配表名, 绕开"表名分词(module) vs 包路径 module"不一致
            // (blade_training_course 推 module=training_course, 但 entity 包是 training → 匹配失败, 校验静默跳过)。
            String tableName = extractTableNameAnnotation(cu);
            DdlTable table = tableName != null ? ddlTables.get(tableName.toLowerCase(java.util.Locale.ROOT)) : null;
            if (table == null) {
                // 兜底: 无 @TableName 注解时, 按包路径 module 推断常见命名 blade_{module}
                // (单段表名如 blade_employee + 包 org.springblade.employee 仍能匹配)
                String module = extractModuleFromPkg(pkg);
                if (module != null) {
                    table = ddlTables.get("blade_" + module);
                    if (table == null) table = ddlTables.get(module);
                }
            }
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
                            "ENTITY-DDL-COLUMN-MISSING",
                            cuToFilePath.get(cu),
                            table.filePath));
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
                            "ENTITY-DDL-COLUMN-MISSING",
                            cuToFilePath.get(cu),
                            table.filePath));
                    continue;
                }
                String mapped = mapDdlTypeToJava(ddlType);
                if (mapped != null && !mapped.equalsIgnoreCase(entry.getValue())) {
                    issues.add(new ContractIssue("ERROR",
                            entityFile + " 字段 " + col + " 类型不匹配: DDL=" + ddlType
                                    + "(->" + mapped + "), Entity=" + entry.getValue(),
                            "ENTITY-DDL-TYPE-MISMATCH",
                            cuToFilePath.get(cu),
                            table.filePath));
                }
            }
            // 多租户: DDL 有 tenant_id 但 entity 非 TenantEntity
            if (table.columns.containsKey("tenant_id") && !extendsTenant) {
                issues.add(new ContractIssue("ERROR",
                        entityFile + " 对应表 " + table.name + " 含 tenant_id 列, 但 Entity 未 extends TenantEntity (多租户字段丢失)",
                        "ENTITY-DDL-TENANT",
                        cuToFilePath.get(cu),
                        table.filePath));
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

    /**
     * 检查 Controller 对其注入的 I*Service 字段的调用是否与 Service 接口定义一致。
     *
     * <p>仅校验"方法名 + 参数个数"。LLM 分别生成 Controller 和 Service 时,
     * 常出现方法名漂移(Controller 调 saveStock, Service 定义 saveStock 但 Controller 写成 save)
     * 或参数个数不符(Controller 调 warningList() 但 Service 定义 warningList(String))。
     * 这类问题导致编译失败, 但 ConventionValidator(单文件)无法发现, 需跨文件校验。
     *
     * <p>防误报: Service 接口 extends BaseService, 父类方法(save/updateById/getOne/deleteLogic/list/page 等)
     * 不在生成的 IService 定义里但调用合法, 用 BASE_SERVICE_METHODS 白名单跳过。
     * 类型不匹配(BigDecimal vs Integer)本规则不检测(参数个数相同), 属已知边界。
     */
    private void checkControllerServiceCalls(List<CompilationUnit> units,
                                             Map<CompilationUnit, String> cuToFilePath,
                                             Map<String, ClassInfo> index,
                                             List<ContractIssue> issues) {
        // BladeX BaseService / MyBatis-Plus IService 继承的标准方法, 调用合法但不在生成 IService 定义内
        // 标准方法白名单见静态常量 BASE_SERVICE_METHODS

        for (CompilationUnit cu : units) {
            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (cid.isInterface()) continue;
                String controllerName = cid.getNameAsString();
                if (!controllerName.endsWith("Controller")) continue;

                // 字段名 -> Service 接口简单名 (如 screwStockService -> IScrewStockService)
                Map<String, String> serviceFields = new HashMap<>();
                for (FieldDeclaration fd : cid.getFields()) {
                    String fieldType = fd.getElementType().toString();
                    if (fieldType.startsWith("I") && fieldType.endsWith("Service")) {
                        for (VariableDeclarator v : fd.getVariables()) {
                            serviceFields.put(v.getNameAsString(), fieldType);
                        }
                    }
                }
                if (serviceFields.isEmpty()) continue;

                // 遍历方法调用: 形如 xxxService.method(args)
                for (MethodCallExpr call : cid.findAll(MethodCallExpr.class)) {
                    if (call.getScope().isEmpty()) continue;
                    if (!(call.getScope().get() instanceof NameExpr scope)) continue;
                    String receiverName = scope.getNameAsString();
                    String serviceType = serviceFields.get(receiverName);
                    if (serviceType == null) continue; // 不是 service 字段的调用, 跳过

                    ClassInfo serviceInfo = index.get(serviceType);
                    if (serviceInfo == null) continue; // Service 接口不在生成集合(理论不会, 但兜底)

                    String methodName = call.getNameAsString();
                    int argCount = call.getArguments().size();
                    // 父类标准方法跳过, 不在生成的 IService 定义里但合法
                    if (BASE_SERVICE_METHODS.contains(methodName)) continue;

                    String signature = methodName + "(" + argCount + ")";
                    if (!serviceInfo.methods.contains(signature)) {
                        // 携带文件定位, 供 BladeXCodeAgent 跨文件修复时定位:
                        //   sourceFilePath = 出错的 Controller(需重生成)
                        //   contractFilePath = Service 接口(契约源头, 作为修复 context 注入)
                        issues.add(new ContractIssue("ERROR",
                                controllerName + " 调用 " + receiverName + "." + methodName
                                        + "(" + argCount + " 个参数), 但 " + serviceType
                                        + " 未定义该签名(定义的方法: "
                                        + serviceInfo.methods.stream().sorted().limit(8).reduce((a, b) -> a + ", " + b).orElse("(无)")
                                        + (serviceInfo.methods.size() > 8 ? ", ..." : "") + ")",
                                "CROSS-CONTROLLER-SERVICE-MISMATCH",
                                cuToFilePath.get(cu),
                                serviceInfo.filePath));
                    }
                }
            }
        }
    }

    /**
     * 检查 ServiceImpl 的 @Override 自定义方法是否在对应 IService 接口中声明。
     *
     * <p>LLM 分文件生成时, 常出现"IService 接口体为空(只 extends BaseService), 但 ServiceImpl 堆了一堆
     * 自定义 @Override 方法" → 编译失败(接口未声明该方法, @Override 找不到父方法)。
     * 本规则检出这类漂移: ServiceImpl 的 public 方法(除父类 BaseService 白名单)必须存在于 IService 定义。
     *
     * <p>定位: sourceFilePath = ServiceImpl(需重生成), contractFilePath = IService 接口(契约源头)。
     * 防误报: 实现类可能调父类方法(已在 BASE_SERVICE_METHODS 白名单内), 跳过。
     */
    private void checkServiceInterfaceImplConsistency(List<CompilationUnit> units,
                                                        Map<CompilationUnit, String> cuToFilePath,
                                                        Map<String, ClassInfo> index,
                                                        List<ContractIssue> issues) {
        // BladeX BaseService/BaseServiceImpl 标准方法, 实现类可合法调用但不在 IService 定义
        // 标准方法白名单见静态常量 BASE_SERVICE_METHODS

        for (CompilationUnit cu : units) {
            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (cid.isInterface()) continue;
                String implName = cid.getNameAsString();
                if (!implName.endsWith("ServiceImpl")) continue;

                // 找对应的 IService 接口名: XxxServiceImpl → IXxxService
                String serviceIfaceName = "I" + implName.substring(0, implName.length() - "ServiceImpl".length()) + "Service";
                ClassInfo serviceInfo = index.get(serviceIfaceName);
                if (serviceInfo == null) continue; // 接口不在生成集合(理论不会, 但兜底)

                // 遍历 ServiceImpl 的 public 方法, 检查是否在接口声明(方法名+参数个数)
                // 同时收集实现类的方法签名, 供循环后反向检查(接口声明了但实现类没实现)
                Set<String> implMethods = new HashSet<>();
                for (MethodDeclaration md : cid.getMethods()) {
                    // 只看 public 且非 static 的实例方法
                    if (!md.isPublic() || md.isStatic()) continue;
                    String methodName = md.getNameAsString();
                    int paramCount = md.getParameters().size();
                    implMethods.add(methodName + "(" + paramCount + ")");
                    // 父类标准方法跳过(合法但不在 IService 定义)
                    if (BASE_SERVICE_METHODS.contains(methodName)) continue;

                    String signature = methodName + "(" + paramCount + ")";
                    if (!serviceInfo.methods.contains(signature)) {
                        // 第一层: 方法名 + 参数个数都对不上 → 接口完全没声明
                        issues.add(new ContractIssue("ERROR",
                                implName + " 声明了方法 " + signature + ", 但接口 " + serviceIfaceName
                                        + " 未声明该签名(接口定义的方法: "
                                        + serviceInfo.methods.stream().sorted().limit(8).reduce((a, b) -> a + ", " + b).orElse("(无)")
                                        + (serviceInfo.methods.size() > 8 ? ", ..." : "") + ")。"
                                        + "需在接口中补充声明, 或从实现类移除该方法。",
                                "CROSS-SERVICE-IMPL-IFACE-MISMATCH",
                                cuToFilePath.get(cu),
                                serviceInfo.filePath));
                        continue;
                    }
                    // 第二层: 方法名+参数个数对上, 但参数类型可能漂移
                    // (典型: 接口 selectScrewPage(Query, ScrewQVO) vs 实现 selectScrewPage(IPage<Screw>, ScrewQVO))
                    // 用细签名严格比对参数类型字符串(去空格), 完全一致才放过
                    StringBuilder fullSig = new StringBuilder(methodName).append("(");
                    boolean first = true;
                    for (var p : md.getParameters()) {
                        if (!first) fullSig.append(",");
                        fullSig.append(p.getType().toString().replaceAll("\\s+", ""));
                        first = false;
                    }
                    fullSig.append(")");
                    String implFullSig = fullSig.toString();
                    if (!serviceInfo.methodFullSigs.contains(implFullSig)) {
                        // 找到方法名+个数相同但类型不同的接口签名, 给 LLM 一个明确对比
                        String ifaceMatch = serviceInfo.methodFullSigs.stream()
                                .filter(s -> s.startsWith(methodName + "("))
                                .filter(s -> s.chars().filter(c -> c == ',').count()
                                        == implFullSig.chars().filter(c -> c == ',').count())
                                .findFirst().orElse("(未找到)");
                        issues.add(new ContractIssue("ERROR",
                                implName + " 方法 " + implFullSig + " 与接口 " + serviceIfaceName
                                        + " 的声明 " + ifaceMatch + " 参数类型不一致 → @Override 编译失败。"
                                        + "需把实现类签名改为与接口一致。",
                                "CROSS-SERVICE-IMPL-IFACE-MISMATCH",
                                cuToFilePath.get(cu),
                                serviceInfo.filePath));
                    }
                }

                // 第三层(反向): 接口声明的每个自定义方法, ServiceImpl 必须有对应实现, 否则编译失败
                // (典型: IService 声明了 saveProduct/updateProduct 等, 但 ServiceImpl 是空类 → implements 编译失败)
                // 修复方向: 以接口为源头重生成 ServiceImpl 补齐实现
                for (String ifaceMethod : serviceInfo.methods) {
                    if (implMethods.contains(ifaceMethod)) continue;
                    // 解析方法名, 父类标准方法接口不会声明(由 BaseService 提供), 跳过
                    String mname = ifaceMethod.substring(0, ifaceMethod.indexOf('('));
                    if (BASE_SERVICE_METHODS.contains(mname)) continue;
                    issues.add(new ContractIssue("ERROR",
                            serviceIfaceName + " 声明了方法 " + ifaceMethod + ", 但 " + implName
                                    + " 未实现该方法 → implements 编译失败。"
                                    + "需在实现类中补充该方法实现(@Override, 签名与接口一致), 或从接口移除声明。",
                            "CROSS-SERVICE-IMPL-IFACE-MISMATCH",
                            cuToFilePath.get(cu),
                            serviceInfo.filePath));
                }
            }
        }
    }

    /**
     * 检查引用闭合: ServiceImpl import 的本模块 Mapper(*Mapper) / Service(I*Service)
     * 必须在生成集合内, 否则编译失败(类找不到)。
     *
     * <p>典型: CourseServiceImpl import RecordMapper 但本次未生成 Record 实体配套文件。
     * 本规则只检"指向 org.springblade.{本模块}.mapper / .service 的 import",
     * 避免误报平台类(BladeMapper/BaseService 等不在生成集合是正常的)。
     */
    /**
     * 9b. VO 类 import 闭合: 任何文件 import org.springblade.{module}.pojo.vo.{XxxVO/IVO/UVO/QVO/EVO},
     * 该 VO 类必须在生成集合内, 否则编译失败。
     *
     * <p>checkImportClosure 只检 mapper/service 包且跳过 Mapper 接口, 漏检 Controller/Wrapper/Mapper
     * 引用未生成的 IVO/UVO/QVO/EVO。本规则补这个漏洞(实测: specialperiod 只生成 VO,
     * Controller/Wrapper/Mapper 引用 IVO/UVO/QVO/EVO 漏检 -> 编译失败但 plan COMPLETED)。
     *
     * <p>不误报: VO 类自身不 import vo 包(VO extends Entity), 故只在 Controller/Wrapper/Mapper 等
     * 引用 vo 类时触发; 平台类(org.springblade.core.*)不在检范围。
     */
    private void checkVoImportClosure(List<CompilationUnit> units,
                                       Map<CompilationUnit, String> cuToFilePath,
                                       Map<String, ClassInfo> index,
                                       List<ContractIssue> issues) {
        Set<String> generatedClassNames = index.keySet();
        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            String module = extractModuleFromPkg(pkg);
            if (module == null) continue;
            String voPrefix = "org.springblade." + module + ".pojo.vo.";
            String file = cu.getStorage().map(s -> s.getFileName().toString()).orElse("?");
            for (com.github.javaparser.ast.ImportDeclaration imp : cu.getImports()) {
                String imped = imp.getNameAsString();
                if (!imped.startsWith(voPrefix)) continue;
                String simpleName = simpleName(imped);
                if (!generatedClassNames.contains(simpleName)) {
                    issues.add(new ContractIssue("ERROR",
                            file + " import " + imped + " (pojo.vo 包) 但该 VO 类未在生成集合 -> 编译失败",
                            "CROSS-IMPORT-CLOSURE-MISSING",
                            cuToFilePath.get(cu), null));
                }
            }
        }
    }

    private void checkImportClosure(List<CompilationUnit> units,
                                     Map<CompilationUnit, String> cuToFilePath,
                                     Map<String, ClassInfo> index,
                                     List<ContractIssue> issues) {
        Set<String> generatedClassNames = index.keySet();
        for (CompilationUnit cu : units) {
            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (cid.isInterface()) continue;
                String implName = cid.getNameAsString();
                // 仅校验 ServiceImpl / Controller / Wrapper 等可能注入本模块类的实现类
                if (!implName.endsWith("ServiceImpl") && !implName.endsWith("Controller")
                        && !implName.endsWith("Wrapper")) continue;

                String currentPkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                // 提取本模块名: org.springblade.{module}.xxx
                String module = extractModuleFromPkg(currentPkg);
                if (module == null) continue;

                for (com.github.javaparser.ast.ImportDeclaration imp : cu.getImports()) {
                    String imped = imp.getNameAsString();
                    // 只关注本模块 mapper / service 包下的 import
                    String pkgPrefix1 = "org.springblade." + module + ".mapper.";
                    String pkgPrefix2 = "org.springblade." + module + ".service.";
                    if (!imped.startsWith(pkgPrefix1) && !imped.startsWith(pkgPrefix2)) continue;

                    String simpleName = simpleName(imped);
                    // 平台/父类 Mapper 跳过(BladeMapper 等不在生成集合是正常的)
                    if ("BladeMapper".equals(simpleName) || "BaseMapper".equals(simpleName)) continue;
                    // Service 接口自身(IXxxService)不检, 由规则8处理
                    if (simpleName.startsWith("I") && simpleName.endsWith("Service")) continue;

                    if (!generatedClassNames.contains(simpleName)) {
                        issues.add(new ContractIssue("ERROR",
                                implName + " import " + imped + ", 但该类未在本次生成集合中 → 编译失败(类找不到)。"
                                        + "若依赖它, 需补充生成该类; 若不需要, 移除 import 与相关引用。",
                                "CROSS-IMPORT-CLOSURE-MISSING",
                                cuToFilePath.get(cu),
                                null));
                    }
                }
            }
        }
    }

    // ─── B1/B2/B3: VO/IVO/UVO 业务字段 ↔ Entity 字段一致性 ───

    /**
     * B1/B2/B3: VO/IVO/UVO 业务字段必须与 Entity 同名同类型。
     *
     * <p>Wrapper 用 BeanUtil.copy 按字段名复制, VO/IVO/UVO 若与 Entity 字段名/类型不一致会丢字段,
     * 导致新增 NOT NULL 报错、列表列空、编辑清空等整条 CRUD 数据流断裂。
     * Entity 是契约源头(先于 VO 生成或同属 API 模块), 故以 Entity 为对端: VO 业务字段必须 ⊆ Entity 字段,
     * 同名字段类型必须一致。QVO 只含查询字段(可有范围字段)不强制一致, 跳过。
     *
     * <p>定位: sourceFilePath = VO 文件(需重生成), contractFilePath = Entity 文件(契约源头),
     * 供 plan 级修复循环以 Entity 为源头重生成 VO。
     */
    private void checkVoEntityFieldConsistency(List<CompilationUnit> units,
                                                Map<CompilationUnit, String> cuToFilePath,
                                                Map<String, ClassInfo> index,
                                                List<ContractIssue> issues) {
        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            if (!pkg.endsWith(".pojo.vo") && !pkg.contains(".pojo.vo.")) continue;
            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String voName = cid.getNameAsString();
                String suffix = voSuffix(voName);
                if (suffix == null) continue; // QVO 或非 VO 类, 跳过
                String entityName = voName.substring(0, voName.length() - suffix.length());
                ClassInfo entity = index.get(entityName);
                ClassInfo vo = index.get(voName);
                if (entity == null || vo == null) continue; // Entity 不在生成集合, 无法比对
                String voFile = cuToFilePath.get(cu);
                for (var entry : vo.fields.entrySet()) {
                    String fname = entry.getKey();
                    String ftype = entry.getValue();
                    if (BASE_ENTITY_FIELDS.contains(fname)) continue;
                    String entityType = entity.fields.get(fname);
                    if (entityType == null) {
                        // B1/B3: VO 字段在 Entity 不存在(改名或凭空)。展示衍生字段(后缀 Name 等)放过。
                        if (isDisplayDerivedField(fname)) continue;
                        issues.add(new ContractIssue("ERROR",
                                voName + " 字段 " + fname + " 在 Entity " + entityName
                                        + " 中不存在(改名或凭空新增)。BeanUtil.copy 无法映射, 该字段恒为 null。"
                                        + "VO/IVO/UVO 业务字段必须与 Entity 同名; 展示衍生字段用 xxxName 后缀; 如需新表列须同步 DDL 与 Entity。",
                                "VO-ENTITY-FIELD-MISMATCH",
                                voFile, entity.filePath));
                    } else if (!entityType.equalsIgnoreCase(ftype)) {
                        // B2: 同名字段类型不一致, BeanUtil.copy 跨类型拷不动
                        issues.add(new ContractIssue("ERROR",
                                voName + " 字段 " + fname + " 类型 " + ftype + " 与 Entity " + entityName
                                        + " 的 " + entityType + " 不一致。BeanUtil.copy 跨类型无法拷贝, 字段丢失。"
                                        + "IVO/UVO/VO 字段类型必须与 Entity 对应字段一致。",
                                "VO-ENTITY-FIELD-TYPE-MISMATCH",
                                voFile, entity.filePath));
                    }
                }
            }
        }
    }

    /** VO 类名后缀识别: 返回 IVO/UVO/EVO/VO 之一; QVO 返回 null(查询对象不强制字段一致) */
    private String voSuffix(String name) {
        if (name == null || name.length() <= 3) return null;
        for (String s : new String[]{"IVO", "UVO", "EVO"}) {
            if (name.endsWith(s)) return s;
        }
        if (name.endsWith("VO") && !name.endsWith("QVO")) return "VO";
        return null;
    }

    /** 展示衍生字段判定: 字典翻译/显示名称类字段(xxxName), 允许 VO 额外持有 */
    private boolean isDisplayDerivedField(String fname) {
        // 展示衍生字段: 字典翻译/显示名称/描述类(xxxName/xxxDesc/xxxText), 允许 VO 额外持有
        // 排除单词 name/desc/text(长度 > 4)
        return (fname.endsWith("Name") || fname.endsWith("Desc") || fname.endsWith("Text")) && fname.length() > 4;
    }

    // ─── B8: Mapper XML resultMap type ↔ 方法返回类型 ───

    /**
     * B8: Mapper XML 的 resultMap type 必须与对应 Mapper 方法返回元素类型一致。
     *
     * <p>典型: selectActivePeriods 声明返回 List&lt;SpecialPeriodVO&gt;, 但 resultMap type 指向 Entity,
     * 且 property 用了 VO 字段名 -> MyBatis 映射错乱。
     */
    private void checkResultMapReturnType(List<GeneratedFile> mapperXmls,
                                           Map<String, ClassInfo> index,
                                           List<ContractIssue> issues) {
        for (GeneratedFile xml : mapperXmls) {
            String path = xml.getFilePath();
            if (path == null || !path.endsWith("Mapper.xml")) continue;
            String content = xml.getContent();
            if (content == null) continue;
            java.util.regex.Matcher nsm = java.util.regex.Pattern
                    .compile("namespace\\s*=\\s*\"([^\"]+)\"").matcher(content);
            if (!nsm.find()) continue;
            String ns = nsm.group(1);
            String mapperName = ns.substring(ns.lastIndexOf('.') + 1);
            ClassInfo mapper = index.get(mapperName);
            if (mapper == null) continue;

            // 建 resultMap id -> type(简单名) 索引
            Map<String, String> resultMapTypes = new HashMap<>();
            java.util.regex.Matcher rmm = java.util.regex.Pattern
                    .compile("(?s)<resultMap\\s+[^>]*>").matcher(content);
            while (rmm.find()) {
                String tag = rmm.group(0);
                java.util.regex.Matcher idm = java.util.regex.Pattern
                        .compile("id\\s*=\\s*\"([^\"]+)\"").matcher(tag);
                java.util.regex.Matcher tpm = java.util.regex.Pattern
                        .compile("type\\s*=\\s*\"([^\"]+)\"").matcher(tag);
                if (idm.find() && tpm.find()) {
                    resultMapTypes.put(idm.group(1), simpleName(tpm.group(1)));
                }
            }

            // 每个带 resultMap 的 <select id="X">, 比对 resultMap type 与方法返回元素类型
            java.util.regex.Matcher sm = java.util.regex.Pattern
                    .compile("(?s)<select\\s+[^>]*>").matcher(content);
            while (sm.find()) {
                String tag = sm.group(0);
                java.util.regex.Matcher idm = java.util.regex.Pattern
                        .compile("id\\s*=\\s*\"([^\"]+)\"").matcher(tag);
                if (!idm.find()) continue;
                String methodId = idm.group(1);
                java.util.regex.Matcher rm = java.util.regex.Pattern
                        .compile("resultMap\\s*=\\s*\"([^\"]+)\"").matcher(tag);
                if (!rm.find()) continue;
                String rmType = resultMapTypes.get(rm.group(1));
                if (rmType == null) continue;
                String retElement = mapperReturnElement(mapper, methodId);
                if (retElement == null) continue;
                if (!rmType.equals(retElement)) {
                    issues.add(new ContractIssue("ERROR",
                            "Mapper.xml <select id=\"" + methodId + "\"> resultMap type=" + rmType
                                    + " 与 Mapper 方法 " + methodId + " 返回元素类型 " + retElement + " 不一致。"
                                    + "需统一: 要么 resultMap type 与方法返回都指向 Entity, 要么都指向 VO(且 property 与 VO 字段同名)。",
                            "MAPPER-RESULTMAP-TYPE-MISMATCH",
                            path, mapper.filePath));
                }
            }
        }
    }

    /** 取 Mapper 方法返回类型的元素类型: List<X> / IPage<X> -> X, X -> X; 找不到返回 null */
    private String mapperReturnElement(ClassInfo mapper, String methodId) {
        for (var entry : mapper.methodReturnTypes.entrySet()) {
            String sig = entry.getKey(); // methodName(N)
            String mname = sig.substring(0, sig.indexOf('('));
            if (!mname.equals(methodId)) continue;
            String retType = entry.getValue();
            int lt = retType.indexOf('<');
            int gt = retType.lastIndexOf('>');
            if (lt >= 0 && gt > lt) {
                String inner = retType.substring(lt + 1, gt);
                int lt2 = inner.indexOf('<'); // IPage<List<X>> 之类取最外层
                return lt2 >= 0 ? inner.substring(0, lt2) : inner;
            }
            return retType;
        }
        return null;
    }

    // ─── B5: Feign 接口实现类缺失 ───

    /**
     * B5: Feign 接口(IXxxClient)必须有实现类(XxxClient, 去 I 前缀), 否则其他微服务通过 Feign 调用会 404。
     *
     * <p>当前生成流程只生成 Feign 接口, 实现类需补; 此处 WARN 提醒, 不阻断(改生成流程是独立改动)。
     */
    private void checkFeignImplExists(Map<String, ClassInfo> index, List<ContractIssue> issues) {
        for (ClassInfo c : index.values()) {
            if (!c.isInterface) continue;
            if (!c.name.startsWith("I") || !c.name.endsWith("Client")) continue;
            if (c.name.length() <= 6) continue; // 过短跳过
            String implName = c.name.substring(1); // IXxxClient -> XxxClient
            if (!index.containsKey(implName)) {
                issues.add(new ContractIssue("WARN",
                        c.name + " 定义了 Feign 接口但生成集合中无实现类 " + implName
                                + " -> Feign 端点无 Controller 实现, 其他微服务调用会 404。"
                                + "需在服务模块补 " + implName + " implements " + c.name
                                + "(@Hidden @RestController, 每个接口方法 @Override + 调 Service)。",
                        "FEIGN-IMPL-MISSING",
                        c.filePath, null));
            }
        }
    }

    // ─── B7: /list 与 Mapper 自定义分页不一致 ───

    /**
     * B7: Mapper 有自定义分页方法(selectXxxPage, 参数含 IPage)但 Controller /list 没调用它 -> 死代码,
     * QVO 区间字段被忽略。
     */
    private void checkListMapperPageConsistency(List<CompilationUnit> units,
                                                 Map<CompilationUnit, String> cuToFilePath,
                                                 Map<String, ClassInfo> index,
                                                 List<ContractIssue> issues) {
        // 实体前缀 -> 自定义分页方法名(用类名前缀匹配, 避免同模块多实体误报/漏报)
        Map<String, String> prefixPageMethod = new HashMap<>();
        for (ClassInfo mapper : index.values()) {
            if (!mapper.name.endsWith("Mapper") || mapper.name.length() <= "Mapper".length()) continue;
            String prefix = mapper.name.substring(0, mapper.name.length() - "Mapper".length());
            for (String sig : mapper.methods) {
                String mname = sig.substring(0, sig.indexOf('('));
                if (!mname.toLowerCase().contains("page")) continue;
                boolean hasIPage = mapper.methodFullSigs.stream()
                        .anyMatch(s -> s.startsWith(mname + "(") && s.contains("IPage"));
                if (hasIPage) {
                    prefixPageMethod.put(prefix, mname);
                    break;
                }
            }
        }
        if (prefixPageMethod.isEmpty()) return;
        for (CompilationUnit cu : units) {
            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (cid.isInterface() || !cid.getNameAsString().endsWith("Controller")) continue;
                String ctrlName = cid.getNameAsString();
                if (ctrlName.length() <= "Controller".length()) continue;
                String prefix = ctrlName.substring(0, ctrlName.length() - "Controller".length());
                String pageMethod = prefixPageMethod.get(prefix);
                if (pageMethod == null) continue;
                String code = cid.toString();
                if (code.contains("\"/list\"") && !code.contains(pageMethod + "(")) {
                    issues.add(new ContractIssue("WARN",
                            ctrlName + " /list 端点用 Condition.getQueryWrapper, 未调用 Mapper 自定义分页方法 "
                                    + pageMethod + ", QVO 区间字段会被忽略(死代码)。"
                                    + "要么 /list 改用 " + pageMethod + "(IPage, QVO), 要么移除 Mapper 自定义分页方法与 QVO 区间字段。",
                            "LIST-MAPPER-PAGE-INCONSISTENT",
                            cuToFilePath.get(cu), null));
                }
            }
        }
    }

    // ─── B4: Controller 绕过 Service 业务校验方法 ───

    /**
     * B4: IService 定义了含业务校验语义的自定义方法(submit/check/validate), 但 Controller /save /update
     * 调基类 save/updateById 而非该方法 -> 校验成死代码。
     */
    private void checkControllerCallsServiceValidation(List<CompilationUnit> units,
                                                        Map<CompilationUnit, String> cuToFilePath,
                                                        Map<String, ClassInfo> index,
                                                        List<ContractIssue> issues) {
        java.util.Set<String> VALIDATION_HINTS = java.util.Set.of("submit", "check", "validate");
        // 标准方法白名单见静态常量 BASE_SERVICE_METHODS
        // 实体前缀 -> IService 中的业务校验方法名(用类名前缀匹配, 避免同模块多实体误报)
        Map<String, String> prefixValidationMethod = new HashMap<>();
        for (ClassInfo svc : index.values()) {
            if (!svc.isInterface || !svc.name.startsWith("I") || !svc.name.endsWith("Service")) continue;
            if (svc.name.length() <= "IService".length()) continue;
            String prefix = svc.name.substring(1, svc.name.length() - "Service".length());
            for (String sig : svc.methods) {
                String mname = sig.substring(0, sig.indexOf('('));
                if (BASE_SERVICE_METHODS.contains(mname)) continue;
                String lower = mname.toLowerCase();
                if (VALIDATION_HINTS.stream().anyMatch(lower::contains)) {
                    prefixValidationMethod.put(prefix, mname);
                    break;
                }
            }
        }
        if (prefixValidationMethod.isEmpty()) return;
        for (CompilationUnit cu : units) {
            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (cid.isInterface() || !cid.getNameAsString().endsWith("Controller")) continue;
                String ctrlName = cid.getNameAsString();
                if (ctrlName.length() <= "Controller".length()) continue;
                String prefix = ctrlName.substring(0, ctrlName.length() - "Controller".length());
                String validationMethod = prefixValidationMethod.get(prefix);
                if (validationMethod == null) continue;
                String code = cid.toString();
                if ((code.contains("\"/save\"") || code.contains("\"/update\""))
                        && !code.contains(validationMethod + "(")) {
                    issues.add(new ContractIssue("WARN",
                            ctrlName + " /save 或 /update 端点未调用 I" + prefix + "Service." + validationMethod
                                    + "(含业务校验), 直接用基类 save/updateById 会使校验成死代码。"
                                    + "应改调 " + validationMethod + "。",
                            "CONTROLLER-SKIP-SERVICE-VALIDATION",
                            cuToFilePath.get(cu), null));
                }
            }
        }
    }

    // ─── B6: 状态机字段无推进机制 ───

    /**
     * B6: Entity 含状态机字段(xxxStatus, 非 Base 的 status)但生成集合无定时任务(timed 包/@Scheduled)
     * 推进状态 -> 依赖该状态的查询可能永远返回空。仅 WARN, 不阻断。
     */
    private void checkStatusMachineDriver(List<CompilationUnit> units,
                                           Map<CompilationUnit, String> cuToFilePath,
                                           Map<String, ClassInfo> index,
                                           List<GeneratedFile> allFiles,
                                           List<ContractIssue> issues) {
        boolean hasScheduler = allFiles.stream().anyMatch(f -> {
            String p = f.getFilePath() == null ? "" : f.getFilePath();
            String c = f.getContent() == null ? "" : f.getContent();
            return p.contains("timed") || c.contains("@Scheduled");
        });
        if (hasScheduler) return;
        for (ClassInfo ent : index.values()) {
            if (ent.tableName == null) continue; // 仅 Entity
            boolean hasStateMachine = ent.fields.keySet().stream()
                    .anyMatch(f -> f.endsWith("Status") && !f.equals("status"));
            if (!hasStateMachine) continue;
            issues.add(new ContractIssue("WARN",
                    ent.name + " 含状态机字段(xxxStatus), 但生成集合无定时任务(timed 包 / @Scheduled)推进状态。"
                            + "依赖该状态值的查询(如 selectActiveXxx)可能永远返回空。"
                            + "需补定时任务推进状态(1未开始->2进行中->3已结束), 或在审查阶段显式标注状态推进方式。",
                    "STATUS-MACHINE-NO-DRIVER",
                    ent.filePath, null));
        }
    }

    private String extractModuleFromPkg(String pkg) {
        if (pkg == null) return null;
        String[] parts = pkg.split("\\.");
        // org.springblade.{module}[.{layer}...] - 至少 4 段(org/springblade/module/layer),
        // module 在 parts[2]。controller/mapper/service 等是 4 段, pojo.entity 是 5 段, 都取 parts[2]。
        if (parts.length >= 4 && "org".equals(parts[0]) && "springblade".equals(parts[1])) {
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

    /** DDL 表信息: 表名 + 列(snake_case 列名 -> 大写类型) + 源文件定位(供 entity↔DDL 自动修复) */
    private static class DdlTable {
        final String name;
        final Map<String, String> columns = new HashMap<>();
        /** DDL 源文件路径(相对 target-project-root),修复 Entity 时作为契约对端 context 注入 */
        String filePath;
        /** DDL 源文件完整内容,修复 Entity 时注入 LLM 参考 */
        String content;
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
        /** 该类所在生成文件的路径, 供跨文件修复定位契约对端文件用 */
        String filePath;
        /** 粗签名: methodName(N), 用于规则2/7(只关心方法名+参数个数) */
        final Set<String> methods = new HashSet<>();
        /** 细签名: methodName(Type1,Type2,...), 用于规则8参数类型对比(去空格、保留泛型) */
        final Set<String> methodFullSigs = new HashSet<>();
        /** 方法返回类型: methodName(N) -> 返回类型字符串(去空格, 保留泛型), 供 B8 resultMap 比对 */
        final Map<String, String> methodReturnTypes = new HashMap<>();
        /** 业务字段: fieldName -> 类型(去空格), 跳过 static; 供 B1/B2/B3 VO↔Entity 比对 */
        final Map<String, String> fields = new LinkedHashMap<>();
        /** @TableName 注解值, 仅 Entity 有; 用于识别 Entity 与 B6 状态机字段检测 */
        String tableName;
        /** extends BaseEntity / TenantEntity 标记 */
        boolean extendsBaseEntity;
        boolean extendsTenantEntity;

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
        /** 出问题的文件路径(需修复的目标), 仅 CROSS-CONTROLLER-SERVICE-MISMATCH 等可定位规则填充, 其余为 null */
        public final String sourceFilePath;
        /** 契约对端文件路径(作为修复 context), 同上 */
        public final String contractFilePath;

        public ContractIssue(String severity, String message, String rule) {
            this(severity, message, rule, null, null);
        }

        public ContractIssue(String severity, String message, String rule,
                             String sourceFilePath, String contractFilePath) {
            this.severity = severity;
            this.message = message;
            this.rule = rule;
            this.sourceFilePath = sourceFilePath;
            this.contractFilePath = contractFilePath;
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
