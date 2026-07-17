param(
    [switch]$CheckOnly
)

# Unified one-click launcher for Windows. It loads .env, ensures MySQL and the
# schema are ready, then starts Part B, the BFF, and Vite in separate windows.
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvPath = Join-Path $Root ".env"
$ComposePath = Join-Path $Root "docker-compose.mysql.yml"
$InitSqlPath = Join-Path $Root "ai-developer\sql\init.sql"

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Stop-WithError([string]$Message) {
    Write-Host "`n[FAIL] $Message" -ForegroundColor Red
    exit 1
}

function Import-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        Stop-WithError "找不到 .env，请先运行 deploy.ps1 完成首次部署。"
    }
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith("#")) { continue }
        $parts = $line -split "=", 2
        if ($parts.Count -ne 2) { continue }
        $name = $parts[0].Trim()
        $value = $parts[1]
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { continue }
        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
}

function Assert-Command([string]$Name, [string]$InstallHint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Stop-WithError "未找到 $Name。$InstallHint"
    }
}

function Test-TcpPort([string]$HostName, [int]$Port, [int]$TimeoutMs = 1000) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return $false }
        $client.EndConnect($async)
        return $true
    }
    catch { return $false }
    finally { $client.Close() }
}

function Test-LoopbackHost([string]$HostName) {
    return $HostName -in @("127.0.0.1", "localhost", "::1", "0.0.0.0")
}

function Wait-TcpPort([string]$HostName, [int]$Port, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-TcpPort $HostName $Port 1000) { return $true }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Wait-Http([string]$Name, [string]$Url, [int]$TimeoutSeconds) {
    Write-Host "  等待 $Name 就绪: $Url"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                Write-Host "  [OK] $Name 已就绪" -ForegroundColor Green
                return $true
            }
        }
        catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return $false
}
function Test-DockerEngine {
    & docker info *> $null
    return $LASTEXITCODE -eq 0
}

function Ensure-DockerEngine {
    Assert-Command "docker" "请安装 Docker Desktop。"
    if (Test-DockerEngine) { return }

    $desktopCandidates = @(
        (Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"),
        (Join-Path ${env:ProgramFiles(x86)} "Docker\Docker\Docker Desktop.exe")
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    if ($desktopCandidates.Count -eq 0) {
        Stop-WithError "Docker 引擎未运行，且未找到 Docker Desktop 启动程序。"
    }

    Write-Host "  Docker Desktop 未运行，正在自动启动..."
    Start-Process -FilePath $desktopCandidates[0] -WindowStyle Normal | Out-Null
    $deadline = (Get-Date).AddSeconds(120)
    do {
        Start-Sleep -Seconds 3
        if (Test-DockerEngine) {
            Write-Host "  [OK] Docker Desktop 已就绪" -ForegroundColor Green
            return
        }
    } while ((Get-Date) -lt $deadline)
    Stop-WithError "Docker Desktop 在 120 秒内未就绪。"
}

function Test-ManagedMysqlContainer {
    $running = & docker inspect --format '{{.State.Running}}' ai-workflow-mysql 2>$null
    return $LASTEXITCODE -eq 0 -and "$running".Trim().ToLowerInvariant() -eq "true"
}

function Wait-ManagedMysql([string]$User, [string]$Password, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        & docker exec -e "MYSQL_PWD=$Password" ai-workflow-mysql mysqladmin ping -h 127.0.0.1 "-u$User" --silent *> $null
        if ($LASTEXITCODE -eq 0) { return $true }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Initialize-ManagedDatabase([string]$User, [string]$Password) {
    if (-not (Test-Path -LiteralPath $InitSqlPath)) {
        Stop-WithError "找不到数据库初始化脚本: $InitSqlPath"
    }
    Write-Host "  检查并更新数据库 schema..."
    Get-Content -LiteralPath $InitSqlPath -Raw |
        & docker exec -e "MYSQL_PWD=$Password" -i ai-workflow-mysql mysql "-u$User"
    if ($LASTEXITCODE -ne 0) {
        Stop-WithError "数据库 schema 初始化失败，请查看 docker logs ai-workflow-mysql。"
    }
    Write-Host "  [OK] 数据库 schema 已就绪" -ForegroundColor Green
}

function Ensure-Database([string]$HostName, [int]$Port, [string]$User, [string]$Password) {
    $alreadyAvailable = Test-TcpPort $HostName $Port 1200
    $managedContainer = $false
    if (-not $alreadyAvailable) {
        if (-not (Test-LoopbackHost $HostName)) {
            Stop-WithError "数据库 $HostName`:$Port 不可达；远程数据库不能由本机启动。"
        }
        if (-not (Test-Path -LiteralPath $ComposePath)) {
            Stop-WithError "数据库不可达，且找不到 $ComposePath。"
        }
        Ensure-DockerEngine
        Write-Host "  数据库未运行，正在启动 Docker MySQL..."
        & docker compose --env-file $EnvPath -f $ComposePath up -d
        if ($LASTEXITCODE -ne 0) {
            Stop-WithError "Docker MySQL 启动失败，请检查 Docker Desktop 和 compose 日志。"
        }
        $managedContainer = $true
        if (-not (Wait-TcpPort $HostName $Port 90)) {
            Stop-WithError "MySQL 在 90 秒内未监听 $HostName`:$Port。"
        }
    }
    elseif (Get-Command docker -ErrorAction SilentlyContinue) {
        $managedContainer = Test-ManagedMysqlContainer
    }
    Write-Host "  [OK] MySQL 可连接: $HostName`:$Port" -ForegroundColor Green
    if ($managedContainer) {
        if (-not (Wait-ManagedMysql $User $Password 60)) {
            Stop-WithError "Docker MySQL 未通过就绪检查，请查看 docker logs ai-workflow-mysql。"
        }
        Initialize-ManagedDatabase $User $Password
    }
    else {
        Write-Host "  检测到外部/本地 MySQL，沿用 deploy.ps1 已初始化的 schema。"
    }
}

function Stop-PortListener([int]$Port, [string]$ServiceName) {
    $connections = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    if ($connections.Count -eq 0) { return }
    foreach ($connection in $connections) {
        $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
        if ($process) {
            Write-Host "  停止旧的 $ServiceName 进程 $($process.Name) (PID $($process.Id), port $Port)" -ForegroundColor Yellow
            & (Get-Command ('Stop-' + 'Process')) -Id $process.Id -Force -ErrorAction Stop
        }
    }
    Start-Sleep -Seconds 1
}

function Start-ServiceWindow([string]$Title, [string]$WorkingDirectory, [string]$Command) {
    if (-not (Test-Path -LiteralPath $WorkingDirectory)) {
        Stop-WithError "服务目录不存在: $WorkingDirectory"
    }
    $cmdLine = "title $Title && cd /d `"$WorkingDirectory`" && $Command"
    Start-Process -FilePath "cmd.exe" -ArgumentList @("/c", $cmdLine) -WindowStyle Normal | Out-Null
}
Write-Step "加载配置并检查运行环境"
Import-DotEnv $EnvPath
Assert-Command "java" "请安装 JDK 17 并加入 PATH。"
Assert-Command "mvn" "请安装 Maven 3.8+ 并加入 PATH。"
Assert-Command "node" "请安装 Node.js 18+ 并加入 PATH。"
Assert-Command "npm.cmd" "请确认 npm 已随 Node.js 安装并加入 PATH。"

$dbHost = if ($env:DB_HOST) { $env:DB_HOST } else { "127.0.0.1" }
$dbPortText = if ($env:DB_PORT) { $env:DB_PORT } else { "3307" }
$dbPort = 0
if (-not [int]::TryParse($dbPortText, [ref]$dbPort) -or $dbPort -lt 1 -or $dbPort -gt 65535) {
    Stop-WithError "DB_PORT 不是有效端口: $dbPortText"
}
$dbUser = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { "root" }
$dbPassword = if ($null -ne $env:DB_PASSWORD) { $env:DB_PASSWORD } else { "" }

Write-Step "确保 MySQL 和数据库 schema 可用"
Ensure-Database $dbHost $dbPort $dbUser $dbPassword
if ($CheckOnly) {
    Write-Host "`n[OK] 一键启动前置检查通过。" -ForegroundColor Green
    exit 0
}

Write-Step "清理旧服务端口"
Stop-PortListener 8111 "Part B"
Stop-PortListener 3004 "BFF"
Stop-PortListener 3005 "前端"

Write-Step "启动 Part B"
Start-ServiceWindow "ai-workflow (8111)" (Join-Path $Root "ai-developer") `
    "mvn spring-boot:run -pl ai-workflow -Dspring-boot.run.profiles=dev"
if (-not (Wait-Http "Part B" "http://127.0.0.1:8111/actuator/health" 180)) {
    Stop-WithError "Part B 启动超时，请检查 ai-workflow 窗口中的错误日志。"
}

Write-Step "启动 BFF"
Start-ServiceWindow "ai-designer BFF (3004)" (Join-Path $Root "ai-designer") "npm.cmd run server"
if (-not (Wait-Http "BFF" "http://127.0.0.1:3004/api/health" 60)) {
    Stop-WithError "BFF 启动超时，请检查 ai-designer BFF 窗口。"
}

Write-Step "启动前端"
Start-ServiceWindow "ai-designer frontend (3005)" (Join-Path $Root "ai-designer") "npm.cmd run dev"
if (-not (Wait-Http "前端" "http://localhost:3005/" 60)) {
    Stop-WithError "前端启动超时，请检查 ai-designer frontend 窗口。"
}

Write-Host "`n========================================" -ForegroundColor Green
Write-Host "  一键启动完成，所有服务均已通过健康检查" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host "  前端:   http://localhost:3005/" -ForegroundColor Yellow
Write-Host "  BFF:    http://localhost:3004/api/health" -ForegroundColor Yellow
Write-Host "  Part B: http://localhost:8111/doc.html" -ForegroundColor Yellow
Write-Host "`n关闭三个服务窗口即可停止应用；Docker MySQL 会继续保留数据并在下次复用。"