# AI 驱动 BladeX 代码生成工作流

通过可视化设计 + 自然语言需求,自动生成符合 BladeX 4.1.0 规范的完整业务模块(Entity/VO/Controller/Service/Mapper/Wrapper/Excel/Feign + DDL + 模块骨架)。

## 架构

三个服务,通过 REST/JSON 互通:

| 服务 | 目录 | 端口 | 角色 |
|---|---|---|---|
| 前端 | `ai-designer/`(Vite + React) | 3005 | 可视化设计页面 |
| BFF / Part A | `ai-designer/server/`(Node + Express) | 3004 | 代理 + 状态回调 |
| Part B | `ai-developer/ai-workflow/`(Spring Boot) | 8111 | AI 代码生成引擎 |

```
浏览器(3005) ── HTTP ──> BFF(3004) ── HTTP ──> Part B(8111) ── 写盘 ──> ai-generated-modules/
                            <── 状态回调 ── Part B
```

## 目录结构

```
├── ai-designer/              Part A:前端 + BFF
│   ├── src/                  React 前端
│   └── server/               Node BFF(端口 3004)
├── ai-developer/             Part B:生成引擎
│   ├── ai-workflow/          Spring Boot 服务(端口 8111)
│   │   └── src/main/resources/bladex-docs/   BladeX 规范文档(打进 jar,运行时加载)
│   └── sql/init.sql          数据库 schema
├── pack.ps1                  源机:打交付包
├── deploy.ps1                目标机:一键部署
├── start.ps1                 启动 3 个服务
└── .env.example              环境变量模板
```

## 快速开始(本机)

### 1. 前置条件

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| Maven | ≥ 3.8 |
| Node.js | ≥ 18 |
| 数据库 | MySQL ≥ 8.0，或 Docker Desktop |

校验:`java -version`、`mvn -v`、`node -v` 都能输出版本。

### 2. 首次配置

复制 `.env.example` 为 `.env`，填写 LLM 和数据库配置；推荐直接运行 `deploy.ps1`，它会交互式生成配置、准备 MySQL 并初始化 schema。

如果 `.env` 使用本机 `127.0.0.1`/`localhost` 数据库但端口尚未监听，后续 `start.bat` 会自动通过 `docker-compose.mysql.yml` 启动 MySQL，并幂等执行 `ai-developer/sql/init.sql`。

### 3. 配置环境变量

复制 `.env.example` 为 `.env`,填入 LLM token、数据库密码等(或用 `deploy.ps1` 交互式生成)。

关键字段:

| 变量 | 必填 | 说明 |
|---|---|---|
| `ANTHROPIC_AUTH_TOKEN` | 是 | LLM 鉴权 token |
| `ANTHROPIC_BASE_URL` | 是 | LLM 网关(火山方舟 / Claude 官方) |
| `LLM_MODEL` | 是 | 模型名(glm-5.1 等) |
| `DB_USERNAME` / `DB_PASSWORD` | 是 | MySQL 凭证 |

### 4. 启动服务

**双击 `start.bat`**（或命令行 `.\start.bat`）即可。批处理会使用 `ExecutionPolicy Bypass` 调用统一的 `start.ps1`，不受本机 PowerShell 执行策略影响。

一键启动会自动完成：

1. 读取 `.env` 并检查 Java、Maven、Node.js、npm；
2. 检查 MySQL；必要时自动启动 Docker Desktop 和 Docker MySQL；
3. 对 Docker MySQL 幂等执行 `init.sql`；
4. 清理 8111、3004、3005 上一轮残留服务；
5. 依次启动 Part B、BFF、前端，并等待三项健康检查通过。

启动成功后访问 http://localhost:3005/ 。三个应用分别在独立窗口运行，关闭对应窗口即可停止应用；Docker MySQL 会保留数据供下次复用。

## 使用流程

1. 打开 http://localhost:3005/ ,新建项目
2. 拖入模块(可选)+ 输入自然语言需求,Enter 触发生成方案
3. 「审查反馈」→ 审查总方案 → 拆分子方案
4. 「子方案」→ 🚀 传输到 Part B
5. 页面显示进度:RECEIVED → EXECUTING → COMPLETED
6. 生成产物落在 `ai-generated-modules/`(见下)

## 生成产物

产物按 **BladeX 多模块格式** 落在独立目录(与项目物理隔离,不污染源码):

```
ai-generated-modules/
├── blade-service-api/blade-{module}-api/        对外契约
│   ├── pom.xml
│   └── src/main/java/org/springblade/{module}/
│       ├── pojo/entity/{Entity}.java            Entity
│       ├── pojo/vo/{Entity}{VO,QVO,IVO,UVO,EVO}.java
│       └── feign/I{Entity}Client.java
├── blade-service/blade-{module}/               业务实现
│   ├── pom.xml
│   └── src/main/java/org/springblade/{module}/
│       ├── {Module}Application.java            启动类
│       ├── controller/{Entity}Controller.java
│       ├── mapper/{Entity}Mapper.java + .xml
│       ├── service/I{Entity}Service.java + impl/{Entity}ServiceImpl.java
│       ├── wrapper/{Entity}Wrapper.java
│       └── excel/{Entity}Excel.java
│   └── src/main/resources/{bootstrap,application-dev}.yml
└── doc/sql/{module}/migration.sql              DDL
```

**集成进真实 BladeX 项目**:把 `blade-{module}-api/` 和 `blade-{module}/` 拷到目标项目的 `blade-service-api/`、`blade-service/` 下,父 pom `<modules>` 注册即可 `mvn compile`。

## 迁移到其他电脑

### 源机打包

```powershell
.\pack.ps1
```

按 7-Zip(`.zip`)→ WinRAR(`.rar`)→ 系统自带(`.zip`,慢)顺序选工具。产物 `bladex-deploy-*.{zip|rar}` 约 0.4 MB(仅源码,不含依赖)。脚本会自动校验包内文件数与源对账,漏文件会 FAIL。

### 目标机部署

PowerShell 默认禁止运行脚本,首次需解锁(任选其一):

```powershell
# 方式 A(推荐):一次性解锁当前用户,之后所有 .ps1 都能直接跑
Set-ExecutionPolicy RemoteSigned -Scope CurrentUser

# 方式 B:不修改策略,单次绕过运行
powershell -ExecutionPolicy Bypass -File deploy.ps1
```

解锁后:

```powershell
.\deploy.ps1
```

交互式完成:依赖检查 → 选 MySQL(本地 / Docker)→ 填凭证 → 写 `.env` → 灌 schema → 构建。完成后双击 `start.bat` 启动。

> 首次构建需联网拉 Maven/npm 依赖。`start.bat` 不受执行策略限制,部署好后直接双击即可。

## 常见问题

| 现象 | 排查 |
|---|---|
| 启动报 `未找到 java/mvn/node` | 装对应软件并加入 PATH,重开 PowerShell |
| Part B 写文件失败 | 确认 `AI_WORKFLOW_OUTPUT_ROOT`(默认 `../../ai-generated-modules`)所在盘可写 |
| LLM 调用 401 | `.env` 里 `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_BASE_URL` 配错 |
| 端口被占用 | `netstat -ano \| findstr :8111` 找占用进程停掉 |
| Part B 启动报 `未加载到任何 BladeX 规范文档` | 默认从 jar 内 `classpath:bladex-docs/` 加载,无需配置;自定义设 `CONVENTION_DOCS_PATH` |
| MySQL 连不上 | 检查 `.env` 的 `DB_USERNAME`/`DB_PASSWORD`;Docker 模式用 `docker compose -f docker-compose.mysql.yml down` 重起 |

## 配置项

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AI_WORKFLOW_OUTPUT_ROOT` | `../../ai-generated-modules` | 产物输出根 |
| `CONVENTION_DOCS_PATH` | `classpath:bladex-docs/` | BladeX 规范文档路径 |
| `PART_A_CALLBACK_URL` | `http://localhost:3004/api/transmission/status-update` | Part A 回调地址 |
| `HOST` | `127.0.0.1` | BFF bind address; do not expose publicly without authentication |
| `AI_WORKFLOW_HOST` | `127.0.0.1` | Part B bind address; keep local unless protected by a trusted network boundary |
| `BFF_ADMIN_TOKEN` | (empty) | Required for non-loopback BFF access; keep empty for localhost-only mode |
| `BFF_JSON_LIMIT` | `2mb` | Maximum JSON request body size |
| `BFF_LLM_RATE_LIMIT` | `30` | Maximum LLM HTTP requests per rate window |
| `BFF_LLM_RATE_WINDOW_MS` | `60000` | LLM rate-limit window in milliseconds |
| `AI_WORKFLOW_ADMIN_TOKEN` | (空) | 管理端 token;未配置时写入端点仅接受本地回环 |
| `TARGET_PROJECT_ROOT` | `../../ai-generated-modules` | 生成产物写入根 + 现有项目索引根(默认隔离区,不再指 blade_hgsjy 定制版) |
