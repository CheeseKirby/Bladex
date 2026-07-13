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
     * 构建带参考项目的提示词 — 阶段2增强"适配并接入参考项目"。
     *
     * <p>注入两段参考信息:
     * <ol>
     *   <li>项目适配摘要(pom/配置/包结构/启动类约定)— 让新模块能接入参考项目编译</li>
     *   <li>同类代码摘要(风格/注解/字段)— 让代码风格贴合现有</li>
     * </ol>
     *
     * @param task               原子任务
     * @param adaptationSummary  项目适配摘要(来自 buildAdaptationSummary),可为 null
     * @param referenceSummary   同类代码摘要(来自 buildStructuredSummary),可为 null
     */
    public Prompt buildWithReference(AtomicTask task, String adaptationSummary, String referenceSummary) {
        String systemPrompt = buildSystemPrompt(task);
        StringBuilder userPrompt = new StringBuilder(buildUserPrompt(task));
        // 1. 项目适配摘要(pom/配置/包结构/启动类)— 优先,让新模块接入参考项目
        if (adaptationSummary != null && !adaptationSummary.isBlank()) {
            userPrompt.append("\n\n").append(adaptationSummary);
            userPrompt.append("\n【重要】以上版本适配约束优先级最高,覆盖规范文档默认。");
            userPrompt.append("若规范用 Swagger v3(@Schema)但参考项目是 v2,必须用 v2(@ApiModel/@ApiModelProperty);");
            userPrompt.append("若规范用 jakarta 但参考项目是 javax,必须用 javax。不遵守会导致编译失败。\n");
        }
        // 2. 同类代码摘要(风格/注解/字段)
        if (referenceSummary != null && !referenceSummary.isBlank()) {
            userPrompt.append("\n\n== 现有项目同类参考(仅供参考风格/注解/结构,不要照抄业务逻辑,字段按需求生成)==\n");
            userPrompt.append(referenceSummary);
        }
        return new Prompt(systemPrompt, userPrompt.toString());
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
     * 构建跨文件修复提示词 — 以 Service 接口为契约源头,重生成与接口契约不符的实现类。
     *
     * <p>覆盖两类场景(都以 IService 接口为契约源头, 把接口源码作 context 注入):
     * <ul>
     *   <li>Controller 调用 Service 接口未声明的方法/参数个数不符 → 重生成 Controller 使调用对齐接口;</li>
     *   <li>ServiceImpl 的 @Override 方法在 IService 接口未声明 → 重生成 ServiceImpl 使其方法对齐接口
     *       (移除接口未声明的方法, 或实现类只保留接口声明 + 父类标准方法)。</li>
     * </ul>
     * Service 接口先于实现类生成、是契约源头,故修复策略是<b>改实现类对齐接口</b>,不改接口。
     *
     * @param issueDescription    CrossFileValidator 检出的问题描述(含方法名/参数个数差异)
     * @param implCode            需修复的实现类(Controller/ServiceImpl)当前源码
     * @param serviceInterfaceCode Service 接口源码(契约源头,不可改,仅作参考)
     * @param task                原子任务(用于系统提示词角色与实体名/模块名占位符)
     */
    public Prompt buildCrossFileFixPrompt(String issueDescription, String implCode,
                                          String serviceInterfaceCode, AtomicTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下实现类与 Service 接口的契约不一致,会导致编译失败。\n");
        sb.append("Service 接口是契约源头(已生成,不可修改),请修改实现类使其对齐 Service 接口定义。\n\n");

        sb.append("== 检出的问题 ==\n").append(issueDescription).append("\n\n");

        sb.append("== Service 接口定义 (契约源头,不可改,仅作参考) ==\n```java\n");
        sb.append(serviceInterfaceCode);
        sb.append("\n```\n\n");

        sb.append("== 当前实现类代码 (需修复) ==\n```java\n");
        sb.append(implCode);
        sb.append("\n```\n\n");

        sb.append("== 任务描述 ==\n");
        sb.append(task.getTaskDescription());
        sb.append("\n\n修复要求:\n");
        sb.append("- 使实现类与上方 Service 接口定义完全一致: 方法名与参数个数对齐接口声明。\n");
        sb.append("- 实现类(@Override 的方法)必须能在接口中找到对应声明;接口未声明的方法要移除,或仅保留接口声明 + 父类标准方法。\n");
        sb.append("- 调用接口方法时(如 Controller 调 Service),方法名+参数个数必须与接口声明一致;接口无对应方法则改用已声明方法或父类标准方法(save/updateById/getOne/deleteLogic/list/page)。\n");
        sb.append("- 保留实现类原有的全部 BladeX 规范(注解、继承、返回 R<T> 等)。\n");
        sb.append("- 输出修复后的完整 Java 源代码,不要任何解释、不要 markdown 包裹。\n");

        return new Prompt(buildSystemPrompt(task), sb.toString());
    }

    /**
     * 构建 Entity↔DDL 修复提示词 — 以 DDL 为契约源头,重生成与表结构不一致的 Entity。
     *
     * <p>场景: Entity 与 DDL 不一致(缺列/类型不符/多租户丢失),导致编译或运行失败。
     * DDL 是表结构契约源头(先于 Entity 生成),故修复策略是<b>改 Entity 对齐 DDL</b>,
     * 把 DDL 源码作为 context 注入,让 LLM 据此重建 Entity 字段。
     *
     * @param issueDescription CrossFileValidator 检出的问题描述(缺哪些列/类型不符)
     * @param entityCode       需修复的 Entity 当前源码
     * @param ddlCode          DDL 源码(契约源头,不可改,仅作参考)
     * @param task             原子任务(系统提示词角色 + 实体名/模块名)
     */
    public Prompt buildEntityDdlFixPrompt(String issueDescription, String entityCode,
                                          String ddlCode, AtomicTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下 Entity 与数据库 DDL 表结构不一致,会导致编译或运行失败。\n");
        sb.append("DDL 是表结构契约源头(已生成,不可修改),请修改 Entity 使其字段与 DDL 列严格对齐。\n\n");

        sb.append("== 检出的问题 ==\n").append(issueDescription).append("\n\n");

        sb.append("== DDL 表结构定义 (契约源头,不可改,仅作参考) ==\n```sql\n");
        sb.append(ddlCode);
        sb.append("\n```\n\n");

        sb.append("== 当前 Entity 代码 (需修复) ==\n```java\n");
        sb.append(entityCode);
        sb.append("\n```\n\n");

        sb.append("== 任务描述 ==\n");
        sb.append(task.getTaskDescription());
        sb.append("\n\n修复要求:\n");
        sb.append("- Entity 业务字段必须与 DDL 业务列一一对应(camelCase 字段 ↔ snake_case 列),不缺不多余。\n");
        sb.append("- 字段类型严格按 DDL 列类型映射: VARCHAR/CHAR/TEXT→String, BIGINT→Long, INT/TINYINT→Integer, ");
        sb.append("DATETIME/DATE/TIMESTAMP→Date, DECIMAL/NUMERIC→BigDecimal。\n");
        sb.append("- 跳过 BaseEntity 已提供的列(id/create_user/create_time/update_user/update_time/status/is_deleted),");
        sb.append("不要在 Entity 重复声明这些字段。\n");
        sb.append("- 多租户: 若 DDL 含 tenant_id 列, Entity 必须 extends TenantEntity");
        sb.append("(org.springblade.core.tenant.mp.TenantEntity, 已含 tenantId 字段,不要再声明 tenantId);");
        sb.append("若 DDL 无 tenant_id 列, extends BaseEntity。\n");
        sb.append("- 保留 @Data/@TableName/@EqualsAndHashCode(callSuper=true)/Swagger注解/serialVersionUID 等 BladeX 规范(Swagger 注解版本按参考项目适配)。\n");
        sb.append("- 输出修复后的完整 Java 源代码,不要任何解释、不要 markdown 包裹。\n");

        return new Prompt(buildSystemPrompt(task), sb.toString());
    }

    /**
     * 构建系统提示词（BladeX专家角色）。
     *
     * <p>用任务携带的 entityName/moduleName 替换提示词里的 {@code {Entity}} / {@code {Name}} / {@code {module}}
     * 占位符。entityName 缺失时回退到 "Entity",避免占位符原样进入 LLM。
     */
    /**
     * 构建 VO↔Entity 修复提示词 - 以 Entity 为契约源头,重生成与 Entity 字段不一致的 VO/IVO/UVO。
     *
     * <p>场景: VO/IVO/UVO 业务字段与 Entity 不同名/不同类型, BeanUtil.copy 丢字段, CRUD 数据流断裂。
     * Entity 是字段契约源头(先于 VO 生成或同属 API 模块), 故修复策略是<b>改 VO 对齐 Entity</b>,
     * 把 Entity 源码作为 context 注入, 让 LLM 据此重建 VO 字段。
     *
     * <p>由 BladeXCodeAgent 在 plan 级跨文件校验检出 VO-ENTITY-FIELD-MISMATCH /
     * VO-ENTITY-FIELD-TYPE-MISMATCH 后调用。
     *
     * @param issueDescription CrossFileValidator 检出的问题描述(哪些字段改名/类型不符/凭空)
     * @param voCode           需修复的 VO/IVO/UVO 当前源码
     * @param entityCode       Entity 源码(契约源头,不可改,仅作参考)
     * @param task             原子任务(系统提示词角色 + 实体名/模块名)
     */
    public Prompt buildVoEntityFixPrompt(String issueDescription, String voCode,
                                          String entityCode, AtomicTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下 VO/IVO/UVO 与 Entity 的字段不一致,会导致 BeanUtil.copy 丢字段,CRUD 数据流断裂。\n");
        sb.append("Entity 是字段契约源头(已生成,不可修改),请修改 VO 使其字段与 Entity 严格对齐。\n\n");

        sb.append("== 检出的问题 ==\n").append(issueDescription).append("\n\n");

        sb.append("== Entity 定义 (契约源头,不可改,仅作参考) ==\n```java\n");
        sb.append(entityCode);
        sb.append("\n```\n\n");

        sb.append("== 当前 VO 代码 (需修复) ==\n```java\n");
        sb.append(voCode);
        sb.append("\n```\n\n");

        sb.append("== 任务描述 ==\n");
        sb.append(task.getTaskDescription());
        sb.append("\n\n修复要求:\n");
        sb.append("- VO/IVO/UVO 的业务字段必须与 Entity 业务字段逐字段同名(如 Entity 用 periodName, VO 也用 periodName, 不要改成 name);\n");
        sb.append("- 同名字段类型必须与 Entity 一致(Entity 是 Date 就用 Date, 不要用 LocalDate;Entity 是 Integer 就用 Integer);\n");
        sb.append("- 移除 Entity 不存在的凭空业务字段(如 weekDays/priority);确为展示衍生字段须用 xxxName 后缀;\n");
        sb.append("- 保留 Base 字段(id/createUser/createTime/updateUser/updateTime/status/isDeleted/tenantId/createDept)与校验注解(@NotBlank/@NotNull)、UVO 的 id(@NotNull);\n");
        sb.append("- IVO 是新增对象,含新增所需业务字段(与 Entity 同名同类型);UVO 含 id + 可修改业务字段;VO 是输出对象,含 Entity 全部业务字段 + 必要展示衍生字段;\n");
        sb.append("- 保留 @Data/implements Serializable/serialVersionUID/@JsonSerialize(Long id 序列化为 String)/Swagger 注解(版本按参考项目适配)等 BladeX 规范;\n");
        sb.append("- 输出修复后的完整 Java 源代码,不要任何解释、不要 markdown 包裹。\n");

        return new Prompt(buildSystemPrompt(task), sb.toString());
    }

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
            default -> substitute(buildGeneralSystemPrompt(), entity, module);
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
                sb.append("请输出 MySQL DDL 语句(CREATE TABLE),若字段注释提到字典则再追加 blade_dict_biz 的 INSERT 语句,不要输出 Java 代码或解释。\n");
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
                sb.append("请只输出符合 BladeX 规范的 Java 源代码(版本按参考项目适配),只生成一个类或接口。\n");
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
        sb.append("- VO/IVO/UVO 业务字段必须与 Entity 逐字段同名同类型 (Entity 用 periodName 就用 periodName, 不要改成 name; Entity 是 Date 就用 Date, 不要用 LocalDate)。凭空业务字段(如 weekDays/priority)禁止; 展示衍生字段用 xxxName 后缀。违反会导致 BeanUtil.copy 丢字段、CRUD 断裂, 且会被跨文件自检拦截并强制重生成。\n");
        sb.append("- Mapper XML: resultMap 的 type 必须与对应 Mapper 方法返回元素类型一致(方法返回 List<XxxVO> 则 resultMap type 指向 XxxVO 且 property 与 VO 字段同名; 返回 List<Entity> 则指向 Entity); <select> 的 @Param 前缀必须与 Mapper 接口 @Param(\"xxx\") 一致。\n");
        sb.append("- Wrapper 必须提供: entityVO(Entity)、entity(IVO)、entity(UVO) 三个方法\n");
        sb.append("- Feign 接口不要引用 fallback 类 (本次只生成接口, fallback 后续单独生成)\n");
        sb.append("- Controller 调用 I").append(entity).append("Service 自定义方法时, 方法名+参数个数必须与接口声明完全一致\n");
        sb.append("  (Service 接口是契约源头, Controller 对齐它; 标准 CRUD 用父类 save/updateById/getOne/deleteLogic/list/page)\n");
        sb.append("- 启动类 ").append(entity).append("Application 的 BladeApplication.run(appName, ...) 第一个参数 appName 必须为 \"blade-").append(module).append("\",\n");
        sb.append("  不得加 -service 等后缀(如 blade-").append(module).append("-service 是错误的), 以保证与前端 API 路径前缀 /api/blade-").append(module).append(" 及 Nacos 服务名一致。\n");
        sb.append("- Mapper 自定义查询方法(如 export").append(entity).append(")的返回类型必须与 I").append(entity).append("Service 接口对应方法声明的返回类型严格一致:\n");
        sb.append("  若接口声明 List<").append(entity).append("EVO>, Mapper 与 Mapper.xml 也必须返回 List<").append(entity).append("EVO>, 不可返回 List<").append(entity).append("VO> 导致类型不匹配。\n");

        return sb.toString();
    }

    // ─── 系统提示词构建方法 ───

    private String buildEntitySystemPrompt() {
        return """
                你是一位BladeX代码生成器。请生成一个Entity类。

                == BladeX规范 (来自 bladex-data-layer.md) ==
                - extends org.springblade.core.mp.base.BaseEntity
                  (BaseEntity已提供以下字段，不要重新声明：
                   id(Long), createUser(Long), createTime(Date),
                   updateUser(Long), updateTime(Date), status(Integer), isDeleted(Integer))
                - @Data
                - @TableName("{tableName}")
                - @EqualsAndHashCode(callSuper = true)
                - 每个业务字段使用 Swagger 注解标注描述(注解版本按参考项目适配: v2 用 @ApiModelProperty(value="..."), v3 用 @Schema(description="..."))
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
                - import Swagger 注解(按参考项目: v2 用 io.swagger.annotations.ApiModel/ApiModelProperty, v3 用 io.swagger.v3.oas.annotations.media.Schema);
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
                你是一位BladeX代码生成器。请生成一个REST Controller类。

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

                == Controller→Service 调用签名一致性 (跨文件契约,违反会编译失败) ==
                - I{Entity}Service 是契约源头(与本 Controller 分两次生成,接口定义即唯一真相)。
                - 调用 I{Entity}Service 的方法时,方法名与参数个数必须与接口声明完全一致。
                - 标准 CRUD 一律用父类方法: save/updateById/getOne/deleteLogic/list/page,不要改这些调用的参数个数。
                - 自定义方法必须先在 I{Entity}Service 接口声明后才能调用;不可凭空调用接口未声明的方法,也不可增减参数个数。
                - 若不确定接口有哪些方法,只调用上述父类标准方法,不要臆造自定义方法调用。
                """
                + "\n\n完整规范:\n" + conventionLoader.getBusinessLayerConvention();
    }

    private String buildServiceSystemPrompt() {
        return """
                你是一位BladeX代码生成器。请生成Service接口和实现类。

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

                == 接口契约稳定性 (跨文件契约,违反会编译失败) ==
                - 本次同时生成接口(I{Entity}Service)和实现类({Entity}ServiceImpl),二者方法必须严格一一对应:
                  实现类里每个 @Override 自定义方法,都必须先在接口中声明;接口里声明的每个方法,实现类都必须实现。
                - 禁止"接口体为空(只 extends BaseService)却实现类堆一堆 @Override 自定义方法" — 这会编译失败。
                - 若需要自定义业务方法: 先在接口声明方法签名, 再在实现类 @Override 实现它, 方法名+参数个数完全一致。
                - 标准方法(save/updateById/getOne/deleteLogic/list/page 等)由父类 BaseService 提供, 接口与实现类都不要重复声明。
                - 接口(I{Entity}Service)是 Controller 调用的契约源头, Controller 按接口声明的方法名+参数个数调用, 不可漂移。

                == 引用闭合 (跨文件契约,违反会编译失败) ==
                - ServiceImpl 只允许 import 本次确定会生成的类:
                  本模块的 {Entity}Mapper、I{Entity}Service、{Entity}Wrapper、{Entity}IVO/UVO/VO/QVO/EVO、{Entity} 实体。
                - 禁止 import 其它实体的 Mapper / Service(如 RecordMapper、IUserService 等),即使业务场景看似需要 —
                  本次只生成 {Entity} 单实体的全套文件, 其它实体本次不存在 → 编译失败。
                - 若业务真需要其它实体, 在方法体里留 TODO 注释(// TODO 需依赖 XxxMapper, 后续补充), 不要写 import 也不要声明字段。

                == 字段访问闭合 (跨文件契约,违反会编译失败) ==
                - ServiceImpl 方法体里访问 Entity 的 getter/setter(如 entity.getXxx()/entity.setXxx(v))时, Xxx 必须是 Entity 实际声明的业务字段。
                - 禁止虚构 Entity 不存在的字段。例: Entity 只有 gasCheckTime, 就不要写 getGasTestTime(); Entity 没有 blindNo, 就不要写 getBlindNo()/setBlindNo()。
                - 生成 ServiceImpl 时看不到 Entity 源码, 故对业务字段访问采取保守策略:
                  * 优先使用方法参数传入的数据(如 ivo.getXxx()), 或 BaseEntity 标准字段(id/createTime/updateTime/status/isDeleted/createUser/updateUser/createDept/tenantId)。
                  * 若业务方法确实需要访问 Entity 的某个业务字段而你不确定其是否存在, 在方法体里留 TODO 注释(// TODO 需访问 Entity.xxx 字段, 待确认), 不要臆造 getter/setter 调用。
                  * 状态机/审批等业务方法里对 Entity 赋值, 只用 setStatus 等 BaseEntity 字段或方法参数, 不要凭空 setXxx 不存在的字段。
                """
                + "\n\n完整规范:\n" + conventionLoader.getBusinessLayerConvention();
    }

    private String buildFeignSystemPrompt() {
        return """
                你是一位BladeX代码生成器。请生成Feign客户端接口。

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
                你是一位BladeX代码生成器。请生成Excel导入导出类。

                == BladeX规范 (来自 bladex-excel.md) ==
                - EVO类使用 @ExcelProperty 标记列
                - Importer类实现 ExcelImporter 接口
                - 金额/数字字段使用String类型避免精度问题
                """
                + "\n\n完整规范:\n" + conventionLoader.getExcelConvention();
    }

    private String buildMapperSystemPrompt() {
        return """
                你是一位BladeX代码生成器。请生成Mapper接口。

                == BladeX规范 (来自 bladex-data-layer.md) ==
                - Mapper接口 extends BaseMapper<Entity>
                - 自定义方法参数使用 @Param 注解
                - 分页方法第一个参数是 IPage，返回值是 List
                """
                + "\n\n完整规范:\n" + conventionLoader.getDataLayerConvention();
    }

    private String buildMapperXmlSystemPrompt() {
        return """
                你是一位BladeX代码生成器。请生成 MyBatis Mapper XML 映射文件。

                == 规范 ==
                - 只输出 XML, 以 <?xml ...?> + <mapper> 开始, 不要 Java 代码或解释
                - namespace 必须等于 Mapper 接口全限定名: org.springblade.{module}.mapper.{Entity}Mapper
                - resultMap type 必须与对应 Mapper 方法返回元素类型一致:
                  * 方法返回 List<{Entity}> / {Entity} -> resultMap type 指向 Entity, property 用 Entity 字段名(periodName 等, 非 name)
                  * 方法返回 List<{Entity}VO> / {Entity}VO -> resultMap type 指向 {Entity}VO, property 用 VO 字段名
                  同一个 resultMap 若被多个方法共用, 这些方法返回元素类型必须相同。
                - resultMap 的 property 必须与 type 指向类的字段同名, column 用 snake_case; 不要写 type 类不存在的 property。
                - <select> 的 @Param 前缀必须与 Mapper 接口 @Param("xxx") 一致 (接口 @Param("qvo") 则 XML 用 qvo.xxx, 不要用 param.xxx)。
                - BaseEntity 列映射: id, create_user, create_time, update_user, update_time, status, is_deleted
                  (若表有多租户列 tenant_id, 一并映射)
                - 业务字段列按 Entity 字段补齐 (column 用 snake_case, property 用 camelCase)
                - 不要用 ```xml 包裹, 直接输出纯 XML
                """;
    }

    private String buildDdlSystemPrompt() {
        return """
                你是一位BladeX数据库设计专家。请生成MySQL DDL语句。

                == 规范 ==
                - 表名使用 snake_case，前缀 blade_ 或业务前缀
                - 主键 BIGINT AUTO_INCREMENT
                - 包含 create_time, update_time, is_deleted 等标准字段
                - 使用 utf8mb4 字符集
                - 添加合理的索引

                == 字典数据生成 (重要, 避免前端 select 字段无选项) ==
                若某字段注释提到"字典 xxx: 1-aaa 2-bbb 3-ccc ..."（xxx 为字典 code, 如 work_type/permit_status）,
                则在 CREATE TABLE 之后, 追加 blade_dict_biz 的 INSERT 语句, 规则:
                - 顶级分类: parent_id=0, code=xxx, dict_key='-1', dict_value=该字典中文名, sort=0
                - 子项: parent_id=顶级id, code=xxx, dict_key='1'/'2'/..., dict_value=aaa/bbb/..., sort 按序
                - tenant_id='000000', is_sealed=0, status=1, is_deleted=0
                - id 用 19 位大数 (如 1800000000000000001 起递增, 避免与现有数据冲突)
                - 字典 code 与字段注释里的 xxx 保持一致 (不要改名, 如注释是 work_type 就用 work_type, 不要改成 permit_work_type)
                示例:
                INSERT INTO blade_dict_biz (id,tenant_id,parent_id,code,dict_key,dict_value,sort,is_sealed,status,is_deleted) VALUES
                (1800000000000000001,'000000',0,'work_type','-1','作业类型',0,0,1,0),
                (1800000000000000002,'000000',1800000000000000001,'work_type','1','动火作业',1,0,1,0);
                """;
    }

    private String buildWrapperSystemPrompt() {
        return """
                你是一位BladeX代码生成器。请生成一个Wrapper转换类。

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
                你是一位BladeX代码生成器。
                请根据任务描述生成符合BladeX框架规范的代码。

                BladeX 技术栈(默认;若"参考项目结构适配"段指定了版本,以参考项目为准):
                - Java + Spring Boot + Spring Cloud(版本按参考项目适配)
                - MyBatis-Plus, Nacos, Sentinel
                - BaseEntity, R<T>, BladeController, Wrappers.lambdaQuery()
                - 注:Java 8 项目禁用 record/sealed/var/switch表达式;Spring Boot 2.x 用 javax.*,3.x 用 jakarta.*

                == VO 类生成规范 (生成 QVO/IVO/UVO/VO/EVO 时适用) ==
                - 包路径: org.springblade.{module}.pojo.vo  (API 模块, 平铺, 不要 ivo/qvo/uvo/evo 子包)
                - 类名: {Entity}QVO / {Entity}IVO / {Entity}UVO / {Entity}VO / {Entity}EVO
                - @Data + implements Serializable + serialVersionUID
                - **字段必须与 Entity 业务字段逐字段同名同类型** (Entity 有 orderNo/customerName/amount, IVO/UVO/VO 也要有同名同类型字段; Entity 用 periodName 就用 periodName 不要改成 name; Entity 是 Date 就用 Date 不要用 LocalDate)。凭空业务字段禁止; 展示衍生字段(字典翻译)用 xxxName 后缀。违反会被自检拦截并强制重生成。
                - IVO (新增): 必填字段加 @NotBlank/@NotNull
                - UVO (修改): 必须含 id(@NotNull) + Entity 的所有可修改业务字段 (新增能改的字段, 修改也能改)
                - VO (输出): Long 类型 id 加 @JsonSerialize(using = ToStringSerializer.class)
                - QVO (查询): Entity 的可筛选字段 + 范围字段(如 minAmount/maxAmount)
                - import 路径必须与上述包路径一致, 否则引用方编译失败
                """;
    }
}
