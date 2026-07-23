# Entity 与 VO - 实体类 + 五类视图对象 + DTO

## 实体名: SpecialPeriod
## 模块名: specialperiod
## 表名: blade_special_period
## 包路径: org.springblade.specialperiod
## 模块结构: blade-service-api/blade-specialperiod-api

## 目标层: API 模块 (blade-specialperiod-api)

## 包结构
```
blade-service-api/blade-specialperiod-api
└── src/main/java/org/springblade/specialperiod
    ├── entity/SpecialPeriod.java
    ├── vo/SpecialPeriodVO.java
    ├── vo/qvo/SpecialPeriodQVO.java
    ├── vo/ivo/SpecialPeriodIVO.java
    ├── vo/uvo/SpecialPeriodUVO.java
    ├── vo/evo/SpecialPeriodEVO.java
    └── dto/HotworkMatchDTO.java
```

## 父 pom 注册
在父 pom 的 `<modules>` 中添加:
```xml
<module>blade-service-api/blade-specialperiod-api</module>
```
API 模块 pom parent 为 blade-service-api。

## 1. Entity: org.springblade.specialperiod.entity.SpecialPeriod
- 继承 `org.springblade.core.mp.base.BaseEntity`
- 注解: `@Data`, `@TableName("blade_special_period")`, `@EqualsAndHashCode(callSuper=true)`, `@ApiModel(value="SpecialPeriod对象", description="特殊时段配置")`
- Swagger v2: `io.swagger.annotations.ApiModel/ApiModelProperty`
- 字段: periodName(String), periodType(Integer), startDate(Date), endDate(Date), startTime(String), endTime(String), upgradeLevel(Integer), isUpgrade(Integer), configStatus(Integer), remark(String)

## 2. VO: org.springblade.specialperiod.vo.SpecialPeriodVO
- 继承 SpecialPeriod
- 额外字段: periodTypeDesc(String), configStatusDesc(String)
- 注解: `@ApiModel(value="SpecialPeriodVO对象")`

## 3. QVO: org.springblade.specialperiod.vo.qvo.SpecialPeriodQVO
- 查询条件: periodName(模糊), periodType, configStatus, isUpgrade, startDate(String), endDate(String)

## 4. IVO: org.springblade.specialperiod.vo.ivo.SpecialPeriodIVO
- 新增入参，使用 javax.validation 约束
- @NotBlank periodName, @NotNull periodType/startDate/endDate/upgradeLevel
- startTime, endTime, isUpgrade, configStatus, remark 可选

## 5. UVO: org.springblade.specialperiod.vo.uvo.SpecialPeriodUVO
- 继承 SpecialPeriodIVO
- 新增 @NotNull id(Long)

## 6. EVO: org.springblade.specialperiod.vo.evo.SpecialPeriodEVO
- 使用 EasyExcel 注解: `@ExcelProperty`, `@ColumnWidth(20)`
- 字段与 IVO 对应

## 7. DTO: org.springblade.specialperiod.dto.HotworkMatchDTO
- 供其他模块引用的动火作业命中校验入参
- 字段: planStartTime(Date), planEndTime(Date)

## 技术约束
- Java 8 语法, 禁用 var/record/sealed
- validation: javax.validation.constraints.*
- Swagger: io.swagger.annotations.* (v2)