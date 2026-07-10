# BladeX 4.1.0 Feign 远程调用指南

> 基于 BladeX 4.1.0.RELEASE 框架源码验证。适用于 Agent 后端开发参考。
>
> **版本适配**: 本文档示例基于 BladeX 4.1.0(Swagger v3 + jakarta.*)。若参考项目是旧版,按参考项目实际版本生成。

## 概述

BladeX 微服务间通过 **OpenFeign** + **Sentinel** 进行声明式远程调用。Feign 接口定义在 API 模块，实现在服务模块。每个 Feign 接口都有一个对应的 **Fallback** 降级类。

---

## Feign 接口定义（API 模块）

文件位置：`blade-service-api/blade-xxx-api/src/main/java/org/springblade/xxx/feign/I*Client.java`

```java
package org.springblade.system.feign;

import org.springblade.core.launch.constant.AppConstant;
import org.springblade.core.tool.api.R;
import org.springblade.system.pojo.entity.Dict;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(
    value = AppConstant.APPLICATION_SYSTEM_NAME,   // 目标服务名（Nacos 注册名）
    fallback = IDictClientFallback.class            // 降级类
)
public interface IDictClient {

    // 端点路径常量
    String API_PREFIX = "/feign/client/dict";
    String GET_BY_ID = API_PREFIX + "/get-by-id";
    String GET_VALUE = API_PREFIX + "/get-value";
    String GET_LIST = API_PREFIX + "/get-list";

    /**
     * 获取字典实体
     */
    @GetMapping(GET_BY_ID)
    R<Dict> getById(@RequestParam("id") Long id);

    /**
     * 获取字典表对应值
     */
    @GetMapping(GET_VALUE)
    R<String> getValue(@RequestParam("code") String code,
                       @RequestParam("dictKey") String dictKey);

    /**
     * 获取字典表
     */
    @GetMapping(GET_LIST)
    R<List<Dict>> getList(@RequestParam("code") String code);
}
```

来源：`IDictClient`（blade-dict-api）

### 关键要点

| 要素 | 说明 |
|------|------|
| `@FeignClient(value = ...)` | `value` 为目标服务的 Nacos 注册名，使用 `AppConstant` 常量 |
| `fallback` | 降级类，当目标服务不可用时返回降级结果 |
| 端点路径 | 建议定义为 `String` 常量，格式为 `"/feign/client/{module}/{action}"` |
| `@RequestParam("name")` | **必须指定参数名**（`value = "name"`），否则 Feign 无法正确传参 |
| 返回值 | 始终返回 `R<T>`，不要返回裸数据类型 |
| 请求方式 | 使用 `@GetMapping` / `@PostMapping` 指定完整路径（含常量引用） |

### 使用自定义常量 vs 框架常量

```java
// 方式一：使用框架内置常量（标准模块）
@FeignClient(
    value = AppConstant.APPLICATION_SYSTEM_NAME,
    fallback = IDictClientFallback.class
)

// 方式二：使用项目自定义常量（业务扩展模块）
@FeignClient(
    value = LauncherConstant.APPLICATION_SAFETY_CONTROL_NAME,
    fallback = ISagetyControlClientFallback.class
)
```

来源：`IDictClient`（方式一）、`ISagetyControlClient`（方式二）

> `AppConstant` 位于 `org.springblade.core.launch.constant.AppConstant`，`LauncherConstant` 位于 `org.springblade.common.constant.LauncherConstant`。

---

## Feign Fallback 降级（API 模块）

文件位置：同 API 模块 feign 包下 `I*ClientFallback.java`

```java
package org.springblade.system.feign;

import org.springblade.core.tool.api.R;
import org.springblade.system.pojo.entity.Dict;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IDictClientFallback implements IDictClient {

    @Override
    public R<Dict> getById(Long id) {
        return R.fail("获取数据失败");
    }

    @Override
    public R<String> getValue(String code, String dictKey) {
        return R.fail("获取数据失败");
    }

    @Override
    public R<List<Dict>> getList(String code) {
        return R.fail("获取数据失败");
    }
}
```

来源：`IDictClientFallback`

### Fallback 规则

| 规则 | 说明 |
|------|------|
| `@Component` | 必须注册为 Spring Bean |
| 实现接口 | 实现对应的 Feign 接口 |
| 返回值 | 统一返回 `R.fail("获取数据失败")` |
| 简单通用 | 不包含复杂逻辑，仅返回失败响应 |

---

## Feign 接口实现（服务模块）

> **Hard Rule（B5，违反会导致其他微服务 Feign 调用 404，生成器会告警提醒）**：
> - api 模块中每个 `IXxxClient` 方法，service 模块必须有匹配的实现类 `XxxClient implements IXxxClient`（`@Hidden @RestController @AllArgsConstructor`），且每个接口方法 `@Override` + `@GetMapping(API_PREFIX + "...")` 端点路径、入参、返回类型与接口完全一致。
> - 禁止"api 模块定义了 `IXxxClient` 但 service 模块无 `XxxClient` 实现类"--其他微服务通过 Feign 调用会 404。

文件位置：`blade-service/blade-xxx/src/main/java/org/springblade/xxx/feign/*Client.java`

```java
package org.springblade.system.feign;

import lombok.AllArgsConstructor;
import org.springblade.core.tenant.annotation.NonDS;
import org.springblade.core.tool.api.R;
import org.springblade.system.pojo.entity.Dict;
import org.springblade.system.service.IDictService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

@NonDS
@Hidden
@RestController
@AllArgsConstructor
public class DictClient implements IDictClient {

    private final IDictService service;

    @Override
    @GetMapping(GET_BY_ID)
    public R<Dict> getById(Long id) {
        return R.data(service.getById(id));
    }

    @Override
    @GetMapping(GET_VALUE)
    public R<String> getValue(String code, String dictKey) {
        return R.data(service.getValue(code, dictKey));
    }

    @Override
    @GetMapping(GET_LIST)
    public R<List<Dict>> getList(String code) {
        return R.data(service.getList(code));
    }
}
```

来源：`DictClient`（blade-system/feign/）

### 关键要点

| 要素 | 说明 |
|------|------|
| `@RestController` | Feign 实现本身也是一个 REST 控制器 |
| `@AllArgsConstructor` | 构造器注入 Service |
| `@Hidden` | 隐藏 Swagger 文档（内部接口，不对外暴露） |
| `@NonDS` | 可选，系统表模块使用，禁用多数据源切换 |
| 方法注解 | 必须与 Feign 接口中的注解一致（路径、请求方式） |
| 返回值 | 用 `R.data()` 包装业务数据 |
| 实现类名 | `{Name}Client`（去掉接口的 `I` 前缀） |

> **注意**：Feign 实现类不继承 `BladeController`，它是一个普通的 `@RestController`。

---

## 调用端使用

在其他服务的 Service 或 Controller 中注入 Feign 接口即可调用：

```java
@Service
@AllArgsConstructor
public class ReportServiceImpl implements IReportService {

    // 注入 Feign 接口（不是实现类）
    private final IDictClient dictClient;

    @Override
    public String getDictValue(String code, String key) {
        R<String> result = dictClient.getValue(code, key);
        if (result.isSuccess()) {
            return result.getData();
        }
        return "未知";
    }
}
```

> 调用方只需要依赖 API 模块（`blade-xxx-api`），不需要依赖服务模块。

---

## Sentinel 熔断机制

Sentinel 在 BladeX 中已默认启用（`blade.yaml` 中 `feign.sentinel.enabled: true`）。当目标服务不可用时，自动触发 Fallback 降级：

```
调用方 → Feign 代理 → Sentinel 拦截 → 目标服务
                            ↓ (服务不可用)
                       Fallback 降级
                       R.fail("获取数据失败")
```

无需额外配置，Sentinel Dashboard 地址在 `LauncherServiceImpl` 中动态设置。

---

## 自定义 Feign 端点路径常量

### 框架内置常量（FeignConstant）

位于 `blade-common` 的 `org.springblade.common.constant.FeignConstant`：

```java
// 示例：框架内置端点
String SAFETY_BLADE_CONTROL_GET_SHIFT_QUAD = "/feign/safety/control/shift-quad";
```

### 自建端点常量（在 Feign 接口中定义）

```java
public interface IOrderClient {
    String API_PREFIX = "/feign/client/order";
    String GET_BY_ID = API_PREFIX + "/get-by-id";
    String GET_LIST = API_PREFIX + "/get-list";
    String SUBMIT   = API_PREFIX + "/submit";
}
```

> 推荐在接口中定义端点常量，保持内聚性。如需跨模块共享，定义在 `FeignConstant` 中。

---

## 完整示例：新建业务模块的 Feign 调用

### 步骤 1：API 模块 — 定义 Feign 接口

`blade-service-api/blade-order-api/.../feign/IOrderClient.java`：

```java
@FeignClient(
    value = LauncherConstant.APPLICATION_ORDER_NAME,
    fallback = IOrderClientFallback.class
)
public interface IOrderClient {
    String API_PREFIX = "/feign/client/order";
    String GET_BY_ID = API_PREFIX + "/get-by-id";

    @GetMapping(GET_BY_ID)
    R<Order> getById(@RequestParam("id") Long id);
}
```

### 步骤 2：API 模块 — 定义 Fallback

`blade-service-api/blade-order-api/.../feign/IOrderClientFallback.java`：

```java
@Component
public class IOrderClientFallback implements IOrderClient {
    @Override
    public R<Order> getById(Long id) {
        return R.fail("获取数据失败");
    }
}
```

### 步骤 3：服务模块 — 实现 Feign 接口

`blade-service/blade-order/.../feign/OrderClient.java`：

```java
@Hidden
@RestController
@AllArgsConstructor
public class OrderClient implements IOrderClient {
    private final IOrderService service;

    @Override
    @GetMapping(GET_BY_ID)
    public R<Order> getById(@RequestParam("id") Long id) {
        return R.data(service.getById(id));
    }
}
```

### 步骤 4：其他服务引用

在需要调用的服务中注入 `IOrderClient`，通过 Maven 依赖 `blade-order-api` 模块即可。

---

## 命名规范速查表

| 元素 | 命名模式 | 位置 | 示例 |
|------|---------|------|------|
| Feign 接口 | `I{Name}Client` | API 模块 `feign/` | `IOrderClient` |
| Feign Fallback | `I{Name}ClientFallback` | API 模块 `feign/` | `IOrderClientFallback` |
| Feign 实现 | `{Name}Client` | 服务模块 `feign/` | `OrderClient` |
| 端点前缀 | `API_PREFIX` | 接口中常量 | `"/feign/client/order"` |
| 具体端点 | `{ACTION}` | 接口中常量 | `GET_BY_ID` |

---

## 注意事项

1. **`@RequestParam` 必须指定 value**：`@RequestParam("id")`，不能省略参数名，否则 Feign 报错。
2. **返回值统一用 `R<T>`**：不要返回裸 `Order` 或 `List<Order>`。
3. **Fallback 必须 `@Component`**：否则不会被 Spring 管理。
4. **实现类加 `@Hidden`**：避免 Feign 实现端点出现在 Swagger 文档中。
5. **实现类不加 `@RequestMapping`**：端点路径由接口的 `@GetMapping` 等注解指定，实现类上不需要类级别的 `@RequestMapping`。
6. **Feign 接口的 `value`** 必须与目标服务在 Nacos 中的注册名一致。
7. **@FeignClient 中的 value 使用常量引用**：不要直接写字符串，使用 `AppConstant.XXX` 或 `LauncherConstant.XXX`。

## 检查清单

- [ ] Feign 接口加 `@FeignClient(value = ..., fallback = ...)`
- [ ] Fallback 类加 `@Component`，实现接口所有方法，返回 `R.fail("获取数据失败")`
- [ ] Feign 实现类加 `@Hidden` `@RestController` `@AllArgsConstructor`
- [ ] `@RequestParam` 指定 value 属性
- [ ] 返回值统一 `R<T>`
- [ ] 调用方只依赖 API 模块，不依赖服务模块
- [ ] 端点路径定义为 String 常量
