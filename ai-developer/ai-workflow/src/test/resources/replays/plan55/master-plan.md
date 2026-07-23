# 特殊时段动火作业管控模块 — BladeX 后端开发方案

---

## 0. 模块身份标识

| 维度 | 值 |
|---|---|
| **实体名** | `SpecialPeriod` |
| **模块名** | `specialperiod` |
| **表名** | `blade_special_period` |
| **包路径** | `org.springblade.specialperiod` |
| **Nacos 服务名** | `blade-specialperiod` |
| **Feign Client value** | `blade-specialperiod` |
| **流程编码** | `special_period_hotwork_upgrade` |
| **统计页面** | `specialPeriodWorkStat` |
| **Nacos Namespace** | `blade_lxqt` |
| **Java/Spring Boot** | Java 1.8 / Spring Boot 2.x |
| **Validation / Swagger** | `javax.validation` / `io.swagger.annotations` (v2) |

---

## 1. 需求分析

### 1.1 业务目标
为动火作业在节假日、公休日、夜间等高风险时段提供**特殊时段配置中心**与**审批自动升级机制**，确保高风险时段作业被强制路由至更高级别审批流，并通过专属标识实现统计隔离与安全分析。

### 1.2 核心能力
1. **特殊时段配置 CRUD**：支持四种时段类型（节假日/公休日/夜间/自定义），支持日期范围 + 每日时间窗组合，支持跨天时间窗。
2. **时段状态机**：草稿 → 启用 ⇄ 停用（互逆）。
3. **时段匹配引擎**：动火作业提交时调用 Feign 接口 `hitSpecialPeriod(planStartTime, planEndTime)`，命中返回 `isSpecialPeriod=1` 及升级级别。
4. **审批升级路由**：命中后流程引擎强制走 `special_period_hotwork_upgrade` 流程，依据 `upgradeLevel` 动态增加审批节点。
5. **数据互斥校验**：同类型启用态时段不允许时间区间重叠。
6. **统计隔离**：`specialPeriodWorkStat` 页面基于 `isSpecialPeriod=1` 聚合，支持时段类型/部门维度筛选。
7. **Excel 批量导入导出**：年度节假日、公休日批量维护。

### 1.3 关键校验
- `periodName` 全局唯一（逻辑删除范围内）。
- `periodType` 为枚举值 1/2/3/4。
- 节假日/公休日：`startDate`/`endDate` 必填且 `startDate ≤ endDate`。
- 夜间：`startTime`/`endTime` 必填，支持 `endTime < startTime`（跨天）。
- 自定义：日期范围与时间范围均必填。
- `upgradeLevel ≥ 1`。
- 启用操作前执行**互斥重叠校验**。

### 1.4 状态机定义

```
特殊时段配置:
  [草稿 0] --启用--> [启用 1] --停用--> [停用 2] --启用--> [启用 1]
  [草稿 0] --作废--> (逻辑删除)

动火作业审批 (外部主表):
  [待提交] --提交--> [审批中(升级流)] --通过--> [审批通过]
                                  --驳回--> [审批驳回]
  [待提交/审批中] --作废--> [已作废]
```

---

## 2. 模块结构

```
blade-service-api/
└── blade-specialperiod-api/                    # API 模块
    ├── pom.xml                                 # parent: blade-service-api
    └── src/main/java/org/springblade/specialperiod/
        ├── entity/
        │   ├── SpecialPeriod.java
        │   ├── SpecialPeriodExcel.java         # Excel 导入导出实体
        │   └── SpecialPeriodWorkStatVO.java    # 统计聚合实体(可选)
        ├── vo/
        │   ├── SpecialPeriodVO.java            # 列表/详情返回
        │   ├── SpecialPeriodQVO.java           # 查询条件
        │   ├── SpecialPeriodIVO.java           # 新增入参
        │   ├── SpecialPeriodUVO.java           # 修改入参
        │   ├── SpecialPeriodEVO.java           # Excel 导出
        │   ├── SpecialPeriodHitVO.java         # 时段命中结果(给动火作业服务用)
        │   └── SpecialPeriodStatVO.java        # 统计聚合结果
        ├── dto/
        │   └── SpecialPeriodMatchDTO.java      # Feign 入参(计划作业时间)
        └── feign/
            ├── SpecialPeriodClient.java        # Feign 接口
            └── ISpecialPeriodClient.java       # Fallback 接口(被引用但不直接使用)

blade-service/
└── blade-specialperiod/                        # Service 模块
    ├── pom.xml                                 # parent: blade-service
    ├── src/main/java/org/springblade/specialperiod/
    │   ├── SpecialPeriodApplication.java
    │   ├── controller/
    │   │   ├── SpecialPeriodController.java
    │   │   └── SpecialPeriodStatController.java
    │   ├── service/
    │   │   ├── ISpecialPeriodService.java
    │   │   ├── impl/SpecialPeriodServiceImpl.java
    │   │   ├── ISpecialPeriodMatchService.java
    │   │   └── impl/SpecialPeriodMatchServiceImpl.java
    │   ├── mapper/
    │   │   ├── SpecialPeriodMapper.java
    │   │   └── SpecialPeriodMapper.xml
    │   ├── wrapper/
    │   │   └── SpecialPeriodWrapper.java
    │   └── config/
    │       └── SpecialPeriodConfig.java
    └── src/main/resources/
        ├── bootstrap.yml
        └── application-dev.yml
```

> 说明：参考项目 profile 指定 `voPackages={QVO=vo.qvo, VO=vo, EVO=vo.evo, UVO=vo.uvo, IVO=vo.ivo}`，因此 VO 按子包归位；profile 优先级高于通用约束。

### 2.1 父 pom 注册

```xml
<!-- blade-service-api/pom.xml -->
<module>blade-specialperiod-api</module>

<!-- blade-service/pom.xml -->
<module>blade-specialperiod</module>
```

### 2.2 Service 模块 pom.xml (核心依赖)

```xml
<dependencies>
    <dependency>
        <groupId>org.springblade</groupId>
        <artifactId>blade-specialperiod-api</artifactId>
        <version>${blade.project.version}</version>
    </dependency>
    <dependency><groupId>org.springblade</groupId><artifactId>blade-core-boot</artifactId></dependency>
    <dependency><groupId>org.springblade</groupId><artifactId>blade-starter-swagger</artifactId></dependency>
    <dependency><groupId>org.springblade</groupId><artifactId>blade-system-api</artifactId></dependency>
    <dependency><groupId>org.springblade</groupId><artifactId>blade-user-api</artifactId></dependency>
    <dependency><groupId>org.springblade</groupId><artifactId>blade-resource-api</artifactId></dependency>
    <dependency><groupId>org.springblade</groupId><artifactId>blade-dict-api</artifactId></dependency>
    <dependency><groupId>org.springblade</groupId><artifactId>blade-log</artifactId></dependency>
    <dependency><groupId>org.springblade</groupId><artifactId>blade-core-mybatis</artifactId></dependency>
    <dependency><groupId>org.springblade</groupId><artifactId>blade-starter-oss</artifactId></dependency>
</dependencies>
```

### 2.3 启动类

```java
package org.springblade.specialperiod;

import org.springblade.common.launch.Launcher;
import org.springblade.core.cloud.client.BladeCloudApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@BladeCloudApplication
public class SpecialPeriodApplication {
    public static void main(String[] args) {
        BladeApplication.run(SpecialPeriodApplication.class, args);
    }
}
```

### 2.4 bootstrap.yml / application-dev.yml

```yaml
# bootstrap.yml
server:
  port: 8970
spring:
  application:
    name: blade-specialperiod
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR}
        namespace: blade_lxqt
      config:
        server-addr: ${NACOS_ADDR}
        namespace: blade_lxqt
        file-extension: yml
```

```yaml
# application-dev.yml
mybatis-plus:
  mapper-locations: classpath:org/springblade/specialperiod/mapper/*.xml
blade:
  sql:
    log: true
```

---

## 3. 数据库 DDL

```sql
-- ========================
-- 特殊时段配置主表
-- ========================
CREATE TABLE `blade_special_period` (
  `id`            BIGINT(20)   NOT NULL                COMMENT '主键',
  `period_name`   VARCHAR(64)  NOT NULL                COMMENT '特殊时段名称(如国庆节、夜间时段)',
  `period_type`   INT(2)       NOT NULL                COMMENT '时段类型(1节假日 2公休日 3夜间 4自定义)',
  `start_date`    DATE         DEFAULT NULL            COMMENT '开始日期(节假日/公休日/自定义适用)',
  `end_date`      DATE         DEFAULT NULL            COMMENT '结束日期(节假日/公休日/自定义适用)',
  `start_time`    VARCHAR(8)   DEFAULT NULL            COMMENT '每日开始时间(HH:mm:ss,夜间/自定义适用)',
  `end_time`      VARCHAR(8)   DEFAULT NULL            COMMENT '每日结束时间(HH:mm:ss,夜间/自定义适用,可跨天)',
  `upgrade_level` INT(2)       NOT NULL DEFAULT 1      COMMENT '审批升级级别(提升N级)',
  `is_enable`     INT(2)       NOT NULL DEFAULT 0      COMMENT '是否启用(0否 1是)',
  `remark`        VARCHAR(500) DEFAULT NULL            COMMENT '备注说明',
  `create_user`   BIGINT(20)   DEFAULT NULL            COMMENT '创建人',
  `create_time`   DATETIME     DEFAULT NULL            COMMENT '创建时间',
  `update_user`   BIGINT(20)   DEFAULT NULL            COMMENT '更新人',
  `update_time`   DATETIME     DEFAULT NULL            COMMENT '更新时间',
  `status`        INT(2)       NOT NULL DEFAULT 0      COMMENT '状态(0草稿 1启用 2停用)',
  `is_deleted`    INT(2)       NOT NULL DEFAULT 0      COMMENT '是否已删除(0否 1是)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_period_name` (`period_name`, `is_deleted`),
  KEY `idx_period_type` (`period_type`),
  KEY `idx_is_enable`   (`is_enable`),
  KEY `idx_status`      (`status`),
  KEY `idx_date_range`  (`start_date`, `end_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='特殊时段配置表';

-- ========================
-- 动火作业主表新增字段(由动火作业模块执行, 此处仅声明)
-- ========================
-- ALTER TABLE blade_hotwork ADD COLUMN is_special_period INT(2) NOT NULL DEFAULT 0 COMMENT '是否特殊时段作业(0否 1是)' AFTER status;
-- ALTER TABLE blade_hotwork ADD KEY idx_is_special_period (is_special_period);
```

---

## 4. Entity 定义

### 4.1 SpecialPeriod (主实体)

```java
package org.springblade.specialperiod.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springblade.core.mp.base.BaseEntity;

import java.util.Date;

@Data
@TableName("blade_special_period")
@EqualsAndHashCode(callSuper = true)
@ApiModel(value = "SpecialPeriod对象", description = "特殊时段配置")
public class SpecialPeriod extends BaseEntity {

    @ApiModelProperty(value = "特殊时段名称")
    private String periodName;

    @ApiModelProperty(value = "时段类型(1节假日 2公休日 3夜间 4自定义)")
    private Integer periodType;

    @ApiModelProperty(value = "开始日期")
    private Date startDate;

    @ApiModelProperty(value = "结束日期")
    private Date endDate;

    @ApiModelProperty(value = "每日开始时间(HH:mm:ss)")
    private String startTime;

    @ApiModelProperty(value = "每日结束时间(HH:mm:ss)")
    private String endTime;

    @ApiModelProperty(value = "审批升级级别")
    private Integer upgradeLevel;

    @ApiModelProperty(value = "是否启用(0否 1是)")
    private Integer isEnable;

    @ApiModelProperty(value = "备注说明")
    private String remark;
}
```

> 注：`BaseEntity` 已含 `id / createUser / createTime / updateUser / updateTime / status / isDeleted`，不再重复声明。`status` 字段在此表中作为状态机载体（0草稿 1启用 2停用），与 `isEnable` 冗余但语义不同：`status` 描述生命周期，`isEnable` 作为查询过滤快捷位，由 Service 层保证同步。

### 4.2 SpecialPeriodExcel (Excel 实体)

```java
package org.springblade.specialperiod.entity;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.ContentStyle;
import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.util.Date;

@Data
@ColumnWidth(20)
@ContentStyle(horizontalAlignment = 1)
@ApiModel(value = "SpecialPeriodExcel对象", description = "特殊时段导入导出")
public class SpecialPeriodExcel {

    @ExcelProperty("时段名称")
    private String periodName;

    @ExcelProperty("时段类型(1节假日 2公休日 3夜间 4自定义)")
    private Integer periodType;

    @ExcelProperty("开始日期(yyyy-MM-dd)")
    private Date startDate;

    @ExcelProperty("结束日期(yyyy-MM-dd)")
    private Date endDate;

    @ExcelProperty("开始时间(HH:mm:ss)")
    private String startTime;

    @ExcelProperty("结束时间(HH:mm:ss)")
    private String endTime;

    @ExcelProperty("升级级别")
    private Integer upgradeLevel;

    @ExcelProperty("是否启用(0否 1是)")
    private Integer isEnable;

    @ExcelProperty("备注")
    private String remark;
}
```

---

## 5. VO 类型

### 5.1 SpecialPeriodVO (列表/详情返回)

```java
package org.springblade.specialperiod.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springblade.specialperiod.entity.SpecialPeriod;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel(value = "SpecialPeriodVO对象", description = "特殊时段配置展示")
public class SpecialPeriodVO extends SpecialPeriod {

    @ApiModelProperty(value = "时段类型描述")
    private String periodTypeDesc;

    @ApiModelProperty(value = "状态描述")
    private String statusDesc;

    @ApiModelProperty(value = "重叠冲突的时段ID列表(启用校验时返回)")
    private List<Long> conflictIds;
}
```

### 5.2 SpecialPeriodQVO (查询)

```java
package org.springblade.specialperiod.vo.qvo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel(value = "SpecialPeriodQVO对象", description = "特殊时段配置查询")
public class SpecialPeriodQVO {

    @ApiModelProperty(value = "时段名称(模糊)")
    private String periodName;

    @ApiModelProperty(value = "时段类型")
    private Integer periodType;

    @ApiModelProperty(value = "是否启用")
    private Integer isEnable;

    @ApiModelProperty(value = "状态")
    private Integer status;

    @ApiModelProperty(value = "开始日期(查询区间起)")
    private String startDateStart;

    @ApiModelProperty(value = "开始日期(查询区间止)")
    private String startDateEnd;
}
```

### 5.3 SpecialPeriodIVO (新增入参)

```java
package org.springblade.specialperiod.vo.ivo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.Date;

@Data
@ApiModel(value = "SpecialPeriodIVO对象", description = "特殊时段配置新增入参")
public class SpecialPeriodIVO {

    @NotBlank(message = "时段名称不能为空")
    @ApiModelProperty(value = "时段名称", required = true)
    private String periodName;

    @NotNull(message = "时段类型不能为空")
    @ApiModelProperty(value = "时段类型(1节假日 2公休日 3夜间 4自定义)", required = true)
    private Integer periodType;

    @ApiModelProperty(value = "开始日期")
    private Date startDate;

    @ApiModelProperty(value = "结束日期")
    private Date endDate;

    @ApiModelProperty(value = "开始时间(HH:mm:ss)")
    private String startTime;

    @ApiModelProperty(value = "结束时间(HH:mm:ss)")
    private String endTime;

    @NotNull(message = "审批升级级别不能为空")
    @Min(value = 1, message = "升级级别至少为1")
    @ApiModelProperty(value = "审批升级级别", required = true)
    private Integer upgradeLevel;

    @ApiModelProperty(value = "是否启用(0否 1是)")
    private Integer isEnable;

    @ApiModelProperty(value = "备注说明")
    private String remark;
}
```

### 5.4 SpecialPeriodUVO (修改入参)

```java
package org.springblade.specialperiod.vo.uvo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotNull;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel(value = "SpecialPeriodUVO对象", description = "特殊时段配置修改入参")
public class SpecialPeriodUVO extends SpecialPeriodIVO {

    @NotNull(message = "主键不能为空")
    @ApiModelProperty(value = "主键", required = true)
    private Long id;
}
```

### 5.5 SpecialPeriodEVO (Excel 导出)

```java
package org.springblade.specialperiod.vo.evo;

import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springblade.specialperiod.entity.SpecialPeriodExcel;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
@ApiModel(value = "SpecialPeriodEVO对象", description = "特殊时段导出包装")
public class SpecialPeriodEVO {

    @ApiModelProperty(value = "导出数据列表")
    private List<SpecialPeriodExcel> dataList;

    @ApiModelProperty(value = "文件名")
    private String fileName;
}
```

### 5.6 SpecialPeriodHitVO (匹配命中结果)

```java
package org.springblade.specialperiod.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel(value = "SpecialPeriodHitVO对象", description = "特殊时段命中结果(动火作业调用)")
public class SpecialPeriodHitVO implements Serializable {

    @ApiModelProperty(value = "是否命中(0否 1是)")
    private Integer isSpecialPeriod;

    @ApiModelProperty(value = "命中的特殊时段ID")
    private Long specialPeriodId;

    @ApiModelProperty(value = "命中的时段名称")
    private String periodName;

    @ApiModelProperty(value = "时段类型")
    private Integer periodType;

    @ApiModelProperty(value = "升级级别")
    private Integer upgradeLevel;

    @ApiModelProperty(value = "升级流程编码")
    private String flowCode;
}
```

### 5.7 SpecialPeriodMatchDTO (Feign 入参)

```java
package org.springblade.specialperiod.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@ApiModel(value = "SpecialPeriodMatchDTO对象", description = "时段匹配入参")
public class SpecialPeriodMatchDTO implements Serializable {

    @ApiModelProperty(value = "计划作业开始时间")
    private Date planStartTime;

    @ApiModelProperty(value = "计划作业结束时间")
    private Date planEndTime;

    @ApiModelProperty(value = "作业日期(用于日期维度匹配, 默认取 planStartTime 当天)")
    private Date planWorkDate;
}
```

### 5.8 SpecialPeriodStatVO (统计聚合)

```java
package org.springblade.specialperiod.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel(value = "SpecialPeriodStatVO对象", description = "特殊时段作业统计聚合")
public class SpecialPeriodStatVO implements Serializable {

    @ApiModelProperty(value = "时段类型")
    private Integer periodType;

    @ApiModelProperty(value = "时段类型描述")
    private String periodTypeDesc;

    @ApiModelProperty(value = "部门ID")
    private Long deptId;

    @ApiModelProperty(value = "部门名称")
    private String deptName;

    @ApiModelProperty(value = "作业总数")
    private Integer totalCount;

    @ApiModelProperty(value = "审批通过数")
    private Integer approvedCount;

    @ApiModelProperty(value = "审批驳回数")
    private Integer rejectedCount;

    @ApiModelProperty(value = "已作废数")
    private Integer cancelledCount;

    @ApiModelProperty(value = "审批通过率")
    private Double approvalRate;
}
```

---

## 6. Mapper 接口

### 6.1 Mapper 接口

```java
package org.springblade.specialperiod.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Param;
import org.springblade.specialperiod.entity.SpecialPeriod;
import org.springblade.specialperiod.vo.SpecialPeriodQVO;
import org.springblade.specialperiod.vo.SpecialPeriodStatVO;

import java.util.List;

public interface SpecialPeriodMapper extends BaseMapper<SpecialPeriod> {

    /**
     * 分页查询
     */
    IPage<SpecialPeriod> selectPageList(IPage<SpecialPeriod> page, @Param("qvo") SpecialPeriodQVO qvo);

    /**
     * 查询启用的同类型时段(用于互斥校验)
     */
    List<SpecialPeriod> selectEnabledByType(@Param("periodType") Integer periodType,
                                            @Param("excludeId") Long excludeId);

    /**
     * 查询所有启用状态的特殊时段(供匹配引擎使用)
     */
    List<SpecialPeriod> selectAllEnabled();

    /**
     * 统计聚合(动火作业主表关联查询, 跨库场景由调用方实现)
     */
    List<SpecialPeriodStatVO> selectStatByCondition(@Param("periodType") Integer periodType,
                                                    @Param("deptId") Long deptId,
                                                    @Param("startTime") String startTime,
                                                    @Param("endTime") String endTime);
}
```

### 6.2 Mapper XML

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="org.springblade.specialperiod.mapper.SpecialPeriodMapper">

    <resultMap id="specialPeriodMap" type="org.springblade.specialperiod.entity.SpecialPeriod">
        <id column="id" property="id"/>
        <result column="period_name" property="periodName"/>
        <result column="period_type" property="periodType"/>
        <result column="start_date" property="startDate"/>
        <result column="end_date" property="endDate"/>
        <result column="start_time" property="startTime"/>
        <result column="end_time" property="endTime"/>
        <result column="upgrade_level" property="upgradeLevel"/>
        <result column="is_enable" property="isEnable"/>
        <result column="remark" property="remark"/>
        <result column="create_user" property="createUser"/>
        <result column="create_time" property="createTime"/>
        <result column="update_user" property="updateUser"/>
        <result column="update_time" property="updateTime"/>
        <result column="status" property="status"/>
        <result column="is_deleted" property="isDeleted"/>
    </resultMap>

    <select id="selectPageList" resultMap="specialPeriodMap">
        SELECT * FROM blade_special_period
        WHERE is_deleted = 0
        <if test="qvo.periodName != null and qvo.periodName != ''">
            AND period_name LIKE CONCAT('%', #{qvo.periodName}, '%')
        </if>
        <if test="qvo.periodType != null">
            AND period_type = #{qvo.periodType}
        </if>
        <if test="qvo.isEnable != null">
            AND is_enable = #{qvo.isEnable}
        </if>
        <if test="qvo.status != null">
            AND status = #{qvo.status}
        </if>
        <if test="qvo.startDateStart != null and qvo.startDateStart != ''">
            AND start_date &gt;= #{qvo.startDateStart}
        </if>
        <if test="qvo.startDateEnd != null and qvo.startDateEnd != ''">
            AND start_date &lt;= #{qvo.startDateEnd}
        </if>
        ORDER BY create_time DESC
    </select>

    <select id="selectEnabledByType" resultMap="specialPeriodMap">
        SELECT * FROM blade_special_period
        WHERE is_deleted = 0
          AND is_enable = 1
          AND status = 1
          AND period_type = #{periodType}
        <if test="excludeId != null">
            AND id &lt;&gt; #{excludeId}
        </if>
    </select>

    <select id="selectAllEnabled" resultMap="specialPeriodMap">
        SELECT * FROM blade_special_period
        WHERE is_deleted = 0
          AND is_enable = 1
          AND status = 1
    </select>

    <select id="selectStatByCondition" resultType="org.springblade.specialperiod.vo.SpecialPeriodStatVO">
        SELECT
            h.is_special_period AS isSpecialPeriod,
            h.period_type       AS periodType,
            h.dept_id           AS deptId,
            COUNT(1)            AS totalCount,
            SUM(CASE WHEN h.status = 4 THEN 1 ELSE 0 END) AS approvedCount,
            SUM(CASE WHEN h.status = 5 THEN 1 ELSE 0 END) AS rejectedCount,
            SUM(CASE WHEN h.status = 6 THEN 1 ELSE 0 END) AS cancelledCount
        FROM blade_hotwork h
        WHERE h.is_deleted = 0
          AND h.is_special_period = 1
        <if test="periodType != null">
            AND h.period_type = #{periodType}
        </if>
        <if test="deptId != null">
            AND h.dept_id = #{deptId}
        </if>
        <if test="startTime != null and startTime != ''">
            AND h.create_time &gt;= #{startTime}
        </if>
        <if test="endTime != null and endTime != ''">
            AND h.create_time &lt;= #{endTime}
        </if>
        GROUP BY h.period_type, h.dept_id
    </select>
</mapper>
```

---

## 7. Service 层

### 7.1 ISpecialPeriodService

```java
package org.springblade.specialperiod.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springblade.core.mp.support.Query;
import org.springblade.specialperiod.entity.SpecialPeriod;
import org.springblade.specialperiod.vo.*;

import java.util.List;

public interface ISpecialPeriodService extends IService<SpecialPeriod> {

    /** 详情 */
    SpecialPeriodVO detail(Long id);

    /** 分页 */
    IPage<SpecialPeriodVO> page(Query query, SpecialPeriodQVO qvo);

    /** 新增 */
    Boolean save(SpecialPeriodIVO ivo);

    /** 修改 */
    Boolean update(SpecialPeriodUVO uvo);

    /** 删除 */
    Boolean remove(Long id);

    /** 启用 */
    Boolean enable(Long id);

    /** 停用 */
    Boolean disable(Long id);

    /** 互斥校验: 启用前判断与同类型已启用时段是否重叠 */
    List<Long> checkConflict(Long id, Integer periodType);

    /** 时段匹配 (供 Feign 内部调用) */
    SpecialPeriodHitVO matchPeriod(org.springblade.specialperiod.dto.SpecialPeriodMatchDTO dto);

    /** 统计聚合 */
    List<SpecialPeriodStatVO> stat(Integer periodType, Long deptId, String startTime, String endTime);

    /** Excel 导出 */
    List<SpecialPeriodExcel> exportData(SpecialPeriodQVO qvo);

    /** Excel 导入 */
    Boolean importData(List<SpecialPeriodExcel> list, Boolean isCovered);
}
```

### 7.2 SpecialPeriodServiceImpl

```java
package org.springblade.specialperiod.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.AllArgsConstructor;
import org.springblade.core.mp.support.Query;
import org.springblade.core.tool.utils.BeanUtil;
import org.springblade.core.tool.utils.DateUtil;
import org.springblade.core.tool.utils.Func;
import org.springblade.specialperiod.entity.SpecialPeriod;
import org.springblade.specialperiod.entity.SpecialPeriodExcel;
import org.springblade.specialperiod.mapper.SpecialPeriodMapper;
import org.springblade.specialperiod.service.ISpecialPeriodService;
import org.springblade.specialperiod.dto.SpecialPeriodMatchDTO;
import org.springblade.specialperiod.vo.*;
import org.springblade.specialperiod.wrapper.SpecialPeriodWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class SpecialPeriodServiceImpl
        extends ServiceImpl<SpecialPeriodMapper, SpecialPeriod>
        implements ISpecialPeriodService {

    private final SpecialPeriodMapper specialPeriodMapper;

    /* ============== 基础 CRUD ============== */

    @Override
    public SpecialPeriodVO detail(Long id) {
        SpecialPeriod entity = getById(id);
        if (entity == null) {
            return null;
        }
        return SpecialPeriodWrapper.build().entityVO(entity);
    }

    @Override
    public IPage<SpecialPeriodVO> page(Query query, SpecialPeriodQVO qvo) {
        IPage<SpecialPeriod> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(query.getCurrent(), query.getSize());
        IPage<SpecialPeriod> result = specialPeriodMapper.selectPageList(page, qvo);
        return SpecialPeriodWrapper.build().pageVO(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean save(SpecialPeriodIVO ivo) {
        checkNameUnique(null, ivo.getPeriodName());
        validateFields(ivo);
        SpecialPeriod entity = BeanUtil.copy(ivo, SpecialPeriod.class);
        // 默认草稿态
        if (entity.getStatus() == null) {
            entity.setStatus(0);
        }
        if (entity.getIsEnable() == null) {
            entity.setIsEnable(0);
        }
        // 若直接启用, 走启用校验
        if (Integer.valueOf(1).equals(entity.getIsEnable())) {
            entity.setStatus(1);
            List<Long> conflict = checkConflict(null, entity.getPeriodType());
            if (!conflict.isEmpty()) {
                throw new IllegalArgumentException("启用失败: 与同类型已启用时段重叠, 冲突ID=" + conflict);
            }
        }
        return save(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean update(SpecialPeriodUVO uvo) {
        SpecialPeriod exists = getById(uvo.getId());
        if (exists == null) {
            throw new IllegalArgumentException("记录不存在");
        }
        // 启用态不可直接修改, 需先停用
        if (Integer.valueOf(1).equals(exists.getStatus())) {
            throw new IllegalStateException("启用状态的时段不可修改, 请先停用");
        }
        checkNameUnique(uvo.getId(), uvo.getPeriodName());
        validateFields(uvo);
        SpecialPeriod entity = BeanUtil.copy(uvo, SpecialPeriod.class);
        return updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean remove(Long id) {
        SpecialPeriod entity = getById(id);
        if (entity != null && Integer.valueOf(1).equals(entity.getStatus())) {
            throw new IllegalStateException("启用状态的时段不可删除, 请先停用");
        }
        return removeById(id);
    }

    /* ============== 状态机: 启用 / 停用 ============== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean enable(Long id) {
        SpecialPeriod entity = getById(id);
        if (entity == null) {
            throw new IllegalArgumentException("记录不存在");
        }
        if (Integer.valueOf(1).equals(entity.getStatus())) {
            return Boolean.TRUE;
        }
        // 互斥校验
        List<Long> conflict = checkConflict(id, entity.getPeriodType());
        if (!conflict.isEmpty()) {
            throw new IllegalStateException(
                    "启用失败: 与同类型已启用时段时间区间重叠, 冲突ID=" + conflict);
        }
        entity.setStatus(1);
        entity.setIsEnable(1);
        return updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean disable(Long id) {
        SpecialPeriod entity = getById(id);
        if (entity == null) {
            throw new IllegalArgumentException("记录不存在");
        }
        if (!Integer.valueOf(1).equals(entity.getStatus())) {
            return Boolean.TRUE;
        }
        entity.setStatus(2);
        entity.setIsEnable(0);
        return updateById(entity);
    }

    /* ============== 互斥校验 ============== */

    @Override
    public List<Long> checkConflict(Long id, Integer periodType) {
        List<SpecialPeriod> enabledList = specialPeriodMapper.selectEnabledByType(periodType, id);
        SpecialPeriod current = id == null ? null : getById(id);
        if (current == null) {
            // 新增启用前调用, 无 current
            return Collections.emptyList();
        }
        List<Long> conflictIds = new ArrayList<>();
        for (SpecialPeriod other : enabledList) {
            if (isOverlap(current, other)) {
                conflictIds.add(other.getId());
            }
        }
        return conflictIds;
    }

    /* ============== 时段匹配引擎 ============== */

    @Override
    public SpecialPeriodHitVO matchPeriod(SpecialPeriodMatchDTO dto) {
        SpecialPeriodHitVO result = new SpecialPeriodHitVO();
        result.setIsSpecialPeriod(0);
        result.setFlowCode("special_period_hotwork_upgrade");

        if (dto == null || dto.getPlanStartTime() == null) {
            return result;
        }

        List<SpecialPeriod> enabledList = specialPeriodMapper.selectAllEnabled();
        if (enabledList.isEmpty()) {
            return result;
        }

        Date planStart = dto.getPlanStartTime();
        Date planEnd   = dto.getPlanEndTime() != null ? dto.getPlanEndTime() : planStart;

        for (SpecialPeriod sp : enabledList) {
            if (isHit(sp, planStart, planEnd)) {
                result.setIsSpecialPeriod(1);
                result.setSpecialPeriodId(sp.getId());
                result.setPeriodName(sp.getPeriodName());
                result.setPeriodType(sp.getPeriodType());
                result.setUpgradeLevel(sp.getUpgradeLevel());
                return result;
            }
        }
        return result;
    }

    /* ============== 统计 ============== */

    @Override
    public List<SpecialPeriodStatVO> stat(Integer periodType, Long deptId, String startTime, String endTime) {
        return specialPeriodMapper.selectStatByCondition(periodType, deptId, startTime, endTime);
    }

    /* ============== Excel ============== */

    @Override
    public List<SpecialPeriodExcel> exportData(SpecialPeriodQVO qvo) {
        IPage<SpecialPeriod> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, -1);
        List<SpecialPeriod> list = specialPeriodMapper.selectPageList(page, qvo).getRecords();
        return BeanUtil.copy(list, SpecialPeriodExcel.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean importData(List<SpecialPeriodExcel> list, Boolean isCovered) {
        if (Func.isEmpty(list)) {
            return Boolean.FALSE;
        }
        List<SpecialPeriod> entities = new ArrayList<>();
        for (SpecialPeriodExcel excel : list) {
            checkNameUnique(null, excel.getPeriodName());
            SpecialPeriod entity = BeanUtil.copy(excel, SpecialPeriod.class);
            entity.setStatus(Integer.valueOf(1).equals(excel.getIsEnable()) ? 1 : 0);
            entities.add(entity);
        }
        return saveBatch(entities);
    }

    /* ============== 私有方法 ============== */

    private void checkNameUnique(Long excludeId, String periodName) {
        LambdaQueryWrapper<SpecialPeriod> wrapper = new LambdaQueryWrapper<SpecialPeriod>()
                .eq(SpecialPeriod::getPeriodName, periodName)
                .eq(SpecialPeriod::getIsDeleted, 0);
        if (excludeId != null) {
            wrapper.ne(SpecialPeriod::getId, excludeId);
        }
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("时段名称已存在: " + periodName);
        }
    }

    private void validateFields(SpecialPeriodIVO ivo) {
        Integer type = ivo.getPeriodType();
        if (type == null || type < 1 || type > 4) {
            throw new IllegalArgumentException("时段类型非法");
        }
        // 节假日/公休日: 日期范围必填
        if (type == 1 || type == 2) {
            if (ivo.getStartDate() == null || ivo.getEndDate() == null) {
                throw new IllegalArgumentException("节假日/公休日类型必须填写日期范围");
            }
            if (ivo.getStartDate().after(ivo.getEndDate())) {
                throw new IllegalArgumentException("开始日期不能晚于结束日期");
            }
        }
        // 夜间: 时间范围必填
        if (type == 3) {
            if (ivo.getStartTime() == null || ivo.getEndTime() == null) {
                throw new IllegalArgumentException("夜间类型必须填写每日时间范围");
            }
        }
        // 自定义: 日期 + 时间均必填
        if (type == 4) {
            if (ivo.getStartDate() == null || ivo.getEndDate() == null
                    || ivo.getStartTime() == null || ivo.getEndTime() == null) {
                throw new IllegalArgumentException("自定义类型必须填写日期与时间范围");
            }
        }
    }

    /**
     * 判断两个特殊时段是否时间区间重叠 (用于互斥校验)
     */
    private boolean isOverlap(SpecialPeriod a, SpecialPeriod b) {
        boolean aUseDate = a.getStartDate() != null && a.getEndDate() != null;
        boolean bUseDate = b.getStartDate() != null && b.getEndDate() != null;
        boolean aUseTime = a.getStartTime() != null && a.getEndTime() != null;
        boolean bUseTime = b.getStartTime() != null && b.getEndTime() != null;

        // 日期维度
        if (aUseDate && bUseDate) {
            boolean dateOverlap = !a.getStartDate().after(b.getEndDate())
                    && !b.getStartDate().after(a.getEndDate());
            if (!dateOverlap) {
                return false;
            }
        }

        // 时间维度
        if (aUseTime && bUseTime) {
            return timeRangeOverlap(a.getStartTime(), a.getEndTime(), b.getStartTime(), b.getEndTime());
        }

        // 仅一方有时间维度, 视为该方覆盖全天, 视为重叠(在日期已通过前提下)
        return true;
    }

    /**
     * 时间段重叠判断, 支持跨天
     */
    private boolean timeRangeOverlap(String aStart, String aEnd, String bStart, String bEnd) {
        long aS = toSeconds(aStart);
        long aE = toSeconds(aEnd);
        long bS = toSeconds(bStart);
        long bE = toSeconds(bEnd);

        // 跨天: 转换为"补集"判断 -> 简化: 用区间集合表示
        List<long[]> aRanges = normalize(aS, aE);
        List<long[]> bRanges = normalize(bS, bE);

        for (long[] a : aRanges) {
            for (long[] b : bRanges) {
                if (a[0] < b[1] && b[0] < a[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<long[]> normalize(long s, long e) {
        List<long[]> list = new ArrayList<>();
        if (s <= e) {
            list.add(new long[]{s, e});
        } else {
            // 跨天: [s, 24:00:00) + [00:00:00, e]
            list.add(new long[]{s, 86400L});
            list.add(new long[]{0L, e});
        }
        return list;
    }

    private long toSeconds(String hhmmss) {
        String[] arr = hhmmss.split(":");
        long h = Long.parseLong(arr[0]);
        long m = Long.parseLong(arr[1]);
        long s = arr.length > 2 ? Long.parseLong(arr[2]) : 0L;
        return h * 3600 + m * 60 + s;
    }

    /**
     * 判断作业计划时间是否命中某一时段
     */
    private boolean isHit(SpecialPeriod sp, Date planStart, Date planEnd) {
        Integer type = sp.getPeriodType();

        // 日期维度匹配 (类型 1, 2, 4)
        if (type == 1 || type == 2 || type == 4) {
            if (sp.getStartDate() == null || sp.getEndDate() == null) {
                return false;
            }
            Date planDayStart = atStartOfDay(planStart);
            Date planDayEnd   = atStartOfDay(planEnd);
            // 作业日期范围与时段日期范围有交集
            if (planDayStart.after(sp.getEndDate()) || planDayEnd.before(sp.getStartDate())) {
                return false;
            }
        }

        // 时间维度匹配 (类型 3, 4)
        if (type == 3 || type == 4) {
            if (sp.getStartTime() == null || sp.getEndTime() == null) {
                return false;
            }
            // 取计划开始时间的时分秒部分
            long pStart = toSeconds(toHms(planStart));
            long pEnd   = toSeconds(toHms(planEnd));
            long sS = toSeconds(sp.getStartTime());
            long sE = toSeconds(sp.getEndTime());

            List<long[]> sRanges = normalize(sS, sE);
            // 起止任一落入时段即命中
            return inAnyRange(pStart, sRanges) || inAnyRange(pEnd, sRanges);
        }

        return true;
    }

    private boolean inAnyRange(long t, List<long[]> ranges) {
        for (long[] r : ranges) {
            if (t >= r[0] && t <= r[1]) {
                return true;
            }
        }
        return false;
    }

    private String toHms(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        return sdf.format(date);
    }

    private Date atStartOfDay(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }
}
```

---

## 8. Controller 端点

### 8.1 SpecialPeriodController

```java
package org.springblade.specialperiod.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.AllArgsConstructor;
import org.springblade.core.boot.ctrl.BladeController;
import org.springblade.core.mp.support.Query;
import org.springblade.core.tool.api.R;
import org.springblade.core.tool.utils.DateUtil;
import org.springblade.specialperiod.entity.SpecialPeriodExcel;
import org.springblade.specialperiod.service.ISpecialPeriodService;
import org.springblade.specialperiod.vo.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.util.List;

@RestController
@AllArgsConstructor
@RequestMapping("/specialperiod")
@Api(value = "特殊时段配置", tags = "特殊时段配置")
public class SpecialPeriodController extends BladeController {

    private final ISpecialPeriodService specialPeriodService;

    @GetMapping("/detail")
    @ApiOperation(value = "详情")
    public R<SpecialPeriodVO> detail(@RequestParam Long id) {
        return R.data(specialPeriodService.detail(id));
    }

    @GetMapping("/list")
    @ApiOperation(value = "分页列表")
    public R<IPage<SpecialPeriodVO>> list(Query query, SpecialPeriodQVO qvo) {
        return R.data(specialPeriodService.page(query, qvo));
    }

    @PostMapping("/save")
    @ApiOperation(value = "新增")
    public R<Boolean> save(@Valid @RequestBody SpecialPeriodIVO ivo) {
        return R.status(specialPeriodService.save(ivo));
    }

    @PostMapping("/update")
    @ApiOperation(value = "修改")
    public R<Boolean> update(@Valid @RequestBody SpecialPeriodUVO uvo) {
        return R.status(specialPeriodService.update(uvo));
    }

    @PostMapping("/remove")
    @ApiOperation(value = "删除")
    public R<Boolean> remove(@RequestParam Long id) {
        return R.status(specialPeriodService.remove(id));
    }

    /* ===== 状态机 ===== */
    @PostMapping("/enable")
    @ApiOperation(value = "启用")
    public R<Boolean> enable(@RequestParam Long id) {
        return R.status(specialPeriodService.enable(id));
    }

    @PostMapping("/disable")
    @ApiOperation(value = "停用")
    public R<Boolean> disable(@RequestParam Long id) {
        return R.status(specialPeriodService.disable(id));
    }

    @GetMapping("/check-conflict")
    @ApiOperation(value = "互斥冲突校验(启用前调用)")
    public R<List<Long>> checkConflict(@RequestParam Long id,
                                       @RequestParam Integer periodType) {
        return R.data(specialPeriodService.checkConflict(id, periodType));
    }

    /* ===== 内部匹配接口(供 Feign 复用) ===== */
    @PostMapping("/match")
    @ApiOperation(value = "时段匹配(内部接口)")
    public R<SpecialPeriodHitVO> match(@RequestBody org.springblade.specialperiod.dto.SpecialPeriodMatchDTO dto) {
        return R.data(specialPeriodService.matchPeriod(dto));
    }

    /* ===== Excel ===== */
    @GetMapping("/export")
    @ApiOperation(value = "导出")
    public void export(SpecialPeriodQVO qvo, HttpServletResponse response) {
        List<SpecialPeriodExcel> list = specialPeriodService.exportData(qvo);
        org.springblade.specialperiod.utils.ExcelUtil.export(response, "特殊时段配置_" + DateUtil.time(), list);
    }

    @PostMapping("/import")
    @ApiOperation(value = "导入")
    public R<Boolean> importData(@RequestParam MultipartFile file,
                                 @RequestParam(defaultValue = "false") Boolean isCovered) {
        List<SpecialPeriodExcel> list = org.springblade.specialperiod.utils.ExcelUtil.read(file);
        return R.status(specialPeriodService.importData(list, isCovered));
    }

    /* ===== 模板下载 ===== */
    @GetMapping("/template")
    @ApiOperation(value = "导入模板下载")
    public void template(HttpServletResponse response) {
        org.springblade.specialperiod.utils.ExcelUtil.export(response, "特殊时段导入模板", SpecialPeriodExcel.class);
    }
}
```

### 8.2 SpecialPeriodStatController

```java
package org.springblade.specialperiod.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import org.springblade.core.boot.ctrl.BladeController;
import org.springblade.core.tool.api.R;
import org.springblade.specialperiod.service.ISpecialPeriodService;
import org.springblade.specialperiod.vo.SpecialPeriodStatVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@AllArgsConstructor
@RequestMapping("/specialperiod/stat")
@Api(value = "特殊时段作业统计", tags = "特殊时段作业统计")
public class SpecialPeriodStatController extends BladeController {

    private final ISpecialPeriodService specialPeriodService;

    @GetMapping("/aggregate")
    @ApiOperation(value = "specialPeriodWorkStat 页面聚合查询")
    public R<List<SpecialPeriodStatVO>> aggregate(@RequestParam(required = false) Integer periodType,
                                                   @RequestParam(required = false) Long deptId,
                                                   @RequestParam(required = false) String startTime,
                                                   @RequestParam(required = false) String endTime) {
        return R.data(specialPeriodService.stat(periodType, deptId, startTime, endTime));
    }
}
```

---

## 9. Wrapper 转换类

```java
package org.springblade.specialperiod.wrapper;

import org.springblade.core.mp.support.BaseEntityWrapper;
import org.springblade.core.tool.utils.BeanUtil;
import org.springblade.specialperiod.entity.SpecialPeriod;
import org.springblade.specialperiod.vo.SpecialPeriodIVO;
import org.springblade.specialperiod.vo.SpecialPeriodUVO;
import org.springblade.specialperiod.vo.SpecialPeriodVO;

import java.util.Objects;

public class SpecialPeriodWrapper extends BaseEntityWrapper<SpecialPeriod, SpecialPeriodVO> {

    public static SpecialPeriodWrapper build() {
        return new SpecialPeriodWrapper();
    }

    @Override
    public SpecialPeriodVO entityVO(SpecialPeriod entity) {
        SpecialPeriodVO vo = Objects.requireNonNull(BeanUtil.copy(entity, SpecialPeriodVO.class));
        vo.setPeriodTypeDesc(periodTypeDesc(entity.getPeriodType()));
        vo.setStatusDesc(statusDesc(entity.getStatus()));
        return vo;
    }

    /** IVO -> Entity */
    public SpecialPeriod entity(SpecialPeriodIVO ivo) {
        return BeanUtil.copy(ivo, SpecialPeriod.class);
    }

    /** UVO -> Entity */
    public SpecialPeriod entity(SpecialPeriodUVO uvo) {
        return BeanUtil.copy(uvo, SpecialPeriod.class);
    }

    private String periodTypeDesc(Integer type) {
        if (type == null) return "";
        switch (type) {
            case 1: return "节假日";
            case 2: return "公休日";
            case 3: return "夜间";
            case 4: return "自定义";
            default: return "未知";
        }
    }

    private String statusDesc(Integer status) {
        if (status == null) return "";
        switch (status) {
            case 0: return "草稿";
            case 1: return "启用";
            case 2: return "停用";
            default: return "未知";
        }
    }
}
```

---

## 10. Excel 导入导出工具类

```java
package org.springblade.specialperiod.utils;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.read.listener.ReadListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ExcelUtil {

    public static <T> void export(HttpServletResponse response, String fileName, List<T> list) {
        try {
            response.setContentType("application/vnd.ms-excel");
            response.setCharacterEncoding("UTF-8");
            String encoded = URLEncoder.encode(fileName, "UTF-8").replaceAll("\\+", "%20");
            response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + encoded + ".xlsx");
            EasyExcel.write(response.getOutputStream())
                     .head(list.get(0).getClass())
                     .sheet("sheet1")
                     .doWrite(list);
        } catch (IOException e) {
            log.error("导出异常", e);
        }
    }

    public static <T> void export(HttpServletResponse response, String fileName, Class<T> clazz) {
        try {
            response.setContentType("application/vnd.ms-excel");
            response.setCharacterEncoding("UTF-8");
            String encoded = URLEncoder.encode(fileName, "UTF-8").replaceAll("\\+", "%20");
            response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + encoded + ".xlsx");
            EasyExcel.write(response.getOutputStream())
                     .head(clazz)
                     .sheet("sheet1")
                     .doWrite(new ArrayList<>());
        } catch (IOException e) {
            log.error("模板导出异常", e);
        }
    }

    public static <T> List<T> read(MultipartFile file) {
        List<T> result = new ArrayList<>();
        try (InputStream is = file.getInputStream()) {
            // 由于泛型擦除, 调用方需自行指定类型, 这里仅提供通用骨架
            // 实际使用: EasyExcel.read(is, SpecialPeriodExcel.class, new ReadListener<SpecialPeriodExcel>() {...}).sheet().doRead();
        } catch (IOException e) {
            log.error("导入异常", e);
        }
        return result;
    }
}
```

> 实际项目中应针对 `SpecialPeriodExcel` 写具体的 `ReadListener` 实现，此处仅展示工具骨架。

---

## 11. Feign 客户端接口

### 11.1 ISpecialPeriodClient (Fallback 接口定义)

```java
package org.springblade.specialperiod.feign;

import org.springblade.core.tool.api.R;
import org.springblade.specialperiod.dto.SpecialPeriodMatchDTO;
import org.springblade.specialperiod.vo.SpecialPeriodHitVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(value = "blade-specialperiod")
public interface ISpecialPeriodClient {

    /**
     * 时段匹配: 供动火作业服务调用, 判断计划作业时间是否命中启用态特殊时段
     */
    @PostMapping("/specialperiod/match")
    R<SpecialPeriodHitVO> matchPeriod(@RequestBody SpecialPeriodMatchDTO dto);
}
```

### 11.2 SpecialPeriodClient (Fallback 实现, 但 Feign 注解不引用 fallback 类)

```java
package org.springblade.specialperiod.feign;

import lombok.extern.slf4j.Slf4j;
import org.springblade.core.tool.api.R;
import org.springblade.specialperiod.dto.SpecialPeriodMatchDTO;
import org.springblade.specialperiod.vo.SpecialPeriodHitVO;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SpecialPeriodClient implements ISpecialPeriodClient {

    @Override
    public R<SpecialPeriodHitVO> matchPeriod(SpecialPeriodMatchDTO dto) {
        log.error("[Feign Fallback] blade-specialperiod matchPeriod 调用失败, 默认返回未命中");
        SpecialPeriodHitVO fallback = new SpecialPeriodHitVO();
        fallback.setIsSpecialPeriod(0);
        return R.data(fallback);
    }
}
```

> 动火作业服务侧依赖 `blade-specialperiod-api`，注入 `ISpecialPeriodClient` 即可；命中后由动火作业服务将 `isSpecialPeriod=1` 写入主表，并触发 `special_period_hotwork_upgrade` 流程。

---

## 12. 关键业务逻辑与异常处理

### 12.1 状态机实现要点

| 当前状态 | 操作 | 目标状态 | 校验 | 副作用 |
|---|---|---|---|---|
| 草稿(0) | 启用 | 启用(1) | 互斥校验通过 | `is_enable=1` |
| 启用(1) | 停用 | 停用(2) | 无 | `is_enable=0` |
| 停用(2) | 启用 | 启用(1) | 互斥校验通过 | `is_enable=1` |
| 草稿(0) | 修改 | 草稿(0) | 名称唯一 + 字段校验 | — |
| 启用(1) | 修改 | (拒绝) | — | 抛 `IllegalStateException` |
| 启用(1) | 删除 | (拒绝) | — | 抛 `IllegalStateException` |

### 12.2 时段匹配算法

1. 拉取所有 `is_enable=1 AND status=1` 的时段。
2. 按类型分别判定：
   - **类型 1/2 (节假日/公休日)**：日期维度匹配 — 计划作业日期范围与 `[startDate, endDate]` 求交集。
   - **类型 3 (夜间)**：时间维度匹配 — 计划作业起止的 `HH:mm:ss` 任一落入时段（**支持跨天**：`endTime < startTime` 时拆分为 `[s, 24:00)` 和 `[00:00, e]` 两段判断）。
   - **类型 4 (自定义)**：日期维度 + 时间维度同时满足。
3. 命中即返回 `SpecialPeriodHitVO`，包含 `specialPeriodId` / `periodType` / `upgradeLevel` / `flowCode=special_period_hotwork_upgrade`。

### 12.3 互斥校验算法

启用前调用 `checkConflict(id, periodType)`：
1. 查询同类型、已启用、非自身的时段列表。
2. 对每个时段执行 `isOverlap`：
   - 日期维度：区间不相交则直接 false。
   - 时间维度：调用 `timeRangeOverlap`（支持跨天，通过 `normalize` 拆分多段后逐段比较）。
3. 返回冲突 ID 列表，非空则抛异常阻断启用。

### 12.4 审批升级路由

- 动火作业服务在 `提交` 阶段调用 `ISpecialPeriodClient#matchPeriod`。
- 若 `isSpecialPeriod=1`：
  1. 主表 `is_special_period = 1`。
  2. 拦截常规审批流，路由至 `flowCode=special_period_hotwork_upgrade`。
  3. 流程引擎依据 `upgradeLevel` 在审批链中插入对应数量的高级别节点（如分管领导、安全总监）。
- 升级审批人由流程引擎通过 Feign 调用 `blade-user-api` 权限服务获取。

### 12.5 统计隔离

- `specialPeriodWorkStat` 接口在 SQL 中以 `h.is_special_period = 1` 为强过滤条件。
- 支持维度：`periodType`、`deptId`、时间范围。
- 跨表关联字段 `blade_hotwork.period_type` 需在动火作业提交命中时段时一并写入。

### 12.6 异常处理策略

| 异常场景 | 处理方式 |
|---|---|
| 名称重复 | `IllegalArgumentException` → R.fail("时段名称已存在") |
| 字段校验失败 | `IllegalArgumentException` → R.fail(具体消息) |
| 启用态修改/删除 | `IllegalStateException` → R.fail("启用状态不可操作") |
| 互斥冲突 | `IllegalStateException` → R.fail("与已启用时段重叠, 冲突ID=[...]") |
| Feign 调用失败 | Fallback 返回 `isSpecialPeriod=0`（**容错降级**：宁可漏升级也不阻塞业务） |
| Excel 导入名称冲突 | 单条跳过 + 记录错误日志，事务回滚整批 |

### 12.7 事务边界

- `save / update / remove / enable / disable / importData` 均加 `@Transactional(rollbackFor = Exception.class)`。
- `matchPeriod` 不需要事务（只读）。
- `enable` 操作中互斥校验与状态更新必须在同一事务内，避免并发启用导致脏数据。

---

## 13. 实现顺序

| 阶段 | 步骤 | 产出 |
|---|---|---|
| **1. 数据库** | 执行 `blade_special_period` DDL；通知动火作业模块执行 `ALTER TABLE blade_hotwork ADD COLUMN is_special_period` | DDL 脚本 |
| **2. API 模块** | 创建 `blade-specialperiod-api`：pom / Entity / VO(QVO/IVO/UVO/VO/EVO/Hit/Stat) / DTO / Feign 接口 | 编译通过 |
| **3. Service 模块骨架** | 创建 `blade-specialperiod`：pom / Application / bootstrap.yml / application-dev.yml | 服务可启动 |
| **4. Mapper** | `SpecialPeriodMapper` 接口 + XML（5 个查询方法） | MyBatis 绑定 |
| **5. Service 层** | `ISpecialPeriodService` + `SpecialPeriodServiceImpl`，先实现 CRUD，再实现状态机，最后实现匹配引擎 | 单测通过 |
| **6. Wrapper** | `SpecialPeriodWrapper`（entityVO / entity(IVO) / entity(UVO)） | 转换可用 |
| **7. Controller** | `SpecialPeriodController`（7 个端点 + Excel）+ `SpecialPeriodStatController` | 接口联调 |
| **8. Excel 工具** | `ExcelUtil` + `SpecialPeriodExcel` 完整 ReadListener | 导入导出可用 |
| **9. Feign** | `ISpecialPeriodClient` + `SpecialPeriodClient` fallback；动火作业服务侧接入调用 | 跨服务联调 |
| **10. 流程接入** | 在流程引擎配置 `special_period_hotwork_upgrade` 流程定义；动火作业提交时路由 | 端到端流程 |
| **11. 统计页面** | `specialPeriodWorkStat` 接口联调；前端按 `periodType` / `deptId` 维度筛选 | 统计可用 |
| **12. 集成测试** | ① 节假日跨天匹配 ② 夜间跨天匹配 ③ 互斥冲突启用 ④ 启用→停用→启用 ⑤ 升级流路由 ⑥ Feign 容错降级 | 全量用例通过 |

---

> **关键设计取舍**
> 1. `status`（状态机）与 `is_enable`（启用位）冗余：前者承载生命周期、后者作为查询过滤快捷位，由 Service 保证同步，避免状态机字段被滥用为查询条件。
> 2. Feign Fallback 选择**降级未命中**而非抛错：动火作业审批升级是安全增强，非业务硬阻塞；匹配服务故障时让作业按常规流程走，避免业务大面积阻塞。
> 3. 跨天时间匹配采用"区间拆分"算法：将 `[22:00, 06:00]` 归一化为 `[22:00, 24:00) ∪ [00:00, 06:00]`，避免 `endTime < startTime` 的特殊判断逻辑散落各处。
> 4. 启用态禁止修改/删除：避免修改后与已命中该时段的进行中作业数据不一致。