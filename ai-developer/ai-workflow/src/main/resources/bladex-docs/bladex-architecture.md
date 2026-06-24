# BladeX 4.1.0 架构与项目结构

> 基于 BladeX 4.1.0.RELEASE 框架源码验证。适用于 Agent 后端开发参考。

## 概述

BladeX 是一个基于 Spring Cloud 的微服务平台，采用 Maven 多模块架构。核心依赖 Spring Boot 3.2.4、Spring Cloud、MyBatis-Plus、Nacos、Sentinel。

**技术栈**：Java 17 / Maven / Spring Boot 3.2.4 / MyBatis-Plus / Nacos / Sentinel / Redis / Druid

---

## Maven 模块结构

```
BladeX (根 POM, org.springblade, packaging=pom, version=4.1.0.RELEASE)
├── blade-auth/              认证服务 (jar)
├── blade-common/             共享常量、工具类 (jar)
├── blade-gateway/            Spring Cloud Gateway 网关 (jar)
├── blade-ops/                基础设施父模块 (pom)
│   ├── blade-admin/          Spring Boot Admin 监控
│   ├── blade-develop/        代码生成器
│   ├── blade-flow/           Flowable 工作流引擎
│   ├── blade-job/            PowerJob 分布式调度
│   ├── blade-log/            日志服务
│   ├── blade-report/         UReport 报表引擎
│   └── blade-resource/       对象存储 + 短信服务
├── blade-ops-api/            基础设施 API 父模块 (pom)
│   ├── blade-flow-api/       工作流 Feign 接口
│   ├── blade-log-api/        日志 Feign 接口
│   └── blade-resource-api/   资源 Feign 接口
├── blade-service/            业务服务父模块 (pom)  ← 主要开发区
│   ├── blade-system/         系统管理 (用户/角色/菜单/部门/租户/字典/参数)
│   ├── blade-user/           用户服务
│   ├── blade-desk/           工作台
│   ├── blade-education/      教育培训
│   └── blade-safety-control/ 安全双控
├── blade-service-api/        业务 API 父模块 (pom) ← 主要开发区
│   ├── blade-system-api/     系统管理 Entity/VO/Feign
│   ├── blade-user-api/       用户 Entity/VO/Feign
│   ├── blade-desk-api/       工作台 Entity/VO/Feign
│   ├── blade-dict-api/       字典 Entity/VO/Feign
│   ├── blade-scope-api/      数据权限 Entity/VO/Feign
│   ├── blade-education-api/  教育培训 Entity/VO/Feign
│   └── blade-safety-control-api/ 安全双控 Entity/VO/Feign
├── blade-plugin/             插件模块 (预留, 空)
├── blade-plugin-api/         插件 API 模块 (预留, 空)
├── doc/
│   ├── nacos/                Nacos 配置文件
│   │   ├── blade.yaml        共享基础配置
│   │   ├── blade-dev.yaml    开发环境配置
│   │   ├── blade-test.yaml   测试环境配置
│   │   ├── blade-prod.yaml   生产环境配置
│   │   └── routes/           网关动态路由 JSON
│   └── sql/                  数据库 DDL 脚本 (MySQL/PostgreSQL/Oracle/SQLServer/DaMeng/YashanDB)
└── script/docker/            Docker Compose 部署配置
```

### 模块职责速查

| 层级 | 模块 | 职责 |
|------|------|------|
| **API 定义** | `blade-xxx-api` | Entity、VO (QVO/IVO/UVO/EVO/VO)、Feign 接口、Feign Fallback、Cache 工具类 |
| **服务实现** | `blade-xxx` | Controller、Service 接口与实现、Mapper、Wrapper、Feign 实现 |
| **共享** | `blade-common` | 公共常量、工具类、Excel 工具、启动器 SPI |
| **基础设施** | `blade-ops/*` | 认证、网关、工作流、调度、日志、报表、资源 |

### 新增业务模块时需要的子模块

每个业务领域需要创建一对模块：

```
blade-service-api/blade-xxx-api/     ← Entity, VO, Feign 接口
blade-service/blade-xxx/             ← Controller, Service, Mapper, Wrapper
```

---

## 启动类写法

所有启动类都使用 `BladeApplication.run()` 而非标准的 `SpringApplication.run()`。注解组合有三种实际模式：

### 模式一：仅 @BladeCloudApplication

不需要 Feign 远程调用的简单模块：

```java
package org.springblade.system;

import org.springblade.core.cloud.client.BladeCloudApplication;
import org.springblade.core.launch.BladeApplication;
import org.springblade.core.launch.constant.AppConstant;

@BladeCloudApplication
public class SystemApplication {
    public static void main(String[] args) {
        BladeApplication.run(AppConstant.APPLICATION_SYSTEM_NAME, SystemApplication.class, args);
    }
}
```

来源：`blade-system/SystemApplication.java`、`blade-desk/DeskApplication.java`

### 模式二：@BladeCloudApplication + @EnableBladeFeign

需要 Feign 远程调用的标准模块：

```java
package org.springblade.system.user;

import org.springblade.core.cloud.feign.EnableBladeFeign;
import org.springblade.core.cloud.client.BladeCloudApplication;
import org.springblade.core.launch.BladeApplication;
import org.springblade.core.launch.constant.AppConstant;

@EnableBladeFeign
@BladeCloudApplication
public class UserApplication {
    public static void main(String[] args) {
        BladeApplication.run(AppConstant.APPLICATION_USER_NAME, UserApplication.class, args);
    }
}
```

来源：`blade-user/UserApplication.java`、`blade-education/EducationApplication.java`

### 模式三：@EnableBladeFeign + @SpringBootApplication

不使用 `@BladeCloudApplication` 组合注解，手动声明：

```java
package org.springblade.safetycontrol;

import org.springblade.common.constant.LauncherConstant;
import org.springblade.core.cloud.feign.EnableBladeFeign;
import org.springblade.core.launch.BladeApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableBladeFeign
@SpringBootApplication
public class SafetyControlApplication {
    public static void main(String[] args) {
        BladeApplication.run(LauncherConstant.APPLICATION_SAFETY_CONTROL_NAME,
            SafetyControlApplication.class, args);
    }
}
```

来源：`blade-safety-control/SafetyControlApplication.java`

> **说明**：`@BladeCloudApplication` 是 BladeX 框架组合注解（包含 `@SpringBootApplication` 等），但**不包含** `@EnableBladeFeign`。需要 Feign 时必须显式添加 `@EnableBladeFeign`。模式三功能等同于模式二，只是手动展开注解。新模块推荐模式二。

### 启动入口参数

`BladeApplication.run(appName, mainClass, args)` 的第一个参数是应用名，用于 Nacos 服务注册。常量定义在两个地方：

- `org.springblade.core.launch.constant.AppConstant` — 框架内置常量（如 `APPLICATION_SYSTEM_NAME = "blade-system"`）
- `org.springblade.common.constant.LauncherConstant` — 项目自定义常量（如 `APPLICATION_SAFETY_CONTROL_NAME = "blade-safety-control"`）

---

## 配置文件体系

每个服务模块的 `src/main/resources/` 下有两个配置文件：

### bootstrap.yml（所有模块完全一致）

```yaml
spring:
  cloud:
    nacos:
      config:
        namespace: blade_hgsjy
      discovery:
        namespace: blade_hgsjy
  config:
    activate:
      on-profile: dev
---
```

- **namespace**：Nacos 命名空间，所有服务共享同一个
- **on-profile: dev**：默认激活 dev 环境，可通过 `--spring.profiles.active=test` 覆盖

### application-dev.yml（两种模式）

**简单模式**（blade-system、blade-user 等标准模块）：

```yaml
server:
  port: 8106

spring:
  datasource:
    url: ${blade.datasource.dev.url}
    username: ${blade.datasource.dev.username}
    password: ${blade.datasource.dev.password}
  data:
    redis:
      host: ${spring.redis.host}
      port: ${spring.redis.port}
      password: ${spring.redis.password}
      database: ${spring.redis.database}
```

**Dynamic Datasource 模式**（blade-safety-control，需要多数据源时）：

```yaml
server:
  port: 8009

spring:
  autoconfigure:
    exclude: com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure
  datasource:
    dynamic:
      druid:
        filters: stat
        initial-size: 1
        min-idle: 1
        max-active: 20
        max-wait: 60000
        time-between-eviction-runs-millis: 60000
        min-evictable-idle-time-millis: 300000
        validation-query: SELECT 'x'
        test-while-idle: true
        test-on-borrow: false
        test-on-return: false
        pool-prepared-statements: false
        max-pool-prepared-statement-per-connection-size: 20
      enabled: true
      primary: dev
      strict: false
      datasource:
        dev:
          url: ${blade.datasource.dev.url}
          username: ${blade.datasource.dev.username}
          password: ${blade.datasource.dev.password}
  data:
    redis:
      host: ${spring.redis.host}
      port: ${spring.redis.port}
      password: ${spring.redis.password}
      database: ${spring.redis.database}
```

> 数据源和 Redis 的实际值由 Nacos 配置文件 `blade-dev.yaml` 中的占位符提供，不在本地配置中硬编码。

---

## Nacos 配置分层

Nacos 中配置加载顺序（后加载的覆盖先加载的）：

| 顺序 | Data ID | 说明 |
|------|---------|------|
| 1 | `blade.yaml` | 所有环境共享的基础配置 |
| 2 | `blade-{profile}.yaml` | 环境特定覆盖（dev/test/prod） |
| 3 | `blade-{service-name}.yaml` | 服务特定配置（按需） |
| 4 | `blade-{service-name}-{profile}.yaml` | 服务+环境特定配置（按需） |

### blade.yaml 关键配置项

```yaml
# 服务端口范围
# blade-gateway: 1168
# blade-auth:    8100
# blade-system:  8106
# blade-user:    8102
# blade-desk:    8105
# blade-resource: 8010
# blade-flow:    8008
# blade-log:     8103

# Spring 配置
spring:
  cloud:
    sentinel:
      eager: true

# Feign 配置
feign:
  sentinel:
    enabled: true
  okhttp:
    enabled: true
  httpclient:
    enabled: false

# BladeX 平台配置
blade:
  oauth2:
    enabled: true
  token:
    state: false        # 无状态 JWT
    single: false
  api:
    crypto:
      enabled: false
  jackson:
    null-to-empty: true
    big-num-to-string: true
  tenant:
    enhance: true
    column: tenant_id
  xss:
    enabled: true
  secure:
    strict-token: true
    strict-header: true
```

---

## 服务启动顺序

服务有依赖关系，应按以下顺序启动：

```
Layer 1 (核心基础设施)
  1. blade-auth       (8100) — 认证服务，所有请求需要 Token
  2. blade-gateway    (1168) — 网关，所有外部请求入口

Layer 2 (基础设施)
  3. blade-admin      (7002) — 监控
  4. blade-develop    (7007) — 代码生成
  5. blade-log        (8103) — 日志
  6. blade-resource   (8010) — 文件存储/短信
  7. blade-flow       (8008) — 工作流
  8. blade-job        (7770) — 调度
  9. blade-report     (8108) — 报表

Layer 3 (业务服务)
  10. blade-system    (8106) — 系统管理
  11. blade-user      (8102) — 用户
  12. blade-desk      (8105) — 工作台
  13. blade-safety-control (8009)
  14. blade-education (28006)
```

### 依赖关系

- **blade-gateway** 依赖 **blade-auth**（需要认证服务可用）
- 所有业务服务依赖 **blade-system**（需要用户/角色/租户数据）
- 各业务服务通过 Feign 互相调用

---

## 服务通信方式

### Feign 声明式调用（主要方式）

```java
// API 模块 — 接口定义
@FeignClient(
    value = AppConstant.APPLICATION_SYSTEM_NAME,
    fallback = IDictClientFallback.class
)
public interface IDictClient {
    String API_PREFIX = "/feign/client/dict";
    String GET_BY_ID = API_PREFIX + "/get-by-id";

    @GetMapping(GET_BY_ID)
    R<Dict> getById(@RequestParam("id") Long id);
}

// Service 模块 — 实现
@RestController
@AllArgsConstructor
public class DictClient implements IDictClient {
    private final IDictService service;

    @Override
    @GetMapping(GET_BY_ID)
    public R<Dict> getById(@RequestParam("id") Long id) {
        return R.data(service.getById(id));
    }
}
```

### RestTemplate（网关内部 / 特殊场景）

```java
@Configuration
public class RestTemplateConfig {
    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
```

### Sentinel 熔断

Feign 集成了 Sentinel，Fallback 类在 Feign 接口调用失败时自动降级：

```java
@Component
public class IDictClientFallback implements IDictClient {
    @Override
    public R<Dict> getById(Long id) {
        return R.fail("获取数据失败");
    }
}
```

---

## 目录结构速查（单个业务模块）

```
blade-service-api/blade-xxx-api/src/main/java/org/springblade/xxx/
├── entity/           Entity 实体类（@TableName + extends BaseEntity）
├── vo/               VO (输出视图对象)
│   ├── qvo/          QVO (查询参数对象)
│   ├── ivo/          IVO (新增参数对象)
│   └── uvo/          UVO (修改参数对象)
├── dto/              DTO (数据传输对象)
├── feign/            Feign 接口 (I*Client) + Fallback
└── cache/            Redis 缓存工具类

blade-service/blade-xxx/src/main/java/org/springblade/xxx/
├── XxxApplication.java      启动类
├── controller/      REST 控制器
├── service/         服务接口 (I*Service)
│   └── impl/        服务实现 (*ServiceImpl)
├── mapper/          MyBatis Mapper 接口
├── wrapper/         Entity ↔ VO 转换器
├── feign/           Feign 接口实现
├── excel/           Excel 导入导出类
└── utils/           模块内部工具类
```

---

## 检查清单

- [ ] 新增业务模块是否同时创建了 API 和服务两个子模块
- [ ] API 模块 pom.xml 中 `spring-boot-maven-plugin` 的 `<skip>` 设为 `true`
- [ ] 服务模块 pom.xml 依赖了对应的 API 模块
- [ ] `bootstrap.yml` 中 Nacos namespace 配置正确
- [ ] `application-dev.yml` 中数据库和 Redis 使用 Nacos 占位符
- [ ] 启动类使用 `BladeApplication.run()` 而非标准 `SpringApplication.run()`
- [ ] 服务名常量定义在 `LauncherConstant` 或使用 `AppConstant` 内置常量
