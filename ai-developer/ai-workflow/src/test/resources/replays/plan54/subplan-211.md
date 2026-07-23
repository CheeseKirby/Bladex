# Mapper 与 Service - 数据访问层 + 业务服务层

## 实体名: SpecialPeriod
## 模块名: specialperiod
## 表名: blade_special_period
## 包路径: org.springblade.specialperiod
## 模块结构: blade-service/blade-specialperiod

## 目标层: Service 模块 (blade-specialperiod)

## 父 pom 注册
```xml
<module>blade-service/blade-specialperiod</module>
```
Service 模块 pom parent 为 blade-service，依赖: blade-service, blade-core-boot, blade-starter-swagger, blade-system-api, blade-user-api, blade-resource-api, blade-dict-api, blade-log, blade-specialperiod-api

## 配置文件
- bootstrap.yml: server.port=8210, spring.application.name=blade-specialperiod, nacos namespace=blade_lxqt
- application-dev.yml: datasource 指向 blade_special_period 库, mybatis-plus mapper-locations

## 启动类: org.springblade.specialperiod.SpecialPeriodApplication
```java
@SpringCloudApplication
public class SpecialPeriodApplication {
    public static void main(String[] args) {
        BladeApplication.run(AppConstant.APPLICATION_DESK_NAME, SpecialPeriodApplication.class, args);
    }
}
```

## 1. Mapper 接口: org.springblade.specialperiod.mapper.SpecialPeriodMapper
- 继承 `BaseMapper<SpecialPeriod>`
- 方法:
  - `selectSpecialPeriodPage(IPage<SpecialPeriodVO> page, @Param("qvo") SpecialPeriodQVO qvo)` - 自定义分页
  - `countConflictPeriod(@Param("id") Long id, @Param("periodType") Integer periodType, @Param("startDate") String startDate, @Param("endDate") String endDate)` - 冲突校验
  - `listEffectivePeriods()` - 查询生效中配置
  - `statByPeriodType(@Param("qvo") SpecialPeriodQVO qvo)` - 按类型统计

## 2. Mapper XML: src/main/resources/mapper/SpecialPeriodMapper.xml
- namespace: org.springblade.specialperiod.mapper.SpecialPeriodMapper
- resultMap: specialPeriodVOMap 映射全字段(驼峰转下划线)
- selectSpecialPeriodPage: 支持名称模糊/类型/状态/升级标记/日期区间筛选
- countConflictPeriod: WHERE is_deleted=0 AND period_type=#{periodType} AND config_status!=2 AND start_date<=#{endDate} AND end_date>=#{startDate}，排除自身 id
- listEffectivePeriods: WHERE is_deleted=0 AND config_status=1 AND CURDATE() BETWEEN start_date AND end_date
- statByPeriodType: GROUP BY period_type, SUM(is_upgrade=1) 统计

## 3. IService: org.springblade.specialperiod.service.ISpecialPeriodService
- 继承 `BaseService<SpecialPeriod>`
- 方法: selectPage, detail, save(IVO), update(UVO), remove, changeStatus, matchEffectivePeriod(HotworkMatchDTO), triggerUpgradeFlow(SpecialPeriod, HotworkMatchDTO), statByPeriodType, importSpecialPeriod(MultipartFile), exportSpecialPeriod(SpecialPeriodQVO, HttpServletResponse)

## 4. ServiceImpl: org.springblade.specialperiod.service.impl.SpecialPeriodServiceImpl
- 继承 `BaseServiceImpl<SpecialPeriodMapper, SpecialPeriod>`
- @Service + @AllArgsConstructor
- save: validateDateRange + validatePeriodTypeRule + checkConflict + 默认值设置(configStatus=0, isUpgrade=1)
- update: 存在性校验 + 已失效不可修改 + 冲突校验
- remove: 生效中不可删除
- changeStatus: 单向流转校验(0->1->2), targetStatus != current+1 则拒绝
- matchEffectivePeriod: 查生效配置 -> 日期命中 -> 夜间(type=3)时间命中(含跨天 22:00~06:00)
- triggerUpgradeFlow: 标记 isUpgrade=1 + TODO 工作流启动
- statByPeriodType: 委托 mapper
- 所有校验异常抛 ServiceException
- @Transactional(rollbackFor=Exception.class) 标注 save/update/changeStatus/triggerUpgradeFlow

## 技术约束
- Java 8, javax.validation, Swagger v2
- 依赖 SpecialPeriodWrapper.build() 进行 VO 转换(Wrapper 在 sub_4 中定义, 此处引用)
- importSpecialPeriod/exportSpecialPeriod 委托 Excel 工具类(sub_5 中定义)