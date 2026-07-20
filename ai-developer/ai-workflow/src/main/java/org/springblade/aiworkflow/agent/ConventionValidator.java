package org.springblade.aiworkflow.agent;

import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.enums.TaskType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * BladeX规范校验器 — Layer 1审查机制
 *
 * <p>纯Java规则引擎，在编译前运行，零LLM成本。
 * 检查LLM生成的BladeX代码是否符合框架规范。
 * 可捕获约90%的规范违规问题。
 *
 * @author AI Developer
 */
@Slf4j
public class ConventionValidator {

    // ─── 预编译正则 ───

    /** 匹配 extends BaseEntity */
    private static final Pattern EXTENDS_BASE_ENTITY = Pattern.compile("extends\\s+BaseEntity");

    /** 匹配 @TableName */
    private static final Pattern TABLE_NAME_ANNO = Pattern.compile("@TableName\\s*\\(");

    /** 匹配 @EqualsAndHashCode(callSuper = true) */
    private static final Pattern EQUALS_HASH_CODE = Pattern.compile("@EqualsAndHashCode\\s*\\(\\s*callSuper\\s*=\\s*true");

    /** 匹配 serialVersionUID */
    private static final Pattern SERIAL_VERSION_UID = Pattern.compile("serialVersionUID\\s*=\\s*\\d+L");

    /** 匹配 BaseEntity 内置字段 */
    private static final Pattern REDECLARED_ID = Pattern.compile("private\\s+Long\\s+id\\s*;");
    private static final Pattern REDECLARED_CREATE_USER = Pattern.compile("private\\s+Long\\s+createUser\\s*;");
    private static final Pattern REDECLARED_CREATE_TIME = Pattern.compile("private\\s+Date\\s+createTime\\s*;");
    private static final Pattern REDECLARED_UPDATE_USER = Pattern.compile("private\\s+Long\\s+updateUser\\s*;");
    private static final Pattern REDECLARED_UPDATE_TIME = Pattern.compile("private\\s+Date\\s+updateTime\\s*;");
    private static final Pattern REDECLARED_STATUS = Pattern.compile("private\\s+Integer\\s+status\\s*;");
    private static final Pattern REDECLARED_IS_DELETED = Pattern.compile("private\\s+Integer\\s+isDeleted\\s*;");

    /** 匹配 @TableLogic（Entity继承BaseEntity后不应重复声明） */
    private static final Pattern TABLE_LOGIC = Pattern.compile("@TableLogic");

    /** 匹配 extends BladeController */
    private static final Pattern EXTENDS_BLADE_CONTROLLER = Pattern.compile("extends\\s+BladeController");

    /** 匹配 @AllArgsConstructor */
    private static final Pattern ALL_ARGS_CONSTRUCTOR = Pattern.compile("@AllArgsConstructor");

    /** 匹配 @Autowired */
    private static final Pattern AUTOWIRED = Pattern.compile("@Autowired");

    /** 匹配返回 R< */
    private static final Pattern RETURN_R = Pattern.compile("R\\s*<");

    /** 匹配 deleteLogic */
    private static final Pattern DELETE_LOGIC = Pattern.compile("deleteLogic\\s*\\(");

    /** 匹配 extends BaseService */
    private static final Pattern EXTENDS_BASE_SERVICE = Pattern.compile("extends\\s+BaseService\\s*<");

    /** 匹配 extends BaseServiceImpl */
    private static final Pattern EXTENDS_BASE_SERVICE_IMPL = Pattern.compile("extends\\s+BaseServiceImpl\\s*<");

    /** 匹配 Wrappers.lambdaQuery */
    private static final Pattern LAMBDA_QUERY = Pattern.compile("Wrappers\\s*\\.\\s*<\\w+>\\s*(lambdaQuery|query\\(\\)\\.lambda)");

    /** 匹配 @FeignClient */
    private static final Pattern FEIGN_CLIENT_ANNO = Pattern.compile("@FeignClient\\s*\\(");

    /** 匹配 @RequestParam(指定了value) */
    private static final Pattern REQUEST_PARAM_WITH_VALUE = Pattern.compile("@RequestParam\\s*\\(\\s*\"[^\"]*\"\\s*\\)");

    /** 匹配方法签名（支持泛型返回类型） */
    private static final Pattern METHOD_SIGNATURE = Pattern.compile(
            "(public|private|protected)\\s+[\\w<>,.\\s]+\\s+\\w+\\s*\\(");

    /** 匹配 @Hidden */
    private static final Pattern HIDDEN_ANNO = Pattern.compile("@Hidden");

    /** 匹配 @RestController */
    private static final Pattern REST_CONTROLLER_ANNO = Pattern.compile("@RestController");

    /** 匹配 类级别 @RequestMapping */
    private static final Pattern REQUEST_MAPPING_CLASS = Pattern.compile("@RequestMapping\\s*\\(");

    // ─── Wrapper 校验正则 ───

    /** 匹配 extends BaseEntityWrapper */
    private static final Pattern EXTENDS_BASE_ENTITY_WRAPPER = Pattern.compile("extends\\s+BaseEntityWrapper\\s*<");

    /** 匹配 static build() 方法 */
    private static final Pattern STATIC_BUILD_METHOD = Pattern.compile("static\\s+\\w+Wrapper\\s+build\\s*\\(");

    /** 匹配 entityVO 方法 */
    private static final Pattern ENTITY_VO_METHOD = Pattern.compile("entityVO\\s*\\(");

    // ─── VO 校验正则 ───

    /** 匹配 @NotBlank 或 @NotNull 校验注解 */
    private static final Pattern VALIDATION_ANNOTATION = Pattern.compile("@(NotBlank|NotNull|NotEmpty|Size|Pattern)\\s*\\(");

    /** 匹配 IVO/UVO 类名模式 */
    private static final Pattern IVO_UVO_CLASS = Pattern.compile("class\\s+\\w+(IVO|UVO)\\b");

    // ─── 平台 Cache 幻觉 / Mapper XML 校验正则 ───

    /** 匹配 import org.springblade.system.cache.XxxCache */
    private static final Pattern IMPORT_PLATFORM_CACHE = Pattern.compile(
            "import\\s+org\\.springblade\\.system\\.cache\\.(\\w+Cache)\\s*;");

    /** BladeX 平台真实存在的 system.cache 类（白名单，其余均为幻觉）。M10: public 供 CrossFileValidator 复用,避免两份漂移。 */
    public static final java.util.Set<String> KNOWN_PLATFORM_CACHES = java.util.Set.of(
            "DictCache", "DictBizCache", "ParamCache", "RegionCache",
            "SysCache", "UserCache", "ApiScopeCache", "DataScopeCache");

    /** 匹配 Mapper XML 的 namespace 声明 */
    private static final Pattern MAPPER_XML_NAMESPACE = Pattern.compile(
            "namespace\\s*=\\s*\"org\\.springblade\\.[^\"]+\"");

    /**
     * 校验生成的文件
     */
    public ValidationResult validate(GeneratedFile file) {
        List<ValidationResult.ValidationIssue> issues = new ArrayList<>();

        TaskType taskType = file.getType() == null ? TaskType.OTHER : file.getType();
        switch (taskType) {
            case STANDARD_CRUD_ENTITY -> validateEntity(file.getContent(), issues);
            case STANDARD_CRUD_CONTROLLER -> validateController(file.getContent(), issues);
            case STANDARD_CRUD_SERVICE, COMPLEX_BUSINESS_SERVICE -> validateService(file.getContent(), issues);
            case FEIGN_CLIENT -> validateFeign(file.getContent(), issues);
            case CUSTOM_MAPPER -> validateMapper(file.getContent(), issues);
            case MAPPER_XML -> validateMapperXml(file.getContent(), issues);
            case EXCEL_IMPORT_EXPORT -> validateExcel(file.getContent(), issues);
            case WRAPPER -> validateWrapper(file.getContent(), issues);
            default -> { /* 不做特殊校验 */ }
        }

        // 平台 Cache 幻觉检查（所有文件）— 命中白名单外的 *Cache import 即 ERROR，
        // 复用 executeSubPlan 的 maxReviewRetries 重试环路自动触发 LLM 修复。
        checkPlatformCacheHallucination(file.getContent(), issues);

        // 基于内容的自动检测：Wrapper 和 VO
        if (EXTENDS_BASE_ENTITY_WRAPPER.matcher(file.getContent()).find()) {
            validateWrapper(file.getContent(), issues);
        }
        if (IVO_UVO_CLASS.matcher(file.getContent()).find()) {
            validateVO(file.getContent(), issues);
        }

        // 通用检查
        checkPackageNaming(file.getContent(), issues);

        // 通过条件: 不存在 ERROR 级别问题(WARN 不阻塞,仅作为建议透传到日志)
        boolean hasError = issues.stream().anyMatch(i -> "ERROR".equalsIgnoreCase(i.getSeverity()));
        return hasError
                ? ValidationResult.fail(issues)
                : new ValidationResult(true, issues);
    }

    // ─── Entity 校验 ───

    private void validateEntity(String code, List<ValidationResult.ValidationIssue> issues) {
        if (!EXTENDS_BASE_ENTITY.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("ENTITY-001", "Entity必须extends BaseEntity"));
        }
        if (!TABLE_NAME_ANNO.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("ENTITY-002", "Entity缺少@TableName注解"));
        }
        if (!EQUALS_HASH_CODE.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("ENTITY-003", "Entity缺少@EqualsAndHashCode(callSuper = true)"));
        }
        if (!SERIAL_VERSION_UID.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.warn("ENTITY-004", "Entity缺少serialVersionUID声明"));
        }
        if (REDECLARED_ID.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("ENTITY-005", "Entity不应重新声明BaseEntity中的id字段"));
        }
        if (REDECLARED_CREATE_USER.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("ENTITY-006", "Entity不应重新声明BaseEntity中的createUser字段"));
        }
        if (REDECLARED_CREATE_TIME.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("ENTITY-007", "Entity不应重新声明BaseEntity中的createTime字段"));
        }
        if (REDECLARED_UPDATE_USER.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("ENTITY-008", "Entity不应重新声明BaseEntity中的updateUser字段"));
        }
        if (REDECLARED_UPDATE_TIME.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("ENTITY-009", "Entity不应重新声明BaseEntity中的updateTime字段"));
        }
        if (REDECLARED_STATUS.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("ENTITY-010", "Entity不应重新声明BaseEntity中的status字段"));
        }
        if (REDECLARED_IS_DELETED.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("ENTITY-011", "Entity不应重新声明BaseEntity中的isDeleted字段"));
        }
        if (EXTENDS_BASE_ENTITY.matcher(code).find() && TABLE_LOGIC.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.warn("ENTITY-012", "继承BaseEntity后不需要重复添加@TableLogic"));
        }
    }

    // ─── Controller 校验 ───

    private void validateController(String code, List<ValidationResult.ValidationIssue> issues) {
        if (!EXTENDS_BLADE_CONTROLLER.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("CTL-001", "Controller必须extends BladeController"));
        }
        if (!ALL_ARGS_CONSTRUCTOR.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("CTL-002", "Controller缺少@AllArgsConstructor（构造器注入）"));
        }
        if (AUTOWIRED.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("CTL-003", "Controller禁止使用@Autowired字段注入"));
        }
        // 检查包含 @PostMapping 或 @GetMapping 的方法返回类型
        if (!RETURN_R.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.warn("CTL-004", "Controller方法应返回R<T>类型"));
        }
        // 检查remove方法使用deleteLogic
        if (code.contains("\"/remove\"") && !DELETE_LOGIC.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.warn("CTL-005", "删除操作应使用deleteLogic()而非removeById()"));
        }
    }

    // ─── Service 校验 ───

    private void validateService(String code, List<ValidationResult.ValidationIssue> issues) {
        if (code.contains("class ") && code.contains("ServiceImpl")) {
            if (!EXTENDS_BASE_SERVICE_IMPL.matcher(code).find()) {
                issues.add(ValidationResult.ValidationIssue.error("SVC-001", "Service实现类必须extends BaseServiceImpl<Mapper, Entity>"));
            }
            if (hasCustomMethod(code) && !LAMBDA_QUERY.matcher(code).find()) {
                issues.add(ValidationResult.ValidationIssue.warn("SVC-002", "Service中自定义查询应使用Wrappers.lambdaQuery()"));
            }
            // 多表操作应有 @Transactional
            if (hasCustomMethod(code) && !code.contains("@Transactional")) {
                issues.add(ValidationResult.ValidationIssue.warn("SVC-004", "多表操作应添加@Transactional(rollbackFor = Exception.class)"));
            }
        }
        if (code.contains("interface ") && code.contains("Service")) {
            if (!EXTENDS_BASE_SERVICE.matcher(code).find()) {
                issues.add(ValidationResult.ValidationIssue.error("SVC-003", "Service接口必须extends BaseService<Entity>"));
            }
        }
    }

    // ─── Feign 校验 ───

    private void validateFeign(String code, List<ValidationResult.ValidationIssue> issues) {
        if (code.contains("interface ") && code.contains("Client")) {
            if (!FEIGN_CLIENT_ANNO.matcher(code).find()) {
                issues.add(ValidationResult.ValidationIssue.error("FEIGN-001", "Feign接口必须添加@FeignClient注解"));
            }
            if (code.contains("@RequestParam") && !REQUEST_PARAM_WITH_VALUE.matcher(code).find()) {
                issues.add(ValidationResult.ValidationIssue.warn("FEIGN-002", "Feign接口中的@RequestParam必须指定value属性"));
            }
            // Feign 接口方法应返回 R<T>
            if (!RETURN_R.matcher(code).find()) {
                issues.add(ValidationResult.ValidationIssue.warn("FEIGN-005", "Feign接口方法应返回R<T>类型"));
            }
        }
        // Fallback 类检查
        if (code.contains("Fallback") && code.contains("implements")) {
            if (!code.contains("@Component")) {
                issues.add(ValidationResult.ValidationIssue.error("FEIGN-006", "Feign Fallback类必须添加@Component注解"));
            }
        }
        if (code.contains("@RestController")) {
            if (!HIDDEN_ANNO.matcher(code).find()) {
                issues.add(ValidationResult.ValidationIssue.warn("FEIGN-003", "Feign实现类应添加@Hidden注解"));
            }
            if (REQUEST_MAPPING_CLASS.matcher(code).find()) {
                issues.add(ValidationResult.ValidationIssue.warn("FEIGN-004", "Feign实现类不应在类级别添加@RequestMapping"));
            }
        }
    }

    // ─── Mapper 校验 ───

    private void validateMapper(String code, List<ValidationResult.ValidationIssue> issues) {
        if (!code.contains("BaseMapper")) {
            issues.add(ValidationResult.ValidationIssue.warn("MAP-001", "Mapper接口应extends BaseMapper<Entity>"));
        }
        // 自定义方法参数应有 @Param
        if (code.contains("@Select") || code.contains("@Update") || code.contains("@Delete")) {
            if (!code.contains("@Param")) {
                issues.add(ValidationResult.ValidationIssue.warn("MAP-002", "自定义Mapper方法参数应使用@Param注解"));
            }
        }
    }

    // ─── Mapper XML 校验 ───

    private void validateMapperXml(String code, List<ValidationResult.ValidationIssue> issues) {
        if (code == null || code.isBlank()) {
            issues.add(ValidationResult.ValidationIssue.error("MAPXML-001", "Mapper XML 内容为空"));
            return;
        }
        if (!MAPPER_XML_NAMESPACE.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("MAPXML-001",
                    "Mapper XML 必须含 namespace=\"org.springblade.{module}.mapper.{Entity}Mapper\""));
        }
        if (!code.contains("<mapper")) {
            issues.add(ValidationResult.ValidationIssue.warn("MAPXML-002", "Mapper XML 应含 <mapper> 根元素"));
        }
    }

    // ─── 平台 Cache 幻觉校验 ───

    private void checkPlatformCacheHallucination(String code,
                                                   List<ValidationResult.ValidationIssue> issues) {
        if (code == null) return;
        java.util.Set<String> reported = new java.util.HashSet<>();
        // 1. 检 import: import org.springblade.system.cache.XxxCache;
        java.util.regex.Matcher m = IMPORT_PLATFORM_CACHE.matcher(code);
        while (m.find()) {
            String cacheClass = m.group(1);
            if (!KNOWN_PLATFORM_CACHES.contains(cacheClass) && reported.add(cacheClass)) {
                issues.add(ValidationResult.ValidationIssue.error("GEN-PLATFORM-CACHE",
                        "禁止臆造平台 Cache 类: " + cacheClass
                                + " 不存在于 org.springblade.system.cache（BladeX 无 DeptCache 等）。"
                                + "翻译字段请改用白名单内的类或留 TODO，移除该 import。"));
            }
        }
        // 2. M10: 检全限定用法(代码内 org.springblade.system.cache.XxxCache.xxx(), 不 import)
        //    LLM 可能不 import 而直接全限定调用, 仅检 import 会漏。reported 去重避免与 import 重复报。
        java.util.regex.Matcher fqm = java.util.regex.Pattern
                .compile("org\\.springblade\\.system\\.cache\\.(\\w+Cache)")
                .matcher(code);
        while (fqm.find()) {
            String cacheClass = fqm.group(1);
            if (!KNOWN_PLATFORM_CACHES.contains(cacheClass) && reported.add(cacheClass)) {
                issues.add(ValidationResult.ValidationIssue.error("GEN-PLATFORM-CACHE",
                        "禁止臆造平台 Cache 类: " + cacheClass
                                + " 不存在于 org.springblade.system.cache（全限定用法）。"
                                + "翻译字段请改用白名单内的类或留 TODO。"));
            }
        }
    }

    // ─── Excel 校验 ───

    private void validateExcel(String code, List<ValidationResult.ValidationIssue> issues) {
        if (!code.contains("@ExcelProperty")) {
            issues.add(ValidationResult.ValidationIssue.warn("EXCEL-001", "Excel模型类应使用@ExcelProperty注解标记列"));
        }
        if (!code.contains("@ColumnWidth")) {
            issues.add(ValidationResult.ValidationIssue.warn("EXCEL-002", "Excel模型类应添加@ColumnWidth/@HeadRowHeight/@ContentRowHeight注解"));
        }
        // Importer 检查
        if (code.contains("class ") && code.contains("Importer")) {
            if (!code.contains("ExcelImporter")) {
                issues.add(ValidationResult.ValidationIssue.error("EXCEL-003", "Importer类必须实现ExcelImporter接口"));
            }
        }
        // 导入逻辑应有 @Transactional
        if (code.contains("import") && code.contains("void") && code.contains("save")
                && !code.contains("@Transactional")) {
            issues.add(ValidationResult.ValidationIssue.warn("EXCEL-004", "Excel导入逻辑应添加@Transactional(rollbackFor = Exception.class)"));
        }
    }

    // ─── Wrapper 校验 ───

    private void validateWrapper(String code, List<ValidationResult.ValidationIssue> issues) {
        if (!EXTENDS_BASE_ENTITY_WRAPPER.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("WRP-001", "Wrapper必须extends BaseEntityWrapper<Entity, VO>"));
        }
        if (!STATIC_BUILD_METHOD.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("WRP-002", "Wrapper缺少static build()工厂方法"));
        }
        if (!ENTITY_VO_METHOD.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.error("WRP-003", "Wrapper必须覆盖entityVO()方法"));
        }
    }

    // ─── VO 校验 ───

    private void validateVO(String code, List<ValidationResult.ValidationIssue> issues) {
        // IVO/UVO 至少有一个校验注解
        if (!VALIDATION_ANNOTATION.matcher(code).find()) {
            issues.add(ValidationResult.ValidationIssue.warn("VO-001", "IVO/UVO的必填字段应添加@NotBlank/@NotNull校验注解"));
        }
        // UVO 必须包含 id 字段
        if (code.contains("class ") && code.contains("UVO")) {
            if (!code.contains("private Long id;") && !code.contains("private Long id ")) {
                issues.add(ValidationResult.ValidationIssue.error("VO-002", "UVO必须包含id字段并标记@NotNull"));
            }
        }
    }

    // ─── 通用校验 ───

    private void checkPackageNaming(String code,
                                     List<ValidationResult.ValidationIssue> issues) {
        // 检查包名是否遵循 org.springblade.{module}.{layer} 格式
        if (code.contains("package ") && !code.contains("org.springblade")) {
            issues.add(ValidationResult.ValidationIssue.warn("GEN-001", "包名应遵循org.springblade.{module}.{layer}格式"));
        }
    }

    private boolean hasCustomMethod(String code) {
        int methodCount = countOccurrences(code, METHOD_SIGNATURE);
        return methodCount > 2;
    }

    private int countOccurrences(String text, Pattern pattern) {
        java.util.regex.Matcher m = pattern.matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    }
}
