# AI 驱动 BladeX 代码生成工作流

通过可视化设计 + 自然语言需求,自动生成符合 BladeX 4.1.0 规范的完整业务模块(Entity/VO/Controller/Service/Mapper/Wrapper/Excel/Feign + DDL + 模块骨架)。

## 架构

三个服务,通过 REST/JSON 互通:

| 服务 | 目录 | 端口 | 角色 |
|---|---|---|---|
| 前端 | `ai-designer/`(Vite + React) | 3000 | 可视化设计页面 |
| BFF / Part A | `ai-designer/server/`(Node + Express) | 3001 | 代理 + 状态回调 |
| Part B | `ai-developer/ai-workflow/`(Spring Boot) | 8110 | AI 代码生成引擎 |

```
浏览器(3000) ── HTTP ──> BFF(3001) ── HTTP ──> Part B(8110) ── 写盘 ──> ai-generated-modules/
                            <── 状态回调 ── Part B
```

## 目录结构

```
├── ai-designer/              Part A:前端 + BFF
│   ├── src/                  React 前端
│   └── server/               Node BFF(端口 3001)
├── ai-developer/             Part B:生成引擎
│   ├── ai-workflow/          Spring Boot 服务(端口 8110)
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
| MySQL | ≥ 8.0 |

校验:`java -version`、`mvn -v`、`node -v` 都能输出版本。

### 2. 初始化数据库(一次性)

```bash
mysql -uroot -p < ai-developer/sql/init.sql
```

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

**双击 `start.bat`**(或命令行 `.\start.bat`)。走 cmd,不受 PowerShell 执行策略限制,直接双击即可。

会开 3 个窗口分别跑 Part B(8110)、BFF(3001)、前端(3000)。访问 http://localhost:3000/ 。

> `start.bat` 读取 `.env` 注入环境变量。密码若含 `& | < > ^` 等 cmd 特殊字符,需用 `^` 转义(如 `secret^&pass`);普通字母数字密码无此问题。

## 使用流程

1. 打开 http://localhost:3000/ ,新建项目
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
| 端口被占用 | `netstat -ano \| findstr :8110` 找占用进程停掉 |
| Part B 启动报 `未加载到任何 BladeX 规范文档` | 默认从 jar 内 `classpath:bladex-docs/` 加载,无需配置;自定义设 `CONVENTION_DOCS_PATH` |
| MySQL 连不上 | 检查 `.env` 的 `DB_USERNAME`/`DB_PASSWORD`;Docker 模式用 `docker compose -f docker-compose.mysql.yml down` 重起 |

## 配置项

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AI_WORKFLOW_OUTPUT_ROOT` | `../../ai-generated-modules` | 产物输出根 |
| `CONVENTION_DOCS_PATH` | `classpath:bladex-docs/` | BladeX 规范文档路径 |
| `PART_A_CALLBACK_URL` | `http://localhost:3001/api/transmission/status-update` | Part A 回调地址 |
| `AI_WORKFLOW_ADMIN_TOKEN` | (空) | 管理端 token;未配置时写入端点仅接受本地回环 |
| `TARGET_PROJECT_ROOT` | `../../blade_hgsjy` | 旧版兼容(BuildVerifier 用,当前未启用) |
