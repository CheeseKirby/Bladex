# BladeX 4.1.0 配置体系指南

> 基于 BladeX 4.1.0.RELEASE 框架源码验证。适用于 Agent 后端开发参考。

## 概述

BladeX 使用 **Nacos** 作为配置中心和服务发现。配置分为三层：**本地 bootstrap.yml** → **Nacos 共享配置 blade.yaml** → **Nacos 环境配置 blade-{profile}.yaml**。所有服务共享同一套 Nacos 配置，通过环境 profile 区分开发/测试/生产。

---

## 配置分层架构

```
┌─────────────────────────────────────────────┐
│ bootstrap.yml (本地)                         │
│  - Nacos namespace                          │
│  - spring.profiles.active                   │
├─────────────────────────────────────────────┤
│ blade.yaml (Nacos 共享)                      │
│  - 框架全局配置 (Sentinel, Feign, OAuth2...) │
│  - 平台配置 (blade.tenant, blade.secure...)  │
├─────────────────────────────────────────────┤
│ blade-{profile}.yaml (Nacos 环境)            │
│  - Redis 连接信息                            │
│  - 数据库连接信息                             │
│  - 环境特定覆盖                              │
├─────────────────────────────────────────────┤
│ blade-{service-name}.yaml (Nacos 服务, 可选) │
│  - 服务特定端口                              │
│  - 服务特定数据源                             │
└─────────────────────────────────────────────┘
```

后加载的配置覆盖先加载的。Nacos 配置通过 `Data ID` 匹配：`blade.yaml`、`blade-dev.yaml`、`blade-system.yaml`、`blade-system-dev.yaml` 等。

---

## bootstrap.yml（本地，每个服务必配）

```yaml
spring:
  cloud:
    nacos:
      config:
        namespace: blade         # Nacos 命名空间
      discovery:
        namespace: blade         # 服务发现命名空间（通常与 config 一致）
  config:
    activate:
      on-profile: dev                 # 默认激活的环境
---
```

> **规则**：
> - `namespace` 为 Nacos 命名空间 ID，所有服务使用同一个
> - `on-profile: dev` 决定加载 `blade-dev.yaml` 等环境配置
> - 部署时通过 `--spring.profiles.active=test` 覆盖

---

## blade.yaml（Nacos 共享配置）

这是所有环境和所有服务共享的核心配置文件，在 Nacos 中以 `blade.yaml` 为 Data ID 发布。

### 服务器配置

```yaml
server:
  undertow:
    threads:
      io: 16                    # IO 线程数（每个 CPU 核心一个）
      worker: 400               # 阻塞任务线程池
    buffer-size: 1024
    direct-buffers: true
```

### Spring Cloud 配置

```yaml
spring:
  cloud:
    nacos:
      discovery:
        isUseCloudNamespaceParsing: false   # 禁用云命名空间解析
      config:
        isUseCloudNamespaceParsing: false
    sentinel:
      eager: true                          # 饥饿加载 Sentinel
```

### Feign + Sentinel

```yaml
feign:
  sentinel:
    enabled: true              # Feign 集成 Sentinel 熔断
  okhttp:
    enabled: true              # 使用 OkHttp 作为 HTTP 客户端
  httpclient:
    enabled: false             # 禁用 Apache HttpClient
```

### Knife4j / Swagger

```yaml
knife4j:
  enable: true
  basic:
    enable: false
    username: blade
    password: blade
  setting:
    language: zh_cn
    enableFooterCustom: true
    footerCustomContent: Copyright © 2024 BladeX All Rights Reserved

swagger:
  title: BladeX 接口文档系统
  version: 4.1.0.RELEASE
```

### OAuth2 / Token

```yaml
blade:
  oauth2:
    enabled: true
    public-key: 04...         # SM2 国密公钥（前端加密用）
    private-key: 00...        # SM2 国密私钥（后端解密用）
  token:
    state: false              # 无状态 JWT（不存 Redis）
    single: false             # 允许多端同时登录
    sign-key: ...             # JWT 签名密钥
    crypto-key: ...           # JWT 加密密钥
```

### 安全框架配置

```yaml
blade:
  secure:
    strict-token: true        # 严格 Token 校验
    strict-header: true       # 严格请求头校验
    skip-url:                 # 放行 URL
      - /test/**
    # 自定义授权规则
    auth:
      - method: ALL
        pattern: /chat/wechat/**
        expression: "hasAuth()"
      - method: POST
        pattern: /dashboard/submit
        expression: "hasAnyRole('administrator', 'admin', 'user')"
    # 基础认证
    basic:
      - method: ALL
        pattern: /dashboard/info
        username: "blade"
        password: "blade"
    # 签名认证
    sign:
      - method: ALL
        pattern: /dashboard/sign
        crypto: "sha1"
    # 多终端认证
    client:
      - client-id: sword
        path-patterns:
          - /sword/**
      - client-id: saber
        path-patterns:
          - /saber/**
```

### 多租户

```yaml
blade:
  tenant:
    enhance: true              # 启用多租户增强
    license: false             # 不启用授权保护
    dynamic-datasource: false  # 不启用动态数据源
    column: tenant_id          # 租户字段名
    exclude-tables:            # 排除多租户逻辑的表
      - blade_user
```

### Jackson 序列化

```yaml
blade:
  jackson:
    null-to-empty: true        # null 自动转空值
    big-num-to-string: true    # Long 自动转 String（前端 JS 精度安全）
```

### XSS 防护

```yaml
blade:
  xss:
    enabled: true
    skip-url:                  # XSS 过滤跳过的 URL
      - /weixin
      - /notice/submit
```

### 日志

```yaml
blade:
  log:
    request:
      enabled: true            # 控制台请求日志
      error-log: true          # 错误日志入库
```

---

## blade-{profile}.yaml（Nacos 环境配置）

以 `blade-dev.yaml` 为例：

### Redis

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      password:
      database: 0
      ssl:
        enabled: false
```

### 数据源

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    druid:
      validation-query: select 1
```

### Blade 平台环境配置

```yaml
blade:
  lock:
    enabled: false
    address: redis://127.0.0.1:6379
  loadbalancer:
    enabled: true
    prior-ip-pattern:          # 灰度发布：优先调用的 IP 段
      - 192.168.0.*
      - 127.0.0.1
  datasource:
    dev:                       # 数据源连接信息（被各服务 application-dev.yml 引用）
      url: jdbc:mysql://localhost:3306/bladex?useSSL=false&...
      username: root
      password: root
```

---

## application-dev.yml（本地，服务模块）

### 简单模式

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

> 占位符 `${blade.datasource.dev.url}` 的值来自 Nacos 环境配置 `blade-dev.yaml`。

### Dynamic Datasource 模式（多数据源）

```yaml
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
        validation-query: SELECT 'x'
        test-while-idle: true
      enabled: true
      primary: dev
      strict: false
      datasource:
        dev:
          url: ${blade.datasource.dev.url}
          username: ${blade.datasource.dev.username}
          password: ${blade.datasource.dev.password}
```

> 多数据源模式使用 `@NonDS` 注解标记不走动态数据源的表，使用 `@DS("datasourceName")` 指定特定数据源。

---

## LauncherServiceImpl — SPI 启动扩展

文件位置：`blade-common/src/main/java/org/springblade/common/launch/LauncherServiceImpl.java`

```java
@AutoService(LauncherService.class)       // Google AutoService SPI 自动发现
public class LauncherServiceImpl implements LauncherService {

    @Override
    public void launcher(SpringApplicationBuilder builder,
                         String appName, String profile, boolean isLocalDev) {
        Properties props = System.getProperties();
        // Nacos 地址
        PropsUtil.setProperty(props, "spring.cloud.nacos.username", "nacos");
        PropsUtil.setProperty(props, "spring.cloud.nacos.password", "nacos");
        PropsUtil.setProperty(props, "spring.cloud.nacos.discovery.server-addr",
            LauncherConstant.nacosAddr(profile));
        PropsUtil.setProperty(props, "spring.cloud.nacos.config.server-addr",
            LauncherConstant.nacosAddr(profile));
        // Sentinel 地址
        PropsUtil.setProperty(props, "spring.cloud.sentinel.transport.dashboard",
            LauncherConstant.sentinelAddr(profile));
        // Zipkin 地址
        PropsUtil.setProperty(props, "spring.zipkin.base-url",
            LauncherConstant.zipkinAddr(profile));
        // 启用动态数据源
        PropsUtil.setProperty(props, "spring.datasource.dynamic.enabled", "true");
    }
}
```

> `LauncherServiceImpl` 在 Spring Boot 启动前执行，通过 SPI 机制自动发现。它根据 `profile` 参数动态设置 Nacos、Sentinel、Zipkin 等基础设施地址。

### Nacos 地址常量（LauncherConstant）

```java
// 开发环境
NACOS_USERNAME = "nacos"
NACOS_PASSWORD = "nacos"
nacosAddr("dev") → "127.0.0.1:8848"

// 测试/生产环境
nacosAddr("test") → "172.30.0.48:8848"
nacosAddr("prod") → "172.30.0.48:8848"
```

---

## Gateway 动态路由

### 路由配置格式

路由配置以 JSON 形式存储在 Nacos 中，Data ID 为 `blade-gateway-dev.json`：

```json
[
  {
    "id": "order-route",
    "order": 0,
    "predicates": [
      {
        "name": "Path",
        "args": {
          "pattern": "/blade-order/**"
        }
      }
    ],
    "filters": [],
    "uri": "lb://blade-order"
  }
]
```

| 字段 | 说明 |
|------|------|
| `id` | 路由唯一标识 |
| `order` | 路由优先级（越小越优先） |
| `predicates` | 路由断言（Path 匹配） |
| `filters` | 路由过滤器（如 StripPrefix、AddRequestHeader） |
| `uri` | 目标 URI，`lb://` 前缀表示通过 Nacos 负载均衡 |

### 动态路由加载机制

`DynamicRouteServiceListener` 在 Gateway 启动时：

1. 连接 Nacos，获取路由配置 JSON
2. 将 JSON 解析为 `List<RouteDefinition>`
3. 注册到 Spring Cloud Gateway
4. 监听 Nacos 配置变更，实时更新路由

### 全局路径前缀剥离

Gateway 的 `RequestFilter`（order: -1000）对所有请求自动执行 `StripPrefix=1`：

```
客户端请求: /blade-order/api/list
Gateway 剥离第一段后: /api/list
转发到 blade-order 服务
```

### Gateway 自动路由发现

`bootstrap.yml` 中配置了 `spring.cloud.gateway.discovery.locator.enabled: true`，Gateway 也会自动为 Nacos 注册中心中的每个服务创建路由。

---

## 安全与认证流程

### Gateway AuthFilter

`AuthFilter`（order: -100）对所有请求进行认证：

1. 检查请求是否在 `skip-url` 列表中 → 跳过认证
2. 检查是否匹配 `basic` 规则 → Basic Auth 认证
3. 检查是否匹配 `sign` 规则 → 签名认证
4. 检查是否匹配 `auth` 规则 → 自定义表达式认证
5. 默认：从 `Blade-Auth` 请求头提取 JWT Token，验证有效性

### Token 验证失败时返回

```json
{
  "code": 401,
  "msg": "Token is not active",
  "data": null
}
```

---

## 关键配置速查

| 配置项 | 位置 | 说明 |
|--------|------|------|
| `spring.cloud.nacos.config.namespace` | bootstrap.yml | Nacos 命名空间 |
| `spring.cloud.nacos.discovery.namespace` | bootstrap.yml | 服务发现命名空间 |
| `spring.config.activate.on-profile` | bootstrap.yml | 激活的环境 |
| `server.port` | application-dev.yml | 服务端口 |
| `spring.datasource.url` | Nacos blade-dev.yaml | 数据库连接 |
| `spring.data.redis.host` | Nacos blade-dev.yaml | Redis 连接 |
| `blade.tenant.column` | Nacos blade.yaml | 多租户字段名 |
| `blade.secure.skip-url` | Nacos blade.yaml | 认证放行 URL |
| `feign.sentinel.enabled` | Nacos blade.yaml | Feign 熔断开关 |

---

## 注意事项

1. **Nacos namespace 必须一致**：所有服务（包括 Gateway）的 `config.namespace` 和 `discovery.namespace` 必须相同。
2. **配置加载顺序**：Nacos 共享配置 → Nacos 环境配置 → 本地 application.yml。同名 key 后者覆盖前者。
3. **敏感信息**：生产环境的数据库密码、Redis 密码等不应写在 `blade-prod.yaml` 明文，应使用 Nacos 配置加密或环境变量注入。
4. **Gateway 路由变更**：修改 Nacos 中的路由 JSON 后，Gateway 会自动热加载，无需重启。
5. **profile 切换**：通过启动参数 `--spring.profiles.active=test` 切换环境，影响 Nacos Data ID 的匹配（`blade-test.yaml`）。
6. **LauncherServiceImpl 中的硬编码**：Nacos 用户名密码（nacos/nacos）和地址在代码中硬编码。生产部署时需要修改 `LauncherConstant` 中的地址常量。

## 检查清单

- [ ] bootstrap.yml 中 Nacos namespace 配置正确
- [ ] application-dev.yml 中数据库/Redis 使用 Nacos 占位符而非硬编码
- [ ] Nacos 中 blade.yaml 和 blade-dev.yaml 已正确发布
- [ ] Gateway 路由 JSON 已配置在 Nacos 中
- [ ] 所有服务端口不冲突
- [ ] 安全放行 URL 列表包含必要的公开接口（如 `/oauth/token/**`）
