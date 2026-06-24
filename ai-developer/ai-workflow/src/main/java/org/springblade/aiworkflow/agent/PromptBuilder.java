package org.springblade.aiworkflow.agent;

import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.convention.BladeXConventionLoader;

/**
 * 提示词构建器
 *
 * <p>根据原子任务类型，加载对应的BladeX规范文档，构建LLM提示词。
 *
 * @author AI Developer
 */
@Slf4j
public class PromptBuilder {

    private final BladeXConventionLoader conventionLoader;

    public PromptBuilder(BladeXConventionLoader conventionLoader) {
        this.conventionLoader = conventionLoader;
    }

    /**
     * 根据任务构建提示词。
     *
     * <p>系统提示词中针对 Entity/Controller/Service/Feign/Excel 等类型,会把
     * {@code {Entity}} / {@code {Name}} 占位符替换为从任务描述上下文推导的实体名,
     * 避免 LLM 自由发挥导致类名漂移(例如 Feign 接口本应叫 IOrderClient 却生成 IUserClient)。
     */
    public Prompt build(AtomicTask task) {
        String systemPrompt = buildSystemPrompt(task);
        String userPrompt = buildUserPrompt(task);
        return new Prompt(systemPrompt, userPrompt);
    }

    /**
     * 构建修复提示词（规范校验失败后）
     */
    public Prompt buildFixPrompt(ValidationResult validation, String generatedCode, AtomicTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下代码未通过BladeX规范校验，请修复以下问题：\n\n");

        sb.append("== 发现的问题 ==\n");
        for (ValidationResult.ValidationIssue issue : validation.getIssues()) {
            sb.append(String.format("- [%s] %s: %s\n",
                    issue.getSeverity(), issue.getRule(), issue.getMessage()));
        }

        sb.append("\n== 原始代码 ==\n```java\n");
        sb.append(generatedCode);
        sb.append("\n```\n");

        sb.append("\n== 任务描述 ==\n");
        sb.append(task.getTaskDescription());
        sb.append("\n\n请输出修复后的完整Java源代码，不要任何解释。");

        return new Prompt(buildSystemPrompt(task), sb.toString());
    }

    /**
     * 构建系统提示词（BladeX专家角色）。
     *
     * <p>用任务携带的 entityName/moduleName 替换提示词里的 {@code {Entity}} / {@code {Name}} / {@code {module}}
     * 占位符。entityName 缺失时回退到 "Entity",避免占位符原样进入 LLM。
     */
    private String buildSystemPrompt(AtomicTask task) {
        String entity = task.getEntityName() != null && !task.getEntityName().isBlank()
                ? task.getEntityName() : "Entity";
        String module = task.getModuleName() != null && !task.getModuleName().isBlank()
                ? task.getModuleName() : entity.toLowerCase();
        return switch (task.getType()) {
            case STANDARD_CRUD_ENTITY -> substitute(buildEntitySystemPrompt(), entity, module);
            case STANDARD_CRUD_CONTROLLER -> substitute(buildControllerSystemPrompt(), entity, module);
            case STANDARD_CRUD_SERVICE, COMPLEX_BUSINESS_SERVICE -> substitute(buildServiceSystemPrompt(), entity, module);
            case FEIGN_CLIENT -> substitute(buildFeignSystemPrompt(), entity, module);
            case EXCEL_IMPORT_EXPORT -> substitute(buildExcelSystemPrompt(), entity, module);
            case CUSTOM_MAPPER -> substitute(buildMapperSystemPrompt(), entity, module);
            case MAPPER_XML -> substitute(buildMapperXmlSystemPrompt(), entity, module);
            case WRAPPER -> substitute(buildWrapperSystemPrompt(), entity, module);
            case DDL_STATEMENT -> buildDdlSystemPrompt();
            default -> buildGeneralSystemPrompt();
        };
    }

    /** 把 {Entity} {Name} {module} 占位符替换为真实值 */
    private String substitute(String prompt, String entity, String module) {
        if (prompt == null) return null;
        return prompt.replace("{Entity}", entity)
                .replace("{Name}", entity)
                .replace("{module}", module);
    }

    /** Wrapper 不属于标准 TaskType，通过此方法供外部显式调用 */
    public String getWrapperSystemPrompt() {
        return buildWrapperSystemPrompt();
    }

    /**
     * 构建用户提示词（任务+完整子方案上下文）。
     *
     * <p>关键:在输出要求里注入<strong>统一的跨文件契约约定</strong>。
     * 因为每个文件是独立 LLM 调用生成的,如果各文件 prompt 各自约定包路径/方法名/字段集,
     * 就会出现"Controller 引用 vo.ivo.Xxx 但 VO 文件实际在 vo 包""Controller 调 wrapper.entity(ivo) 但 Wrapper 没这方法"
     * 这类跨文件不一致(实测会导致编译失败)。这里把约定集中固化,所有文件共用同一套规则。
     */
    private String buildUserPrompt(AtomicTask task) {
        String entity = task.getEntityName() != null && !task.getEntityName().isBlank()
                ? task.getEntityName() : "Entity";
        String module = task.getModuleName() != null && !task.getModuleName().isBlank()
                ? task.getModuleName() : entity.toLowerCase();

        StringBuilder sb = new StringBuilder();

        sb.append("== 任务 ==\n");
        sb.append(task.getTaskDescription());
        sb.append("\n");

        sb.append("== 输出要求 ==\n");
        switch (task.getType()) {
            case DDL_STATEMENT -> {
                sb.append("请只输出 MySQL DDL 语句(SQL),不要输出 Java 代码或解释。\n");
                sb.append("表名使用 snake_case,前缀 blade_ 或业务前缀。\n");
                sb.append("必须包含 BaseEntity 标准字段: id BIGINT AUTO_INCREMENT, create_user BIGINT,");
                sb.append(" create_time DATETIME, update_user BIGINT, update_time DATETIME, status INT DEFAULT 1, is_deleted INT DEFAULT 0。\n");
                sb.append("不要用 ```sql 包裹,直接输出纯 SQL。\n");
            }
            case STANDARD_CRUD_SERVICE, COMPLEX_BUSINESS_SERVICE -> {
                sb.append("请只生成一个 Java 类(接口或实现类),具体类型见任务描述。\n");
                sb.append("包路径 org.springblade.").append(module).append(".service 或 .service.impl。\n");
                sb.append("不要解释、不要 markdown 包裹,直接从 package 开始输出。\n");
            }
            default -> {
                sb.append("请只输出符合 BladeX 4.1.0 规范的 Java 源代码,只生成一个类或接口。\n");
                sb.append("不要解释、不要 markdown 包裹、不要任何分隔符。直接从 `package` 开始输出。\n");
            }
        }

        // 跨文件契约约定 — 所有 Java 文件共用,确保各文件包路径/方法/字段一致,可相互编译通过
        sb.append("\n== 跨文件契约约定 (必须严格遵守,否则无法编译) ==\n");
        sb.append("- 实体名严格使用: ").append(entity).append(" (不要用 Entity/User/其他词)\n");
        sb.append("- 模块名严格使用: ").append(module).append("\n");
        sb.append("- API 模块(blade-service-api/blade-").append(module).append("-api) 包路径:\n");
        sb.append("  Entity → org.springblade.").append(module).append(".pojo.entity\n");
        sb.append("  VO → org.springblade.").append(module).append(".pojo.vo (平铺, 不要 ivo/qvo/uvo/evo 子包)\n");
        sb.append("  Feign 接口 → org.springblade.").append(module).append(".feign\n");
        sb.append("- IMPL 模块(blade-service/blade-").append(module).append(") 包路径:\n");
        sb.append("  controller / mapper / service / service.impl / wrapper / excel → org.springblade.").append(module).append(".{layer}\n");
        sb.append("- IMPL 类引用 API 模块类时必须 import: org.springblade.").append(module).append(".pojo.entity.").append(entity).append(" 与 org.springblade.").append(module).append(".pojo.vo.*\n");
        sb.append("- VO 类名: ").append(entity).append("VO / ").append(entity).append("QVO / ").append(entity).append("IVO / ").append(entity).append("UVO / ").append(entity).append("EVO\n");
        sb.append("- VO 字段必须与 Entity 业务字段一一对应 (Entity 有的业务字段, IVO/UVO/VO 都要有对应字段)\n");
        sb.append("- Wrapper 必须提供: entityVO(Entity)、entity(IVO)、entity(UVO) 三个方法\n");
        sb.append("- Feign 接口不要引用 fallback 类 (本次只生成接口, fallback 后续单独生成)\n");

        return sb.toString();
    }

    // ─── 系统提示词构建方法 ───

    private String buildEntitySystemPrompt() {
        return """
                你是一位BladeX 4.1.0代码生成器。请生成一个Entity类。

                == BladeX规范 (来自 bladex-data-layer.md) ==
                - extends org.springblade.core.mp.base.BaseEntity
                  (BaseEntity已提供以下字段，不要重新声明：
                   id(Long), createUser(Long), createTime(Date),
                   updateUser(Long), updateTime(Date), status(Integer), isDeleted(Integer))
                - @Data
                - @TableName("{tableName}")
                - @EqualsAndHashCode(callSuper = true)
                - 每个业务字段使用 @Schema(description = "...") (OpenAPI 3风格)
                - 表名使用 snake_case，前缀 blade_ 或业务前缀
                - 包路径: org.springblade.{module}.pojo.entity  (API 模块)
                - 类名: {EntityName}
                - 添加 private static final long serialVersionUID = 1L;
                - **重要**: 继承 BaseEntity 后不要重复添加 @TableLogic 注解!
                - **多租户**: 若表结构含 tenant_id 列/子方案提及租户, extends org.springblade.core.tenant.mp.TenantEntity
                  (TenantEntity 已含 tenantId 字段); 否则 extends BaseEntity。
                - **字段对齐**: 字段名(camelCase)与类型须严格对应表结构列(snake_case)与列类型
                  (VARCHAR→String, BIGINT→Long, INT→Integer, DATETIME→Date, DECIMAL→BigDecimal, TEXT→String)。

                == 导入必要的包 ==
                - import com.baomidou.mybatisplus.annotation.TableName;
                - import io.swagger.v3.oas.annotations.media.Schema;
                - import lombok.Data;
                - import lombok.EqualsAndHashCode;
                - import org.springblade.core.mp.base.BaseEntity;

                == VO字段契约 (关键! 各VO单独生成, 字段必须与Entity对齐, 否则跨文件编译失败) ==
                本Entity只声明业务字段, 不要声明BaseEntity已有字段(id/createUser/createTime等)。
                后续生成的 VO/QVO/IVO/UVO/EVO 会引用本Entity的业务字段, 保持字段名完全一致。
                """
                + "\n\n完整规范:\n" + conventionLoader.getDataLayerConvention();
    }

    private String buildControllerSystemPrompt() {
        return """
                你是一位BladeX 4.1.0代码生成器。请生成一个REST Controller类。

                == BladeX规范 (来自 bladex-business-layer.md) ==
                类级别：
                - @RestController
                - @AllArgsConstructor（构造器注入，绝不用 @Autowired）
                - @RequestMapping("/{pathPrefix}")
                - @Tag(name = "{中文名称}", description = "{中文名称}接口") (OpenAPI 3)
                - extends org.springblade.core.boot.ctrl.BladeController

                标准端点：
                1. 详情: @GetMapping("/detail")，使用 Condition.getQueryWrapper(entity)
                   返回 R<{Entity}VO>，通过 {Entity}Wrapper.build().entityVO(detail) 转换
                2. 分页列表: @GetMapping("/list")，参数 Map+Query，使用 Condition.getPage/Condition.getQueryWrapper
                   返回 R<IPage<{Entity}VO>>，通过 {Entity}Wrapper.build().pageVO(pages) 转换
                3. 新增: @PostMapping("/save")，@Valid @RequestBody {Entity}IVO
                   返回 R.status(service.save(Wrapper.build().entity(ivo)))
                4. 修改: @PostMapping("/update")，@Valid @RequestBody {Entity}UVO
                   返回 R.status(service.updateById(Wrapper.build().entity(uvo)))
                5. 删除: @PostMapping("/remove")，使用 service.deleteLogic(Func.toLongList(ids))

                所有方法返回 R<T>
                使用 @ApiOperationSupport(order = N) 控制排序
                使用 @Operation(summary = "...") 描述方法
                GET 请求参数: Map<String,Object> params + Query query (不是 Entity 对象!)
                POST 请求参数: @Valid @RequestBody (IVO/UVO)
                - import API 模块类: org.springblade.{module}.pojo.entity.{Entity}, org.springblade.{module}.pojo.vo.{Entity}VO/{Entity}IVO/{Entity}UVO
                - Wrapper 在本地包 org.springblade.{module}.wrapper.{Entity}Wrapper
                """
                + "\n\n完整规范:\n" + conventionLoader.getBusinessLayerConvention();
    }

    private String buildServiceSystemPrompt() {
        return """
                你是一位BladeX 4.1.0代码生成器。请生成Service接口和实现类。

                == BladeX规范 (来自 bladex-business-layer.md) ==
                接口：
                - 接口名: I{Entity}Service
                - extends org.springblade.core.mp.base.BaseService<{Entity}>
                - 包路径: org.springblade.{module}.service  (IMPL 模块)
                - import Entity: org.springblade.{module}.pojo.entity.{Entity}

                实现类：
                - 类名: {Entity}ServiceImpl
                - extends org.springblade.core.mp.base.BaseServiceImpl<{Entity}Mapper, {Entity}>
                - implements I{Entity}Service
                - @Service
                - @AllArgsConstructor（如有额外依赖注入）
                - 自定义方法使用 Wrappers.<{Entity}>lambdaQuery() 构建查询
                - 多表操作加 @Transactional(rollbackFor = Exception.class)
                - 业务校验失败抛出 ServiceException("错误信息")
                """
                + "\n\n完整规范:\n" + conventionLoader.getBusinessLayerConvention();
    }

    private String buildFeignSystemPrompt() {
        return """
                你是一位BladeX 4.1.0代码生成器。请生成Feign客户端接口。

                == BladeX规范 (来自 bladex-feign.md) ==
                - 本次只生成 Feign 接口 I{Name}Client, 不要生成 Fallback 类和实现类
                - @FeignClient(value = "blade-{module}-service") — **不要写 fallback 属性**
                  (fallback 类本次不生成, 引用会导致编译失败; 后续单独生成时再补 fallback)
                - 接口方法返回 R<{Entity}> 或 R<List<{Entity}>>, 与 Controller 端点对应
                - @RequestParam 必须指定 value
                - 包路径: org.springblade.{module}.feign  (API 模块)
                - import Entity: org.springblade.{module}.pojo.entity.{Entity}

                == 完整代码模板 ==
                @FeignClient(value = "blade-{module}-service")
                public interface I{Name}Client {
                    String API_PREFIX = "/feign/client/{module}";
                    @GetMapping(API_PREFIX + "/get-by-id")
                    R<{Entity}> getById(@RequestParam("id") Long id);
                }
                """
                + "\n\n完整规范:\n" + conventionLoader.getFeignConvention();
    }

    private String buildExcelSystemPrompt() {
        return """
                你是一位BladeX 4.1.0代码生成器。请生成Excel导入导出类。

                == BladeX规范 (来自 bladex-excel.md) ==
                - EVO类使用 @ExcelProperty 标记列
                - Importer类实现 ExcelImporter 接口
                - 金额/数字字段使用String类型避免精度问题
                """
                + "\n\n完整规范:\n" + conventionLoader.getExcelConvention();
    }

    private String buildMapperSystemPrompt() {
        return """
                你是一位BladeX 4.1.0代码生成器。请生成Mapper接口。

                == BladeX规范 (来自 bladex-data-layer.md) ==
                - Mapper接口 extends BaseMapper<Entity>
                - 自定义方法参数使用 @Param 注解
                - 分页方法第一个参数是 IPage，返回值是 List
                """
                + "\n\n完整规范:\n" + conventionLoader.getDataLayerConvention();
    }

    private String buildMapperXmlSystemPrompt() {
        return """
                你是一位BladeX 4.1.0代码生成器。请生成 MyBatis Mapper XML 映射文件。

                == 规范 ==
                - 只输出 XML, 以 <?xml ...?> + <mapper> 开始, 不要 Java 代码或解释
                - namespace 必须等于 Mapper 接口全限定名: org.springblade.{module}.mapper.{Entity}Mapper
                - resultMap type 指向 Entity: org.springblade.{module}.pojo.entity.{Entity}
                - BaseEntity 列映射: id, create_user, create_time, update_user, update_time, status, is_deleted
                  (若表有多租户列 tenant_id, 一并映射)
                - 业务字段列按 Entity 字段补齐 (column 用 snake_case, property 用 camelCase)
                - 不要用 ```xml 包裹, 直接输出纯 XML
                """;
    }

    private String buildDdlSystemPrompt() {
        return """
                你是一位BladeX 4.1.0数据库设计专家。请生成MySQL DDL语句。

                == 规范 ==
                - 表名使用 snake_case，前缀 blade_ 或业务前缀
                - 主键 BIGINT AUTO_INCREMENT
                - 包含 create_time, update_time, is_deleted 等标准字段
                - 使用 utf8mb4 字符集
                - 添加合理的索引
                """;
    }

    private String buildWrapperSystemPrompt() {
        return """
                你是一位BladeX 4.1.0代码生成器。请生成一个Wrapper转换类。

                == BladeX规范 (来自 bladex-business-layer.md) ==
                - 类名: {Entity}Wrapper
                - extends org.springblade.core.mp.support.BaseEntityWrapper<{Entity}, {Entity}VO>
                - 提供 public static {Entity}Wrapper build() 工厂方法
                - 覆盖 public {Entity}VO entityVO({Entity} entity) 方法
                  - null 检查: if (entity == null) return new {Entity}VO();
                  - 使用 BeanUtil.copy(entity, {Entity}VO.class) 做属性复制
                - **必须**提供 IVO→Entity 转换方法: public {Entity} entity({Entity}IVO ivo)
                  - Controller 的 /save 端点会调用 Wrapper.build().entity(ivo), 缺这个方法会导致编译失败
                - **必须**提供 UVO→Entity 转换方法: public {Entity} entity({Entity}UVO uvo)
                  - Controller 的 /update 端点会调用 Wrapper.build().entity(uvo), 缺这个方法会导致编译失败
                - 包路径: org.springblade.{module}.wrapper  (IMPL 模块)
                - import API 模块类: org.springblade.{module}.pojo.entity.{Entity}, org.springblade.{module}.pojo.vo.{Entity}VO/{Entity}IVO/{Entity}UVO
                - BeanUtil 来自 org.springblade.core.tool.utils.BeanUtil
                - **禁止臆造平台 Cache 类**: BladeX 没有 DeptCache / DictCache.getDeptName 这种类。
                  若确实需要翻译部门名/角色名, 只能用 org.springblade.system.cache.SysCache:
                    SysCache.getDeptNames(deptId)  // 返回 List<String>, 用 Func.join() 拼接
                    SysCache.getRoleNames(roleId)
                  否则 entityVO 只做 BeanUtil.copy, 翻译字段留空 + 注释 "// TODO 业务层补充"
                  绝不要 import org.springblade.system.cache.DeptCache 等 SysCache 之外的类

                == 完整代码模板 ==
                public class {Entity}Wrapper extends BaseEntityWrapper<{Entity}, {Entity}VO> {
                    public static {Entity}Wrapper build() { return new {Entity}Wrapper(); }

                    @Override
                    public {Entity}VO entityVO({Entity} entity) {
                        if (entity == null) return new {Entity}VO();
                        return Objects.requireNonNull(BeanUtil.copy(entity, {Entity}VO.class));
                    }

                    public {Entity} entity({Entity}IVO ivo) {
                        return BeanUtil.copy(ivo, {Entity}.class);
                    }

                    public {Entity} entity({Entity}UVO uvo) {
                        return BeanUtil.copy(uvo, {Entity}.class);
                    }
                }
                """
                + "\n\n完整规范:\n" + conventionLoader.getBusinessLayerConvention();
    }

    private String buildGeneralSystemPrompt() {
        return """
                你是一位BladeX 4.1.0代码生成器。
                请根据任务描述生成符合BladeX框架规范的代码。

                BladeX 4.1.0 技术栈:
                - Java 17, Spring Boot 3.2.4, Spring Cloud
                - MyBatis-Plus, Nacos, Sentinel
                - BaseEntity, R<T>, BladeController, Wrappers.lambdaQuery()

                == VO 类生成规范 (生成 QVO/IVO/UVO/VO/EVO 时适用) ==
                - 包路径: org.springblade.{module}.pojo.vo  (API 模块, 平铺, 不要 ivo/qvo/uvo/evo 子包)
                - 类名: {Entity}QVO / {Entity}IVO / {Entity}UVO / {Entity}VO / {Entity}EVO
                - @Data + implements Serializable + serialVersionUID
                - **字段必须与 Entity 业务字段一一对应** (Entity 有 orderNo/customerName/amount, 这些 VO 也要有同名同类型字段)
                - IVO (新增): 必填字段加 @NotBlank/@NotNull
                - UVO (修改): 必须含 id(@NotNull) + Entity 的所有可修改业务字段 (新增能改的字段, 修改也能改)
                - VO (输出): Long 类型 id 加 @JsonSerialize(using = ToStringSerializer.class)
                - QVO (查询): Entity 的可筛选字段 + 范围字段(如 minAmount/maxAmount)
                - import 路径必须与上述包路径一致, 否则引用方编译失败
                """;
    }
}
