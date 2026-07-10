# BladeX 4.1.0 数据层开发指南

> 基于 BladeX 4.1.0.RELEASE 框架源码验证。适用于 Agent 后端开发参考。
>
> **版本适配**: 本文档示例用 Swagger v3(@Schema) + jakarta.*。若参考项目是旧版(Java 8 / Swagger v2 / javax.*),
> **必须按参考项目实际版本生成**:Swagger 用 @ApiModel/@ApiModelProperty,注解用 javax.*。参考项目版本约束优先级高于本文档。

## 概述

BladeX 数据层基于 **MyBatis-Plus** + **Druid** 连接池。实体定义在 API 模块，Mapper 定义在服务模块。所有查询使用 MyBatis-Plus 的 Java API（Lambda 表达式），不使用 XML 映射文件（除非复杂报表查询）。

---

## Entity 实体类

### 模式一：extends BaseEntity（推荐，主流模式）

`BaseEntity` 由 BladeX 框架提供（`org.springblade.core.mp.base.BaseEntity`），内置以下字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 主键，雪花算法自动生成 |
| `createUser` | Long | 创建人 ID |
| `createTime` | Date | 创建时间 |
| `updateUser` | Long | 修改人 ID |
| `updateTime` | Date | 修改时间 |
| `status` | Integer | 业务状态（1=正常, 2=禁用等） |
| `isDeleted` | Integer | 逻辑删除标记（0=未删除, 1=已删除） |

**代码模板**：

```java
package org.springblade.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springblade.core.mp.base.BaseEntity;

@Data
@TableName("blade_order")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Order对象")
public class Order extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单编号")
    private String orderNo;

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "金额")
    private java.math.BigDecimal amount;

    @Schema(description = "备注")
    private String remark;
}
```

来源：`blade-system-api/pojo/entity/Tenant.java`、`Param.java`、`blade-safety-control-api/entity/Banner.java`

### 模式二：implements Serializable 显式字段（旧模块兼容）

部分旧模块（如 blade-dict-api）不继承 `BaseEntity`，手动声明所有字段：

```java
package org.springblade.system.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("blade_dict")
@Schema(description = "Dict对象")
public class Dict implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "父主键")
    private Long parentId;

    @Schema(description = "字典码")
    private String code;

    @Schema(description = "字典值")
    private String dictKey;

    @Schema(description = "字典名称")
    private String dictValue;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "是否已封存")
    private Integer isSealed;

    @Schema(description = "业务状态")
    private Integer status;

    @TableLogic
    @Schema(description = "是否已删除")
    private Integer isDeleted;
}
```

来源：`blade-dict-api/pojo/entity/Dict.java`

> **推荐**：新模块统一使用模式一 `extends BaseEntity`，减少样板代码。

### Entity 常用注解

| 注解 | 位置 | 说明 |
|------|------|------|
| `@TableName("blade_xxx")` | 类 | 指定数据库表名，命名规范 `blade_` 前缀 + 蛇形 |
| `@TableId(value = "id", type = IdType.ASSIGN_ID)` | 字段 | 主键，雪花算法。模式二需要显式声明，模式一已继承 |
| `@TableLogic` | 字段 | 逻辑删除标记。模式二需要显式声明，模式一已继承 |
| `@TableField(exist = false)` | 字段 | 标记非数据库字段（用于查询参数或临时计算值） |
| `@JsonSerialize(using = ToStringSerializer.class)` | Long 字段 | 前端 JS 精度安全，Long 序列化为 String |
| `@JsonSerialize(nullsUsing = NullSerializer.class)` | Long 字段 | null 时也序列化（而非忽略），通常和 ToStringSerializer 组合使用 |
| `@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")` | Date 字段 | 日期格式化 |
| `@EqualsAndHashCode(callSuper = true)` | 类 | 继承 BaseEntity 时必须，确保 equals/hashCode 包含父类字段 |

### Swagger 注解选择

| 模块 | 注解风格 | 示例 |
|------|---------|------|
| blade-system-api / blade-dict-api | OpenAPI 3 | `@Schema(description = "...")` |
| blade-safety-control-api | Swagger 2 | `@ApiModel(value = "...", description = "...")` + `@ApiModelProperty(value = "...")` |

> **推荐**：新模块使用 OpenAPI 3 (`@Schema`)，与 BladeX 4.1.0 整体风格一致。

### 表名命名规范

- 框架表：`blade_` 前缀 + 蛇形命名，如 `blade_user`、`blade_role`、`blade_dict`
- 业务表：`safety_` 前缀或自定义前缀 + 蛇形命名，如 `safety_banner`
- 关联表：两张表名用下划线连接，如 `blade_role_menu`、`blade_user_dept`

---

## VO 类型与使用场景

> **Hard Rule（字段一致性，违反会导致 BeanUtil.copy 丢字段、CRUD 数据流断裂，且被生成器跨文件自检拦截并强制重生成）**：
> - **B1 字段名一致**：VO/IVO/UVO 的业务字段名必须与 Entity 逐字段同名。Entity 用 `periodName`，VO/IVO/UVO 也用 `periodName`，不要改成 `name`/`type`/`enabled`。推荐 `VO extends Entity` 仅追加展示字段；若 `implements Serializable`，字段必须与 Entity 同名。
> - **B2 字段类型一致**：IVO/UVO/VO 的字段类型必须与 Entity 对应字段一致。Entity 是 `Date` 就用 `Date`，不要用 `LocalDate`；Entity 是 `Integer` 就用 `Integer`。日期类型统一用 `Date`（与目标项目既有约定一致）。
> - **B3 字段有据可循**：VO 每个字段必须来自：Entity 字段、或 Base 书写字段（id/createUser/createTime/updateUser/updateTime/status/isDeleted/tenantId/createDept）、或明确的展示衍生字段（后缀 `Name`/`StatusName`/`TypeName` 等，如 `customerTypeName`）。**禁止凭空新增业务字段**（如 `weekDays`/`priority`）；如需新增表列必须同步 DDL 与 Entity。
> - QVO 是查询对象，只含可筛选字段 + 范围字段（如 `startDateStart`/`startDateEnd`），不强制与 Entity 字段一致。

BladeX 定义了 5 种 VO 类型，各司其职：

| 类型 | 后缀 | 用途 | 位置 |
|------|------|------|------|
| **VO** | `XxxVO` | 输出视图对象，返回给前端 | `vo/XxxVO.java` 或 `vo/` |
| **QVO** | `XxxQVO` | 查询参数对象，接收前端查询条件 | `vo/qvo/XxxQVO.java` 或 `vo/` |
| **IVO** | `XxxIVO` | 新增参数对象，带 `@NotBlank`/`@NotNull` 校验 | `vo/ivo/XxxIVO.java` |
| **UVO** | `XxxUVO` | 修改参数对象，含 `id` + 校验注解 | `vo/uvo/XxxUVO.java` |
| **EVO** | `XxxExcel` | Excel 导入导出对象，含 `@ExcelProperty` | `excel/XxxExcel.java`（服务模块） |

### 子包惯例

**方式一：平铺在 vo/ 下**（blade-system-api）

```
vo/
├── UserVO.java
├── RoleVO.java
└── DeptVO.java
```

**方式二：子包分类**（blade-safety-control-api）

```
vo/
├── BannerVO.java
├── ivo/
│   └── BannerIVO.java
├── qvo/
│   └── BannerQVO.java
└── uvo/
    └── BannerUVO.java
```

> 两种方式均可。子包方式文件多时更清晰，平铺方式适合 VO 类型少的模块。

### VO（输出视图对象）

```java
package org.springblade.order.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.NullSerializer;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "OrderVO对象")
public class OrderVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonSerialize(nullsUsing = NullSerializer.class, using = ToStringSerializer.class)
    @Schema(description = "主键id")
    private Long id;

    @Schema(description = "订单编号")
    private String orderNo;

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "金额")
    private java.math.BigDecimal amount;

    @Schema(description = "业务状态")
    private String status;  // 注意：VO 中 status 是 String 而非 Integer
}
```

### QVO（查询参数对象）

```java
package org.springblade.order.vo.qvo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "OrderQVO对象")
public class OrderQVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "订单编号")
    private String orderNo;

    @Schema(description = "客户名称")
    private String customerName;
}
```

> QVO 只包含查询时需要过滤的字段，不需要全部字段。

### IVO（新增参数对象）

```java
package org.springblade.order.vo.ivo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
@Schema(description = "OrderIVO对象")
public class OrderIVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "订单编号不能为空！")
    @Schema(description = "订单编号")
    private String orderNo;

    @NotBlank(message = "客户名称不能为空！")
    @Schema(description = "客户名称")
    private String customerName;

    @NotNull(message = "金额不能为空！")
    @Schema(description = "金额")
    private java.math.BigDecimal amount;
}
```

> **注意**：`javax.validation.constraints` vs `jakarta.validation.constraints` — BladeX 4.1.0 使用 Spring Boot 3.x，应使用 `jakarta.validation`。但源码中部分旧模块仍然使用 `javax.validation`。新模块统一用 `jakarta.validation`。

### UVO（修改参数对象）

```java
package org.springblade.order.vo.uvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "OrderUVO对象")
public class OrderUVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "主键不能为空！")
    @Schema(description = "主键id", required = true)
    private Long id;

    @NotBlank(message = "订单编号不能为空！")
    @Schema(description = "订单编号")
    private String orderNo;

    @NotBlank(message = "客户名称不能为空！")
    @Schema(description = "客户名称")
    private String customerName;

    @NotNull(message = "金额不能为空！")
    @Schema(description = "金额")
    private java.math.BigDecimal amount;
}
```

> UVO 与 IVO 的区别：UVO 必须包含 `id` 字段且标记 `@NotNull`。

### EVO（Excel 导入导出对象）

见 [bladex-excel.md](bladex-excel.md) 详细说明。

---

## Mapper 接口

### 基础 Mapper

```java
package org.springblade.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springblade.order.entity.Order;

public interface OrderMapper extends BaseMapper<Order> {
    // 空接口，继承所有 MyBatis-Plus 内置 CRUD 方法
}
```

来源：`BannerMapper.java` — 大部分简单 CRUD 的 Mapper 都是空接口。

### 自定义查询 Mapper

```java
package org.springblade.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Param;
import org.springblade.order.entity.Order;
import org.springblade.order.vo.OrderVO;

import java.util.List;

public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 自定义分页查询
     */
    List<OrderVO> selectOrderPage(IPage<OrderVO> page, @Param("qvo") OrderQVO qvo);

    /**
     * 根据状态查询
     */
    List<Order> getByStatus(@Param("status") String status);

    /**
     * 统计查询
     */
    Long countByCustomer(@Param("customerName") String customerName);
}
```

> **规则**：
> - 自定义方法参数使用 `@Param` 注解
> - 分页方法第一个参数是 `IPage`，返回值是 `List`（MyBatis-Plus 自动填充分页信息）
> - 没有 XML 映射文件时，可以使用 MyBatis `@Select` 注解（BladeX 标准模块不使用此方式，而是通过 MyBatis-Plus Lambda API 在 Service 层构建查询）

### Mapper XML 映射文件（B8 硬约束）

> **Hard Rule（违反会导致 MyBatis 映射错乱，被生成器自检拦截）**：
> - `resultMap` 的 `type` 必须与对应 Mapper 方法返回元素类型一致：方法返回 `List<Entity>`/`Entity` 则 `type` 指向 Entity、`property` 用 Entity 字段名；方法返回 `List<XxxVO>`/`XxxVO` 则 `type` 指向 VO、`property` 用 VO 字段名。同一 `resultMap` 若被多个方法共用，这些方法返回元素类型必须相同。
> - `resultMap` 的 `property` 必须与 `type` 指向类的字段同名，`column` 用 snake_case；不要写 `type` 类不存在的 `property`。
> - `<select>` 里引用参数的前缀必须与 Mapper 接口 `@Param("xxx")` 一致：接口 `@Param("qvo")` 则 XML 用 `qvo.xxx`，不要用 `param.xxx`。

---

## MyBatis-Plus 查询模式

### Lambda 查询（推荐）

```java
// 等值查询
List<Order> list = orderService.list(
    Wrappers.<Order>lambdaQuery()
        .eq(Order::getCustomerName, "张三")
        .orderByAsc(Order::getCreateTime)
);

// Like 模糊查询
List<Order> list = orderService.list(
    Wrappers.<Order>lambdaQuery()
        .like(Order::getOrderNo, "2024")
        .orderByAsc(Order::getSort)
);

// 条件性 Like（参数非空时才生效）
List<Banner> banners = bannerService.list(
    Wrappers.<Banner>lambdaQuery()
        .like(EmptyUtil.isNotEmpty(qvo.getTitle()), Banner::getTitle, qvo.getTitle())
        .orderByAsc(Banner::getSort)
);
```

来源：`BannerController.list()` 真实写法。

### Lambda 更新

```java
// 条件更新
orderService.update(
    Wrappers.<Order>update().lambda()
        .set(Order::getStatus, "2")
        .eq(Order::getId, orderId)
);
```

来源：`TenantServiceImpl.setting()` 中使用 `Wrappers.<Tenant>update().lambda().set(...).eq(...)`

### Condition 工具类

```java
// 从 Map 参数构建 QueryWrapper（GET 请求自动映射）
IPage<Order> pages = orderService.page(
    Condition.getPage(query),                          // 分页参数
    Condition.getQueryWrapper(params, Order.class)     // 查询条件
);

// 从 Entity 对象构建 QueryWrapper
Order query = new Order();
query.setCustomerName("张三");
Order detail = orderService.getOne(Condition.getQueryWrapper(query));
```

来源：`ParamController.list()`、`DictController.detail()`

### 分页查询

```java
@GetMapping("/list")
public R<IPage<OrderVO>> list(@RequestParam Map<String, Object> params, Query query) {
    IPage<Order> pages = orderService.page(
        Condition.getPage(query),
        Condition.getQueryWrapper(params, Order.class)
    );
    return R.data(OrderWrapper.build().pageVO(pages));
}
```

`Query` 对象由 BladeX 框架自动绑定，包含 `current`（当前页）、`size`（每页条数）等参数。

### 逻辑删除

```java
// 方式一：deleteLogic（BaseServiceImpl 提供，推荐）
@PostMapping("/remove")
public R remove(@RequestParam String ids) {
    return R.status(orderService.deleteLogic(Func.toLongList(ids)));
}

// 方式二：removeByIds（依赖 @TableLogic 自动转为软删除）
@PostMapping("/remove")
public R remove(@RequestParam String ids) {
    return R.status(orderService.removeByIds(Func.toLongList(ids)));
}
```

> `Func.toLongList(ids)` 将逗号分隔的 ID 字符串（如 `"1,2,3"`）转为 `List<Long>`。

### 查询不在逻辑删除范围内的记录

```java
// 忽略 @TableLogic 过滤
List<Dict> list = dictService.list(
    Wrappers.<Dict>query().lambda()
        .eq(Dict::getIsDeleted, BladeConstant.DB_NOT_DELETED)
);
```

来源：`DictController.selectAll()`

---

## 命名规范速查表

| 元素 | 命名模式 | 示例 |
|------|---------|------|
| Entity | `{Name}` | `Order`, `OrderItem` |
| 表名 | `blade_{snake_name}` | `blade_order`, `blade_order_item` |
| Mapper | `{Name}Mapper` | `OrderMapper` |
| VO (输出) | `{Name}VO` | `OrderVO` |
| QVO (查询) | `{Name}QVO` | `OrderQVO` |
| IVO (新增) | `{Name}IVO` | `OrderIVO` |
| UVO (修改) | `{Name}UVO` | `OrderUVO` |
| EVO (Excel) | `{Name}Excel` | `OrderExcel` |

---

## 状态机字段（B6 约束）

> **约束（违反会导致依赖状态的查询永远返回空，生成器会告警提醒）**：
> - 含**状态机字段**（`xxxStatus` 有多个枚举值且依赖时间流转，如 `periodStatus`：1-未开始/2-进行中/3-已结束）的模块，必须生成配套**定时任务**（`timed` 包 `@Scheduled` 或走 blade-xxljob）推进状态，或在审查阶段显式标注状态推进方式。
> - 依赖该状态值的查询（如 `selectActivePeriods` 查 "进行中" 状态）若状态从不推进，会永远返回空。
> - 日期/时间区间判断注意类型匹配：`start_date`/`end_date` 是 `DATE`、`start_time`/`end_time` 是 `TIME`，不要用 `NOW()`（`DATETIME`）直接与 `TIME` 列比较。

## 注意事项

1. **Long 类型 ID 必须序列化为 String**：前端 JS 无法安全处理超过 2^53 的整数，所有 Long 类型的 ID 字段必须加 `@JsonSerialize(using = ToStringSerializer.class)`。`BaseEntity` 中的 `id` 已在框架层面处理，无需手动添加。

2. **`@EqualsAndHashCode(callSuper = true)`**：继承 `BaseEntity` 的 Entity 类必须添加此注解，否则 Lombok 生成的 equals/hashCode 不包含父类字段。

3. **`@TableLogic`**：继承 `BaseEntity` 的类已内置逻辑删除支持，不需要手动添加。手动模式的 Entity 必须显式在 `isDeleted` 字段上加 `@TableLogic`。

4. **`@TableField(exist = false)`**：用于标记 Java 对象中的临时字段（不在数据库表中），如查询辅助字段、计算字段、子对象列表等。

5. **条件查询中 null 处理**：`Wrappers.lambdaQuery().eq(Field::getXxx, null)` 不会生成 WHERE 条件，这是 MyBatis-Plus 的默认行为。如需判空再添加条件，使用 `.eq(condition, Field::getXxx, value)` 模式。

6. **分页方法返回值**：自定义 Mapper 分页方法返回 `List` 而非 `IPage`，MyBatis-Plus 会自动将结果写入传入的 `IPage` 参数。

## 检查清单

- [ ] Entity 继承 `BaseEntity` 并加 `@EqualsAndHashCode(callSuper = true)`
- [ ] Entity 加 `@TableName` 指定表名
- [ ] VO 中 Long 类型 ID 加 `@JsonSerialize(using = ToStringSerializer.class)`
- [ ] IVO/UVO 必填字段加 `@NotBlank`/`@NotNull`
- [ ] UVO 包含 `id` 字段并标记 `@NotNull`
- [ ] Mapper 接口继承 `BaseMapper<Entity>`
- [ ] 自定义 Mapper 方法参数使用 `@Param`
- [ ] 使用 `Wrappers.lambdaQuery()` 而非字符串拼接查询条件
- [ ] 逻辑删除使用 `deleteLogic()` 或依赖 `@TableLogic`
