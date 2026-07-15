# AI Workflow 一键部署脚本(Windows / PowerShell)
#
# 用法:
#   1. 解压本压缩包到任意目录(例如 D:\workspace\houduan\)
#   2. 在该目录右键 → "用 PowerShell 运行"  或  打开 PowerShell 后 cd 过去
#   3. .\deploy.ps1
#
# 脚本职责:
#   - 检查依赖(JDK 17 / Maven / Node 18+ / MySQL 或 Docker)
#   - 交互式收集凭证(LLM token、DB 密码)
#   - 写 .env 文件
#   - 选择 MySQL 来源(本地 / Docker)并灌 schema
#   - mvn -o package + npm install
#   - 打印 start.ps1 启动命令(脚本不自动起服务)
#
# 注意:
#   - 不会替你安装 JDK/MySQL/Node;缺了会提示装哪个版本
#   - .env 不会被打进压缩包,只在目标机生成

# 用 Continue 而非 Stop:外部命令(java/mvn/node/docker/mysql)常把版本/进度信息
# 写到 stderr(Java 惯例),Stop 策略会把它们误判为错误抛 NativeCommandError。
# 失败检测统一靠 $LASTEXITCODE + 显式 Fail()。
$ErrorActionPreference = "Continue"
$ProgressPreference    = "SilentlyContinue"

# ─── 工作目录 ───────────────────────────────────────────
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root
Write-Host ""
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  AI Workflow 部署脚本" -ForegroundColor Cyan
Write-Host "  工作目录: $Root" -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

# ─── 工具函数 ───────────────────────────────────────────
function Test-Cmd($name) {
    return [bool](Get-Command $name -ErrorAction SilentlyContinue)
}

function Fail($msg) {
    Write-Host "[FAIL] $msg" -ForegroundColor Red
    exit 1
}

function Info($msg) { Write-Host "[INFO] $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }

function Read-Secret($prompt) {
    $sec = Read-Host -Prompt $prompt -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

# ─── 1. 依赖检查 ──────────────────────────────────────────
Info "Step 1/6: 检查依赖"

if (-not (Test-Cmd "java")) { Fail "未找到 java。请装 JDK 17 (https://adoptium.net/),然后把 bin 加入 PATH。" }
$javaVerOut = (& java -version 2>&1) -join "`n"
if ($javaVerOut -notmatch '"(\d+)\.') { Fail "无法解析 java -version: $javaVerOut" }
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt 17) { Fail "需要 JDK 17+,当前是 $javaMajor。" }
Write-Host "  java: OK (major=$javaMajor)"

if (-not (Test-Cmd "mvn")) { Fail "未找到 mvn。请装 Maven 3.8+ (https://maven.apache.org/)。" }
$mvnVerOut = (& mvn -v 2>&1) -join "`n"
if ($mvnVerOut -notmatch 'Apache Maven (\d+)\.') { Fail "无法解析 mvn -v: $mvnVerOut" }
$mvnMajor = [int]$Matches[1]
if ($mvnMajor -lt 3) { Fail "需要 Maven 3.8+,当前是 $mvnMajor。" }
Write-Host "  mvn: OK (v$mvnMajor)"

if (-not (Test-Cmd "node")) { Fail "未找到 node。请装 Node 18+ (https://nodejs.org/)。" }
$nodeVer = (& node -v 2>&1).TrimStart('v')
if ($nodeVer -notmatch '^(\d+)\.') { Fail "无法解析 node -v: $nodeVer" }
$nodeMajor = [int]$Matches[1]
if ($nodeMajor -lt 18) { Fail "需要 Node 18+,当前是 v$nodeVer。" }
Write-Host "  node: OK (v$nodeVer)"

if (-not (Test-Cmd "npm")) { Fail "未找到 npm(随 Node 一起装)。" }
Write-Host "  npm: OK"

# ─── 2. MySQL 来源选择 ────────────────────────────────────
Info "Step 2/6: 选择 MySQL 来源"
Write-Host "  [1] 本地已装 MySQL(脚本调用 mysql 命令灌 schema)"
Write-Host "  [2] 用 Docker 起临时 MySQL(脚本启动 docker-compose,自动建库)"
$mysqlMode = Read-Host "选择 [1/2]"

if ($mysqlMode -eq "1") {
    if (-not (Test-Cmd "mysql")) {
        Fail "未找到 mysql 命令。要么装 MySQL Client 加 PATH,要么选 [2] 用 Docker。"
    }
    Write-Host "  mysql client: OK"
    $dbHost     = Read-Host "MySQL 主机 [默认 127.0.0.1]"
    if ([string]::IsNullOrWhiteSpace($dbHost)) { $dbHost = "127.0.0.1" }
    $dbPort     = Read-Host "MySQL 端口 [默认 3307]"
    if ([string]::IsNullOrWhiteSpace($dbPort)) { $dbPort = "3307" }
    $dbUser     = Read-Host "MySQL 用户 [默认 root]"
    if ([string]::IsNullOrWhiteSpace($dbUser)) { $dbUser = "root" }
    $dbPass     = Read-Secret "MySQL 密码(输入隐藏)"
}
elseif ($mysqlMode -eq "2") {
    if (-not (Test-Cmd "docker")) { Fail "未找到 docker。请装 Docker Desktop 或选 [1]。" }
    Write-Host "  docker: OK"
    $dbHost = "127.0.0.1"; $dbPort = "3307"; $dbUser = "root"
    $dbPass = Read-Secret "为 Docker MySQL 设置 root 密码"
}
else { Fail "无效选择: $mysqlMode" }

# ─── 3. 收集 LLM 凭证 ────────────────────────────────────
Info "Step 3/6: 收集 LLM 凭证"
$llmToken = Read-Secret "ANTHROPIC_AUTH_TOKEN (LLM 鉴权 token)"
if ([string]::IsNullOrWhiteSpace($llmToken)) { Fail "LLM token 不能为空。" }

$llmBaseUrl = Read-Host "LLM 网关 URL [默认 https://ark.cn-beijing.volces.com/api/coding]"
if ([string]::IsNullOrWhiteSpace($llmBaseUrl)) { $llmBaseUrl = "https://ark.cn-beijing.volces.com/api/coding" }

$llmModel = Read-Host "LLM 模型名 [默认 glm-5.1]"
if ([string]::IsNullOrWhiteSpace($llmModel)) { $llmModel = "glm-5.1" }

# ─── 4. 写 .env ───────────────────────────────────────────
Info "Step 4/6: 生成 .env(凭证文件,不要提交版本控制)"
$envPath = Join-Path $Root ".env"
@"
# 由 deploy.ps1 生成于 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
ANTHROPIC_AUTH_TOKEN=$llmToken
ANTHROPIC_BASE_URL=$llmBaseUrl
LLM_MODEL=$llmModel
ANTHROPIC_VERSION=2023-06-01

DB_USERNAME=$dbUser
DB_PASSWORD=$dbPass
DB_HOST=$dbHost
DB_PORT=$dbPort

AI_WORKFLOW_OUTPUT_ROOT=../../ai-generated-modules
TARGET_PROJECT_ROOT=../../ai-generated-modules
CONVENTION_DOCS_PATH=classpath:bladex-docs/
# BFF 端口(默认 3004;BFF 读 PORT,vite 代理读 VITE_BFF_PORT,两者必须相同)
HOST=127.0.0.1
PORT=3004
VITE_BFF_PORT=3004
# BFF 放行前端 CORS 来源(默认前端跑在 3005)
FRONTEND_ORIGIN=http://localhost:3005
# BFF 转发到 Part B(ai-workflow)的地址(默认 8111)
PART_B_URL=http://localhost:8111
PART_A_CALLBACK_URL=http://localhost:3004/api/transmission/status-update
BFF_ADMIN_TOKEN=
AI_WORKFLOW_ADMIN_TOKEN=
"@ | Out-File -FilePath $envPath -Encoding utf8 -NoNewline
Write-Host "  .env 已写入: $envPath"

# ─── 5. 数据库初始化 ─────────────────────────────────────
Info "Step 5/6: 数据库初始化(灌 init.sql)"
$initSql = Join-Path $Root "ai-developer\sql\init.sql"
if (-not (Test-Path $initSql)) { Fail "找不到 init.sql: $initSql" }

if ($mysqlMode -eq "2") {
    # Docker 模式:起容器
    $composeFile = Join-Path $Root "docker-compose.mysql.yml"
    @"
services:
  ai-workflow-mysql:
    image: mysql:8.0
    container_name: ai-workflow-mysql
    environment:
      MYSQL_ROOT_PASSWORD: $dbPass
      MYSQL_DATABASE: ai_workflow
    ports:
      - "${dbPort}:3306"
    volumes:
      - ai-workflow-mysql-data:/var/lib/mysql
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
volumes:
  ai-workflow-mysql-data:
"@ | Out-File -FilePath $composeFile -Encoding utf8 -NoNewline

    Write-Host "  启动 Docker MySQL..."
    # 先清理同名残留容器(上次失败可能留下 Created/Exited 状态的容器,导致 up 报 name in use)
    # 容器不存在时 docker rm 会写 stderr(正常),Continue 策略下不抛异常,忽略即可。
    & docker rm -f ai-workflow-mysql 2>$null | Out-Null

    # 端口冲突预检:3307(或配置的 dbPort)若已被占用,提示后再起,避免 compose up 报模糊错
    $portBusy = (Get-NetTCPConnection -LocalPort $dbPort -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count
    if ($portBusy -gt 0) {
        Write-Host "[WARN] 端口 $dbPort 已被占用(可能是本机 MySQL 或其他容器)。" -ForegroundColor Yellow
        Write-Host "       选项:1) 选本地 MySQL 模式复用该实例 2) 停掉占用者 3) 改用其他端口" -ForegroundColor Yellow
        $cont = Read-Host "是否继续(可能失败)? [y/N]"
        if ($cont -notmatch '^[yY]') { Fail "请先释放端口 $dbPort 后重跑 deploy.ps1" }
    }

    & docker compose -f $composeFile up -d 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[FAIL] docker compose up 失败" -ForegroundColor Red
        Write-Host "      常见原因:端口 $dbPort 被占用、或同名容器残留。" -ForegroundColor Red
        Write-Host "      排查:docker ps -a | findstr mysql;  netstat -ano | findstr :$dbPort" -ForegroundColor Red
        exit 1
    }

    Write-Host "  等待 MySQL 就绪(最多 60 秒)..."
    $ready = $false
    for ($i = 1; $i -le 30; $i++) {
        Start-Sleep -Seconds 2
        $check = & docker exec ai-workflow-mysql mysqladmin ping -uroot -p"$dbPass" 2>$null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }
    if (-not $ready) { Fail "MySQL 启动超时,请检查 docker logs ai-workflow-mysql" }
    Write-Host "  MySQL 就绪"

    Write-Host "  灌入 init.sql..."
    Get-Content $initSql -Raw | & docker exec -i ai-workflow-mysql mysql -uroot -p"$dbPass"
    if ($LASTEXITCODE -ne 0) { Fail "init.sql 执行失败" }
}
else {
    # 本地 MySQL
    Write-Host "  灌入 init.sql 到 ${dbHost}:${dbPort}..."
    # 用 stdin 管道灌入(与 Docker 模式一致),避免 source 命令对含空格部署路径解析失败
    Get-Content $initSql -Raw | & mysql -h $dbHost -P $dbPort -u $dbUser -p"$dbPass"
    if ($LASTEXITCODE -ne 0) { Fail "init.sql 执行失败,请检查 MySQL 凭证。" }
}
Write-Host "  数据库初始化完成"

# ─── 6. 构建 ─────────────────────────────────────────────
Info "Step 6/6: 构建(首次较慢,需联网拉 Maven/npm 依赖)"

Push-Location (Join-Path $Root "ai-developer")
Write-Host "  mvn -DskipTests package(预热依赖)..."
& mvn -DskipTests -q package
$mvnExit = $LASTEXITCODE
Pop-Location
if ($mvnExit -ne 0) { Fail "Maven 构建失败" }
Write-Host "  Maven 构建 OK"

Push-Location (Join-Path $Root "ai-designer")
Write-Host "  npm install..."
& npm install --no-audit --no-fund
$npmExit = $LASTEXITCODE
Pop-Location
if ($npmExit -ne 0) { Fail "npm install 失败" }
Write-Host "  npm install OK"

# ─── 完成 ────────────────────────────────────────────────
Write-Host ""
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Green
Write-Host "  部署完成。下一步:" -ForegroundColor Green
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Green
Write-Host ""
Write-Host "  启动服务:  .\start.ps1" -ForegroundColor Yellow
Write-Host "  访问前端:  http://localhost:3005/" -ForegroundColor Yellow
Write-Host "  BFF API:   http://localhost:3004/" -ForegroundColor Yellow
Write-Host "  Part B:    http://localhost:8111/doc.html" -ForegroundColor Yellow
Write-Host ""
if ($mysqlMode -eq "2") {
    Write-Host "  停 MySQL:  docker compose -f docker-compose.mysql.yml down" -ForegroundColor Gray
}
Write-Host ""
