# 后端代码生成工作流修复计划(对照《后端工作流修改清单.md》B1–B8)

## 总览

核实结论:清单 B1–B8 全部属实,且实际产物比清单描述更严重(含 E1/E2 编译级错误)。
根因:生成器规范文档无硬约束 + 无跨文件自检,LLM 自由发挥导致 5 件套字段分裂、契约断裂。

**范围(已确认)**:只改生成器(规范文档 + 自检 + 自动修复),不手改 specialperiod 产物。
**B1/B2/B3 处理(已确认)**:阻断(ERROR) + 自动修复重试,以 Entity 为契约源头重生成 VO/IVO/UVO。

修复分两层:**治本(规范文档 + PromptBuilder 约束,约束 LLM)** + **兜底(CrossFileValidator 自检 + 自动修复,拦截漏网)**。

---

## 阶段 1:规范文档硬约束(治本)

### 1.1 bladex-data-layer.md(B1/B2/B3/B6/B8)
- VO 章节(`## VO 类型与使用场景` 顶部)加 **Hard Rule** 段:
  - 业务字段名必须与 Entity 逐字段同名;推荐 `VO extends Entity` 仅追加展示字段;若 `implements Serializable`,字段必须与 Entity 同名同类型。
  - IVO/UVO 字段类型必须与 Entity 对应字段一致;日期统一 `Date`(与目标项目既有约定一致),不用 `LocalDate`。
  - VO 每个字段必须来自:Entity 字段 / Base 书写字段 / 展示衍生字段(后缀 `Name`/`StatusName` 等);禁止凭空新增业务字段;如需新表列必须同步 DDL。
- Mapper XML 章节(新增小节):`resultMap` 的 `type` 必须与对应 Mapper 方法返回类型的元素类型一致;`property` 必须与 type 指向类的字段同名;`<select>` 的 `@Param` 前缀必须与 Mapper 接口 `@Param("xxx")` 一致。
- 状态机小节(新增):含状态机字段(`xxxStatus` 多枚举且依赖时间流转)的模块,必须生成配套定时任务推进状态,或在审查阶段显式标注推进方式。

### 1.2 bladex-business-layer.md(B4/B7)
- Controller 章节 `/save` `/update` 示例旁加约束:Service 若定义了带业务校验的方法(如 `submit`/`xxxSave`),Controller 端点必须调用该方法,而非基类 `save`/`updateById`。
- `/list` 端点旁加约束:`/list` 与 Mapper 自定义分页方法必须二选一一致 —— 要么 `Condition.getQueryWrapper(params, Entity)` 且 Mapper 无自定义分页;要么 `selectXxxPage(QVO)` 且 QVO 字段全部在 XML 生效。

### 1.3 bladex-feign.md(B5)
- 加约束:api 模块中每个 `IXxxClient` 方法,service 模块必须有匹配的 `@GetMapping(API_PREFIX + "...")` 实现端点(实现类 `XxxClient implements IXxxClient`),入参/返回类型一致。

---

## 阶段 2:PromptBuilder 加强约束

- `buildUserPrompt` 第 269 行已有"VO 字段必须与 Entity 一一对应"软约束,加强为硬措辞 + 列举禁止项(改名/凭空字段/类型漂移),并补"IVO/UVO 日期用 Date 不用 LocalDate""resultMap type 与方法返回类型一致"。
- `buildMapperXmlSystemPrompt` 补:resultMap type 必须与 Mapper 方法返回元素类型一致;`<if test="xxx.yyy">` 的 `xxx` 必须等于 `@Param` 值。
- `buildGeneralSystemPrompt` 的 VO 规范段补字段一致性硬约束(VO 任务走该 prompt)。

---

## 阶段 3:CrossFileValidator 新增自检规则

### 3.0 扩展 ClassInfo(基础设施)
- `ClassInfo` 新增 `Map<String,String> fields`(fieldName -> type)、`String tableName`、`boolean extendsBase/Tenant`。
- 解析时收集非 static 字段(跳过 `serialVersionUID`),与现有 `checkEntityDdlAlignment` 字段提取模式一致。

### 3.1 B1/B2/B3 — checkVoEntityFieldConsistency(ERROR,可修复)
- 定位 Entity:cu 包路径含 `.pojo.entity`,提取业务字段集 `E`(name->type,排除 Base 字段 id/createUser/createTime/updateUser/updateTime/status/isDeleted/tenantId/createDept)。
- 定位 VO/IVO/UVO:类名后缀为 VO/IVO/UVO/EVO(包 `.pojo.vo`),提取业务字段集 `V`。
- 规则 `VO-ENTITY-FIELD-MISMATCH`(ERROR):V 中字段不在 E、非 Base 字段、非展示衍生(后缀 `Name`/`StatusName`/`TypeName` 等白名单) -> 报错。覆盖 B1(改名)+ B3(凭空)。
- 规则 `VO-ENTITY-FIELD-TYPE-MISMATCH`(ERROR):V 与 E 同名字段类型不一致 -> 报错。覆盖 B2。
- `ContractIssue` 定位:`sourceFilePath` = VO 文件(需重生成),`contractFilePath` = Entity 文件(契约源头)。

### 3.2 B8 — checkResultMapReturnType(ERROR)
- 解析 Mapper XML 每个 `<select id="X" resultMap="Y">`,取 resultMap `type`。
- 在 Mapper 接口找方法 X 的返回类型,取元素类型(List<XxxVO> -> XxxVO)。
- type 与元素类型不一致 -> ERROR `MAPPER-RESULTMAP-TYPE-MISMATCH`,sourceFilePath=XML,contractFilePath=Mapper.java。

### 3.3 B5 — checkFeignImplExists(WARN,不阻断)
- IXxxClient 接口存在时,检查生成集合是否有 `XxxClient`(去 I 前缀)实现类。无则 WARN `FEIGN-IMPL-MISSING`。
- 不阻断(当前生成流程只生成接口,改流程是另一独立改动,本次不做)。

### 3.4 B7 — checkListMapperPageConsistency(WARN)
- Mapper 有自定义分页方法(方法名含 `Page` 且参数含 `IPage`)但 Controller `/list` 方法体未调用它 -> WARN `LIST-MAPPER-PAGE-INCONSISTENT`。

### 3.5 B4 — checkControllerCallsServiceValidation(WARN)
- IService 自定义方法(非 Base)名含 `submit`/`save`/`update`/`check`/`validate` 校验语义,但 Controller `/save` `/update` 端点未调用该方法(调了基类 save/updateById)-> WARN `CONTROLLER-SKIP-SERVICE-VALIDATION`。

### 3.6 B6 — 规范文档约束为主,自检轻量告警(WARN)
- 检测 Entity 含 `xxxStatus` 字段(类型 Integer)且 Mapper 有 `selectXxxByStatus` 类查询,但生成集合无 `timed` 包定时任务 -> WARN `STATUS-MACHINE-NO-DRIVER`。不阻断。

---

## 阶段 4:B1/B2/B3 自动修复路径(阻断 + 重生成)

仿 `retryPlanWideEntityDdlMismatches` 新增 **plan 级**修复循环(因 Entity/VO 可能分属不同子方案,plan 级兜底最稳)。

### 4.1 PromptBuilder.buildVoEntityFixPrompt
- 以 Entity 为契约源头(不可改),把 Entity 源码 + 检出问题注入,要求重生成 VO/IVO/UVO 使字段名/类型与 Entity 严格对齐。
- 复用 `buildGeneralSystemPrompt` 的 VO 规范角色。

### 4.2 BladeCodeGenRouter.fixVoWithEntity
- 仿 `fixEntityWithDdl`:接收 VO 文件 + Entity 源码 + task + issueDescription,调 LLM 重生成 VO。
- `extractCode` 复用现有 Java 提取。

### 4.3 BladeXCodeAgent.retryPlanWideVoEntityMismatches
- 仿 `retryPlanWideEntityDdlMismatches`:从 DB 拉 plan 全量文件,`VO_ENTITY_RULES = {VO-ENTITY-FIELD-MISMATCH, VO-ENTITY-FIELD-TYPE-MISMATCH}`。
- 按 VO 文件(sourceFilePath)分组,以 Entity(contractFilePath)为源头调 `fixVoWithEntity`,成功则 `replaceFile` + `persistSingleFile` + `updateGeneratedFileContent`。
- 新增 `buildTaskFromVoPath`(仿 `buildTaskFromEntityPath`):从 `org/springblade/{module}/pojo/vo/{Entity}{Suffix}.java` 反推实体名/模块名,构造 `TaskType.OTHER` task。
- 在 `executeWorkflow` 第 171 行 `retryPlanWideEntityDdlMismatches` 之后调用。

---

## 阶段 5:测试(CrossFileValidatorTest)

复用 specialperiod 真实 bug 作 fixture,为每条新规则加用例:
- B1:VO `name/type/enabled` vs Entity `periodName/periodType/isEnabled` -> 检出 VO-ENTITY-FIELD-MISMATCH。
- B2:IVO `startDate:LocalDate` vs Entity `startDate:Date` -> 检出 VO-ENTITY-FIELD-TYPE-MISMATCH。
- B3:VO `weekDays/priority` 凭空 -> 检出(合并到 B1 规则)。
- B8:XML resultMap type=Entity 但方法返回 List<VO> -> 检出 MAPPER-RESULTMAP-TYPE-MISMATCH。
- B5:有 ISpecialPeriodClient 无 SpecialPeriodClient -> 检出 FEIGN-IMPL-MISSING(WARN)。
- B7:Mapper 有 selectSpecialPeriodPage 但 Controller /list 没调 -> 检出 LIST-MAPPER-PAGE-INCONSISTENT(WARN)。
- B4:IService 有 submit 但 Controller /save 调 save -> 检出 CONTROLLER-SKIP-SERVICE-VALIDATION(WARN)。
- 反例:permit/WorkPermit(VO extends Entity,字段一致)-> 不报错(防误报)。

---

## 阶段 6:编译 + 回归验证

- `mvn -q -pl ai-developer/ai-workflow compile` 编译通过。
- `mvn -q -pl ai-developer/ai-workflow test` 全部测试通过(含新增用例)。

---

## 改动文件清单

**规范文档(治本)**:
- `ai-developer/ai-workflow/src/main/resources/bladex-docs/bladex-data-layer.md`
- `ai-developer/ai-workflow/src/main/resources/bladex-docs/bladex-business-layer.md`
- `ai-developer/ai-workflow/src/main/resources/bladex-docs/bladex-feign.md`

**生成器代码(兜底 + 修复)**:
- `agent/CrossFileValidator.java`(扩展 ClassInfo + 6 条新规则)
- `agent/PromptBuilder.java`(buildVoEntityFixPrompt + 加强 buildUserPrompt/buildMapperXmlSystemPrompt/buildGeneralSystemPrompt)
- `agent/BladeCodeGenRouter.java`(fixVoWithEntity)
- `agent/BladeXCodeAgent.java`(retryPlanWideVoEntityMismatches + buildTaskFromVoPath + executeWorkflow 调用点)

**测试**:
- `agent/CrossFileValidatorTest.java`(新增用例)

## 不做(范围排除)
- 不手改 specialperiod 产物(用户确认)。
- 不改 parseAtomicTasks 加 Feign 实现类生成任务(B5 仅规范约束 + WARN,改流程是独立改动)。
- 不做 B6 定时任务骨架自动生成(仅规范约束 + 轻量 WARN)。
- 不改 ConventionValidator(B1/B2/B3 是跨文件,放 CrossFileValidator)。
