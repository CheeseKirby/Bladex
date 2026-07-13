# BladeX 4.1.0 业务层开发指南

> 基于 BladeX 4.1.0.RELEASE 框架源码验证。适用于 Agent 后端开发参考。
>
> **版本适配**: 本文档示例用 Swagger v3(@Schema/@Tag/@Operation) + jakarta.*。若参考项目是旧版(Java 8 / Swagger v2 / javax.*),
> **必须按参考项目实际版本生成**:Swagger 用 @ApiModel/@Api/@ApiOperation,注解用 javax.*。参考项目版本约束优先级高于本文档。

## 概述

BladeX 业务层遵循经典三层架构：**Controller → Service → Mapper**。Controller 接收请求并调用 Service，Service 封装业务逻辑并调用 Mapper（或 Feign Client）。层间数据转换通过 **Wrapper** 完成。

---

## Service 层

### 接口定义：两种基类

**模式一：extends BaseService<Entity>（BladeX 增强，推荐）**

```java
package org.springblade.order.service;

import org.springblade.core.mp.base.BaseService;
import org.springblade.order.entity.Order;

public interface IOrderService extends BaseService<Order> {

    /**
     * 获取订单值
     */
    String getValue(String paramKey);
}
```

来源：`IParamService`、`ITenantService`、`IBannerService`

`BaseService` 继承 MyBatis-Plus `IService`，额外提供了 `deleteLogic()`、`changeStatus()` 等方法。

**模式二：extends IService<Entity>（MyBatis-Plus 标准）**

```java
package org.springblade.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.springblade.system.pojo.entity.Dict;

public interface IDictService extends IService<Dict> {
    // 自定义方法...
}
```

来源：`IDictService`、`IMenuService`、`IRoleService`

> **选择建议**：需要逻辑删除 (`deleteLogic`) 或状态变更 (`changeStatus`) 功能时使用 `BaseService`；否则 `IService` 即可。新模块推荐 `BaseService` 以保持一致。

### 实现类：两种基类

**模式一：extends BaseServiceImpl<M extends BaseMapper<T>, T>（BladeX 增强）**

```java
package org.springblade.order.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springblade.core.mp.base.BaseServiceImpl;
import org.springblade.order.entity.Order;
import org.springblade.order.mapper.OrderMapper;
import org.springblade.order.service.IOrderService;
import org.springframework.stereotype.Service;

@Service
public class OrderServiceImpl extends BaseServiceImpl<OrderMapper, Order>
        implements IOrderService {

    @Override
    public String getValue(String paramKey) {
        Order order = this.getOne(
            Wrappers.<Order>query().lambda().eq(Order::getOrderNo, paramKey)
        );
        return order.getCustomerName();
    }
}
```

来源：`ParamServiceImpl`、`TenantServiceImpl`、`BannerServiceImpl`

**模式二：extends ServiceImpl<M extends BaseMapper<T>, T>（MyBatis-Plus 标准）**

```java
package org.springblade.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springblade.system.pojo.entity.Dict;
import org.springblade.system.mapper.DictMapper;
import org.springblade.system.service.IDictService;
import org.springframework.stereotype.Service;

@Service
public class DictServiceImpl extends ServiceImpl<DictMapper, Dict>
        implements IDictService {
    // ...
}
```

来源：`DictServiceImpl`、`MenuServiceImpl`、`RoleServiceImpl`

> **规则**：`BaseService` 接口配 `BaseServiceImpl`，`IService` 接口配 `ServiceImpl`。不要混搭。

### Service 通用注解

| 注解 | 说明 |
|------|------|
| `@Service` | 标记为 Spring Bean |
| `@AllArgsConstructor` | Lombok 构造器注入（当 Service 需要注入多个依赖时） |
| `@Transactional(rollbackFor = Exception.class)` | 声明式事务（多表操作时必须） |

### 使用 @AllArgsConstructor 构造器注入

```java
@Service
@AllArgsConstructor
public class TenantServiceImpl extends BaseServiceImpl<TenantMapper, Tenant>
        implements ITenantService {

    private final IRoleService roleService;
    private final IMenuService menuService;
    private final IDeptService deptService;
    // 通过 @AllArgsConstructor 生成包含所有 final 字段的构造器
    // 绝对不要使用 @Autowired 字段注入
}
```

来源：`TenantServiceImpl` — 注入了 10+ 个依赖，全部通过 `@AllArgsConstructor` 构造器注入。

### 简单 Service（无自定义逻辑）

大多数简单 CRUD 的 Service 实现类可以是空的：

```java
// 接口
public interface IBannerService extends BaseService<Banner> {
}

// 实现
@Service
public class BannerServiceImpl extends BaseServiceImpl<BannerMapper, Banner>
        implements IBannerService {
}
```

来源：`BannerServiceImpl` — 完全继承基类方法，无任何自定义代码。

### Service 常用内置方法（继承自基类）

| 方法 | 来源 | 说明 |
|------|------|------|
| `getById(Long id)` | IService | 按主键查询 |
| `getOne(Wrapper<T>)` | IService | 按条件查询单条 |
| `list(Wrapper<T>)` | IService | 按条件查询列表 |
| `page(IPage, Wrapper<T>)` | IService | 分页查询 |
| `save(T)` | IService | 新增 |
| `saveOrUpdate(T)` | IService | 新增或修改（有 id 则修改） |
| `updateById(T)` | IService | 按主键修改 |
| `update(Wrapper<T>)` | IService | 按条件修改 |
| `saveBatch(List<T>)` | IService | 批量新增 |
| `removeByIds(List<Long>)` | IService | 按主键删除（配合 @TableLogic 实现软删除） |
| `deleteLogic(List<Long>)` | BaseService | BladeX 逻辑删除（推荐） |
| `changeStatus(List<Long>, Integer)` | BaseService | 批量修改状态 |

### 事务

```java
@Service
public class OrderServiceImpl extends BaseServiceImpl<OrderMapper, Order>
        implements IOrderService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean submitOrder(Order order) {
        // 多表操作必须加事务
        this.saveOrUpdate(order);
        itemService.saveBatch(order.getItems());
        logService.saveLog(order);
        return true;
    }
}
```

> 简单单表操作（save/update/delete）不需要显式加 `@Transactional`，MyBatis-Plus 内置方法自带事务。

### 业务异常

```java
import org.springblade.core.log.exception.ServiceException;

// 唯一性校验
LambdaQueryWrapper<Order> lqw = Wrappers.<Order>query().lambda()
    .eq(Order::getOrderNo, order.getOrderNo());
Long cnt = baseMapper.selectCount(
    Func.isEmpty(order.getId())
        ? lqw
        : lqw.notIn(Order::getId, order.getId())
);
if (cnt > 0L) {
    throw new ServiceException("当前订单编号已存在!");
}
```

来源：`DictServiceImpl.submit()` 真实写法。

> `ServiceException` 是 BladeX 框架提供的运行时异常，会被全局异常处理器捕获并返回 `R.fail("...")`。

---

## Controller 层

### 标准模式：extend BladeController

```java
package org.springblade.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springblade.core.boot.ctrl.BladeController;
import org.springblade.core.mp.support.Condition;
import org.springblade.core.mp.support.Query;
import org.springblade.core.tool.api.R;
import org.springblade.core.tool.utils.Func;
import org.springblade.order.entity.Order;
import org.springblade.order.service.IOrderService;
import org.springblade.order.vo.OrderVO;
import org.springblade.order.wrapper.OrderWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@AllArgsConstructor
@RequestMapping("/order")
@Tag(name = "订单管理", description = "订单管理")
public class OrderController extends BladeController {

    private final IOrderService orderService;
```

来源：`DictController`、`ParamController`、`TenantController`、`BannerController`

### 不继承 BladeController 的情况

`UserController` 是唯一不继承 `BladeController` 的例外。新模块统一继承 `BladeController`。

### Controller 注解

| 注解 | 位置 | 说明 |
|------|------|------|
| `@RestController` | 类 | 标识 REST 控制器 |
| `@AllArgsConstructor` | 类 | Lombok 构造器注入 |
| `@RequestMapping("/xxx")` | 类 | 请求路径前缀 |
| `@Tag(name = "...")` | 类 | OpenAPI 3 接口分组 |
| `@Api(value = "...", tags = "...")` | 类 | Swagger 2 接口分组（旧模块） |
| `@NonDS` | 类 | 禁用多数据源切换（系统表模块使用） |
| `@Hidden` | 类 | 隐藏接口（内部 Feign 接口使用） |
| `@PreAuth(RoleConstant.HAS_ROLE_ADMIN)` | 类/方法 | 权限控制 |

### 标准 CRUD 端点

```java
/**
 * 详情
 */
@GetMapping("/detail")
@ApiOperationSupport(order = 1)
@Operation(summary = "详情", description = "传入id")
public R<OrderVO> detail(Order order) {
    Order detail = orderService.getOne(Condition.getQueryWrapper(order));
    return R.data(OrderWrapper.build().entityVO(detail));
}

/**
 * 分页列表
 */
@GetMapping("/list")
@Parameters({
    @Parameter(name = "orderNo", description = "订单编号",
        in = ParameterIn.QUERY, schema = @Schema(type = "string")),
    @Parameter(name = "customerName", description = "客户名称",
        in = ParameterIn.QUERY, schema = @Schema(type = "string"))
})
@ApiOperationSupport(order = 2)
@Operation(summary = "列表", description = "传入order")
public R<IPage<OrderVO>> list(
        @Parameter(hidden = true) @RequestParam Map<String, Object> params,
        Query query) {
    IPage<Order> pages = orderService.page(
        Condition.getPage(query),
        Condition.getQueryWrapper(params, Order.class)
    );
    return R.data(OrderWrapper.build().pageVO(pages));
}

/**
 * 新增或修改
 */
@PostMapping("/submit")
@ApiOperationSupport(order = 3)
@Operation(summary = "新增或修改", description = "传入order")
public R submit(@Valid @RequestBody Order order) {
    return R.status(orderService.saveOrUpdate(order));
}

/**
 * 删除
 */
@PostMapping("/remove")
@ApiOperationSupport(order = 4)
@Operation(summary = "逻辑删除", description = "传入ids")
public R remove(@Parameter(description = "主键集合", required = true)
        @RequestParam String ids) {
    return R.status(orderService.deleteLogic(Func.toLongList(ids)));
}
```

来源：`ParamController`（标准 CRUD 模板）、`BannerController`（带 IVO/UVO 变体）

> **Hard Rule（B7，违反会导致 QVO 区间字段失效/死代码，生成器会告警提醒）**：
> - `/list` 端点与 Mapper 自定义分页方法必须**二选一一致**：
>   - 方式一：`/list` 用 `Condition.getQueryWrapper(params, Entity.class)` + `service.page(...)`，此时 Mapper**不应**定义自定义分页方法，QVO 不含区间字段。
>   - 方式二：`/list` 用 `service.selectXxxPage(IPage, QVO)` 调用 Mapper 自定义分页，此时 QVO 的区间字段（如 `startDateStart`/`startDateEnd`）必须在 Mapper XML 生效。
> - 禁止"Mapper 定义了 `selectXxxPage` 但 Controller `/list` 没调用它"——这会让 QVO 区间字段被忽略（死代码）。

### 使用 IVO/UVO 的端点（更严格的参数校验）

```java
/**
 * 新增
 */
@PostMapping("/save")
@ApiOperationSupport(order = 3)
@Operation(summary = "新增")
public R save(@Valid @RequestBody OrderIVO orderIVO) {
    return R.status(orderService.save(OrderWrapper.build().entity(orderIVO)));
}

/**
 * 修改
 */
@PostMapping("/update")
@ApiOperationSupport(order = 4)
@Operation(summary = "修改")
@Transactional
public R update(@Valid @RequestBody OrderUVO orderUVO) {
    return R.status(orderService.updateById(OrderWrapper.build().entity(orderUVO)));
}
```

来源：`BannerController.save()` 和 `BannerController.update()`

> **Hard Rule（B4，违反会使业务校验成死代码，生成器会告警提醒）**：
> - Service 若定义了带业务校验的方法（如 `submit`/`checkXxx`/`validateXxx`），Controller 的 `/save`、`/update` 端点**必须调用该方法**，而非基类 `save()`/`updateById()`。否则校验逻辑成为死代码，非法数据直接落库。
> - 示例：`ISpecialPeriodService.submit()` 含时段重叠校验，则 Controller `/save` 应调 `service.submit(wrapper.entity(ivo))`，不要调 `service.save(...)`。

### 权限控制

```java
@GetMapping("/list")
@PreAuth(RoleConstant.HAS_ROLE_ADMIN)       // 仅管理员可访问
public R<IPage<Order>> list(...) { ... }

@PostMapping("/submit")
@PreAuth(RoleConstant.HAS_ROLE_ADMINISTRATOR) // 仅超级管理员
public R submit(@Valid @RequestBody Tenant tenant) { ... }
```

来源：`ParamController`、`TenantController`

常用权限常量（`org.springblade.core.tool.constant.RoleConstant`）：

| 常量 | 含义 |
|------|------|
| `HAS_ROLE_ADMIN` | 需要管理员角色 |
| `HAS_ROLE_ADMINISTRATOR` | 需要超级管理员角色 |

### Swagger 注解混合使用注意

BladeX 4.1.0 同时存在两种 Swagger 注解风格：

| 注解用途 | OpenAPI 3（推荐） | Swagger 2（旧模块） |
|---------|-------------------|---------------------|
| 类分组 | `@Tag(name = "...")` | `@Api(value = "...", tags = "...")` |
| 方法描述 | `@Operation(summary = "...")` | `@ApiOperation(value = "...")` |
| 参数描述 | `@Parameter(description = "...")` | `@ApiParam(name = "...", value = "...")` |
| 排序 | `@ApiOperationSupport(order = N)` | `@ApiOperationSupport(order = N)` |

> **推荐**：新模块统一使用 OpenAPI 3 注解。

---

## R<T> 统一响应

`R` 是 BladeX 的统一响应包装类（`org.springblade.core.tool.api.R`）。

### 常用静态方法

| 方法 | HTTP 语义 | 返回值 |
|------|----------|--------|
| `R.data(T data)` | 成功，带数据 | `R<T>` |
| `R.status(boolean success)` | 成功/失败 | `R<Object>` |
| `R.success(String msg)` | 成功，带消息 | `R<Object>` |
| `R.fail(String msg)` | 失败，带消息 | `R<Object>` |

### 使用示例

```java
// 返回单条数据
return R.data(orderVO);

// 返回分页数据
return R.data(OrderWrapper.build().pageVO(pages));

// 返回列表
return R.data(list);

// 操作结果
return R.status(orderService.saveOrUpdate(order));

// 操作失败
return R.fail("订单编号不能为空！");
```

### 响应 JSON 结构

```json
{
  "code": 200,
  "success": true,
  "data": { ... },
  "msg": "操作成功"
}
```

---

## Wrapper 数据转换

Wrapper 负责 Entity ↔ VO 之间的转换，继承 `BaseEntityWrapper<E, V>`。

### 标准 Wrapper 模板

```java
package org.springblade.order.wrapper;

import org.springblade.core.mp.support.BaseEntityWrapper;
import org.springblade.core.tool.utils.BeanUtil;
import org.springblade.order.entity.Order;
import org.springblade.order.vo.OrderVO;
import org.springblade.order.vo.ivo.OrderIVO;
import org.springblade.order.vo.uvo.OrderUVO;

import java.util.Objects;

public class OrderWrapper extends BaseEntityWrapper<Order, OrderVO> {

    public static OrderWrapper build() {
        return new OrderWrapper();
    }

    @Override
    public OrderVO entityVO(Order order) {
        if (order == null) {
            return new OrderVO();
        }
        OrderVO vo = Objects.requireNonNull(BeanUtil.copy(order, OrderVO.class));
        // 在此处解析 ID 字段为显示名称
        // vo.setCustomerTypeName(DictCache.getValue("customer_type", order.getCustomerType()));
        return vo;
    }

    /** IVO → Entity（新增时使用） */
    public Order entity(OrderIVO orderIVO) {
        if (orderIVO == null) {
            return new Order();
        }
        return Objects.requireNonNull(BeanUtil.copy(orderIVO, Order.class));
    }

    /** UVO → Entity（修改时使用） */
    public Order entity(OrderUVO orderUVO) {
        if (orderUVO == null) {
            return new Order();
        }
        return Objects.requireNonNull(BeanUtil.copy(orderUVO, Order.class));
    }
}
```

来源：`BannerWrapper`（IVO/UVO entity 方法）、`DictWrapper`（entityVO 含缓存查询）

### Wrapper 常用方法（继承自 BaseEntityWrapper）

| 方法 | 说明 |
|------|------|
| `entityVO(E entity)` | Entity → VO（单条） |
| `listVO(List<E> list)` | Entity 列表 → VO 列表 |
| `pageVO(IPage<E> page)` | 分页 Entity → 分页 VO |
| `listNodeVO(List<E> list)` | Entity 列表 → 树形 VO（需 Entity 实现 INode） |
| `listNodeLazyVO(List<E> list)` | Entity 列表 → 懒加载树形 VO |

### Wrapper 使用示例（Controller 中）

```java
// 单条
return R.data(OrderWrapper.build().entityVO(detail));

// 列表
return R.data(OrderWrapper.build().listVO(list));

// 分页
return R.data(OrderWrapper.build().pageVO(pages));

// IVO → Entity（新增）
orderService.save(OrderWrapper.build().entity(orderIVO));

// UVO → Entity（修改）
orderService.updateById(OrderWrapper.build().entity(orderUVO));
```

### 树形数据 Wrapper

```java
public class DictWrapper extends BaseEntityWrapper<Dict, DictVO> {

    @Override
    public DictVO entityVO(Dict dict) {
        DictVO vo = Objects.requireNonNull(BeanUtil.copyProperties(dict, DictVO.class));
        // 解析 parentId → parentName
        if (Func.equals(dict.getParentId(), BladeConstant.TOP_PARENT_ID)) {
            vo.setParentName(BladeConstant.TOP_PARENT_NAME);
        } else {
            Dict parent = DictCache.getById(dict.getParentId());
            vo.setParentName(parent.getDictValue());
        }
        return vo;
    }

    /** 将平铺列表转为树形结构 */
    public List<DictVO> listNodeVO(List<Dict> list) {
        List<DictVO> collect = list.stream()
            .map(dict -> BeanUtil.copyProperties(dict, DictVO.class))
            .collect(Collectors.toList());
        return ForestNodeMerger.merge(collect);
    }
}
```

来源：`DictWrapper` — 展示了 `entityVO` 中解析显示名称和 `listNodeVO` 树形转换。

> `ForestNodeMerger.merge()` 是 BladeX 提供的树形合并工具，将 `List<INode>` 转为树形结构。VO 类需实现 `INode<T>` 接口（含 `id`、`parentId`、`children` 字段）。

### BeanUtil

`BeanUtil.copy()` 和 `BeanUtil.copyProperties()` 来自 `org.springblade.core.tool.utils.BeanUtil`（BladeX 封装），功能类似 Spring `BeanUtils.copyProperties`。BladeX 版本返回目标对象，支持链式调用。

---

## @Valid 参数校验

```java
// 在 Controller 方法参数上加 @Valid
public R save(@Valid @RequestBody OrderIVO orderIVO) { ... }

// 在 IVO/UVO 字段上加校验注解
@NotBlank(message = "订单编号不能为空！")
private String orderNo;

@NotNull(message = "金额不能为空！")
private BigDecimal amount;
```

来源：`BannerController.save()` 使用 `@Valid @RequestBody BannerIVO`

> **注意**：校验注解包名 `javax.validation.constraints` vs `jakarta.validation.constraints`。BladeX 4.1.0 基于 Spring Boot 3.x，应使用 `jakarta.validation`。但部分旧模块仍然使用 `javax.validation`。新模块统一用 `jakarta.validation`。

---

## 命名规范速查表

| 元素 | 命名模式 | 示例 |
|------|---------|------|
| Service 接口 | `I{Name}Service` | `IOrderService` |
| Service 实现 | `{Name}ServiceImpl` | `OrderServiceImpl` |
| Controller | `{Name}Controller` | `OrderController` |
| Wrapper | `{Name}Wrapper` | `OrderWrapper` |

---

## 注意事项

1. **构造器注入**：使用 `@AllArgsConstructor`，永远不要用 `@Autowired` 字段注入。
2. **Controller 参数类型**：GET 请求使用 `@RequestParam Map<String, Object>` + `Query query` 模式（不是 Entity 对象），POST 使用 `@RequestBody`。
3. **`@Parameter(hidden = true)`**：用于隐藏 Swagger 文档中由框架自动绑定的参数（如 `Map<String, Object> params`）。
4. **操作成功**：新增/修改/删除统一返回 `R.status(boolean)`，查询返回 `R.data(T)`。
5. **缓存清理**：修改或删除操作后如需清理缓存，调用 `CacheUtil.clear(CACHE_NAME)`。
6. **Controller 中的 `@Transactional`**：简单逻辑不需要；复杂多步操作可在 Controller 加 `@Transactional`（如 BannerController.update）。
7. **`BladeController`**：`extend BladeController` 提供了一些便捷方法，但不是强制的（如 `UserController` 就不继承）。

## 检查清单

- [ ] Service 接口继承 `BaseService` 或 `IService`
- [ ] Service 实现继承 `BaseServiceImpl` 或 `ServiceImpl`，基类与接口匹配
- [ ] Controller 使用 `@AllArgsConstructor` 构造器注入
- [ ] Controller 继承 `BladeController`
- [ ] 使用 `R.data()` / `R.status()` 统一响应
- [ ] 新增/修改参数加 `@Valid @RequestBody`
- [ ] Wrapper 继承 `BaseEntityWrapper<E, V>`，提供静态 `build()` 方法
- [ ] Wrapper 覆盖 `entityVO()` 方法
- [ ] 多表操作加 `@Transactional(rollbackFor = Exception.class)`
- [ ] 业务校验失败抛出 `ServiceException`
