# Wrapper 与 Controller - 转换类 + REST 端点

## 实体名: SpecialPeriod
## 模块名: specialperiod
## 表名: blade_special_period
## 包路径: org.springblade.specialperiod
## 模块结构: blade-service/blade-specialperiod

## 目标层: Service 模块 (blade-specialperiod) - Wrapper + Controller

## 1. Wrapper: org.springblade.specialperiod.wrapper.SpecialPeriodWrapper
- 继承 `BaseEntityWrapper<SpecialPeriod, SpecialPeriodVO>`
- 静态方法 `build()` 返回实例
- `entityVO(SpecialPeriod entity)`: BeanUtil.copy + 设置 periodTypeDesc(节假日/公休日/夜间) + configStatusDesc(待生效/生效中/已失效)
- `entity(SpecialPeriodIVO ivo)`: IVO -> Entity
- `entity(SpecialPeriodUVO uvo)`: UVO -> Entity

## 2. 主控制器: org.springblade.specialperiod.controller.SpecialPeriodController
- 继承 `BladeController`
- @RestController, @AllArgsConstructor
- @RequestMapping(AppConstant.APPLICATION_DESK_NAME + "/specialperiod")
- @Api(value="特殊时段配置", tags="特殊时段动火作业升级管控")
- 端点列表:
  1. GET /detail - 详情(传入 id)
  2. GET /list - 分页列表(QVO + Query)
  3. POST /save - 新增(@Valid @RequestBody IVO)
  4. POST /update - 修改(@Valid @RequestBody UVO)
  5. POST /remove - 逻辑删除
  6. POST /changeStatus - 状态流转(id + targetStatus)
  7. POST /match - 动火作业命中校验(@RequestBody HotworkMatchDTO)
  8. POST /triggerUpgrade - 触发升级审批流程(periodId + HotworkMatchDTO)
  9. POST /import - Excel 批量导入(MultipartFile)
  10. GET /export - Excel 导出(QVO + HttpServletResponse)
- 使用 @ApiOperationSupport(order=N) + @ApiOperation
- 返回统一 R<T> 包装

## 3. 统计控制器: org.springblade.specialperiod.controller.SpecialPeriodStatController
- 继承 `BladeController`
- @RestController, @AllArgsConstructor
- @RequestMapping("/specialperiod/stat")
- @Api(value="特殊时段作业统计", tags="特殊时段动火作业升级管控-统计")
- 端点:
  - GET /byPeriodType - 按时段类型统计(specialPeriodWorkStat 页面数据源)

## 技术约束
- Java 8, javax.validation, Swagger v2 (io.swagger.annotations.*)
- Controller 不含业务逻辑, 全部委托 ISpecialPeriodService
- import/export 端点委托 Service 层方法(在 sub_3 中声明, sub_5 中实现 Excel 工具)