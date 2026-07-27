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

function New-RandomHexSecret([int]$ByteCount = 32) {
    $bytes = New-Object byte[] $ByteCount
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }
    return (($bytes | ForEach-Object { $_.ToString("x2") }) -join "")
}

function Set-DotEnvValue([string]$Path, [string]$Name, [string]$Value) {
    $existing = if (Test-Path -LiteralPath $Path) {
        [System.IO.File]::ReadAllText($Path)
    } else {
        ""
    }
    $lines = [System.Text.RegularExpressions.Regex]::Split($existing, "\r?\n")
    $updatedLines = New-Object System.Collections.Generic.List[string]
    $matched = $false
    $pattern = "^\s*" + [System.Text.RegularExpressions.Regex]::Escape($Name) + "\s*="
    foreach ($line in $lines) {
        if ($line -match $pattern) {
            if (-not $matched) {
                $updatedLines.Add($Name + "=" + $Value)
                $matched = $true
            }
            continue
        }
        $updatedLines.Add($line)
    }
    if (-not $matched) {
        while ($updatedLines.Count -gt 0 -and [string]::IsNullOrEmpty($updatedLines[$updatedLines.Count - 1])) {
            $updatedLines.RemoveAt($updatedLines.Count - 1)
        }
        $updatedLines.Add($Name + "=" + $Value)
    }
    $updated = ($updatedLines -join "`r`n").TrimEnd("`r", "`n") + "`r`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $updated, $utf8NoBom)
}

function Ensure-PlanBundleSigningSecret([string]$Path) {
    $primary = if ($null -eq $env:PLAN_BUNDLE_SIGNING_SECRET) { "" } else { $env:PLAN_BUNDLE_SIGNING_SECRET.Trim() }
    $compatibility = if ($null -eq $env:AI_WORKFLOW_BUNDLE_SIGNING_SECRET) { "" } else { $env:AI_WORKFLOW_BUNDLE_SIGNING_SECRET.Trim() }
    $knownPlaceholder = "replace-with-a-random-64-character-hex-secret"
    if (-not [string]::IsNullOrWhiteSpace($primary) -and $primary -ne $knownPlaceholder) { return }
    if (-not [string]::IsNullOrWhiteSpace($compatibility)) {
        [Environment]::SetEnvironmentVariable("PLAN_BUNDLE_SIGNING_SECRET", $compatibility, "Process")
        return
    }

    $generated = New-RandomHexSecret 32
    Set-DotEnvValue $Path "PLAN_BUNDLE_SIGNING_SECRET" $generated
    [Environment]::SetEnvironmentVariable("PLAN_BUNDLE_SIGNING_SECRET", $generated, "Process")
    Write-Host "  [OK] Generated and persisted the shared Part A/Part B bundle signing secret." -ForegroundColor Green
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
    # 显式 UTF-8 读取 + $OutputEncoding=UTF8 喂给 mysql,避免 PS5.1 默认 ASCII/GBK
    # 破坏 init.sql 中文与动态 SQL 单引号(触发 ERROR 1064)。try/finally 恢复,
    # 不影响后续原生进程调用;不设 [Console]::OutputEncoding 以免影响中文显示。
    $prevOutputEncoding = $OutputEncoding
    try {
        $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
        Get-Content -LiteralPath $InitSqlPath -Raw -Encoding UTF8 |
            & docker exec -e "MYSQL_PWD=$Password" -i ai-workflow-mysql mysql --default-character-set=utf8mb4 "-u$User"
    } finally {
        $OutputEncoding = $prevOutputEncoding
    }
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

function Get-PortListenerPids([int]$Port) {
    # netstat is intentionally used instead of Get-NetTCPConnection here. On some Windows hosts
    # Get-NetTCPConnection can block for a long time or transiently miss a listener during startup.
    $seen = @{}
    foreach ($line in @(& netstat -ano 2>$null)) {
        if ($line -match "^\s*TCP\s+\S+:$Port\s+\S+\s+LISTENING\s+(\d+)\s*$") {
            $listenerPid = [int]$Matches[1]
            if (-not $seen.ContainsKey($listenerPid)) {
                $seen[$listenerPid] = $true
                Write-Output $listenerPid
            }
        }
    }
}

function Stop-PortListener([int]$Port, [string]$ServiceName) {
    $listenerPids = @(Get-PortListenerPids $Port)
    foreach ($listenerPid in $listenerPids) {
        $process = Get-Process -Id $listenerPid -ErrorAction SilentlyContinue
        if ($process) {
            Write-Host "  Stop old $ServiceName process $($process.Name) (PID $($process.Id), port $Port)" -ForegroundColor Yellow
            Stop-Process -Id $process.Id -Force -ErrorAction Stop
        }
    }

    $deadline = (Get-Date).AddSeconds(15)
    do {
        $remaining = @(Get-PortListenerPids $Port)
        if ($remaining.Count -eq 0) { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    Stop-WithError "$ServiceName old listener did not stop on port $Port (PID: $($remaining -join ', '))."
}

function Initialize-ReferenceProject([string]$ReferenceRoot) {
    if ([string]::IsNullOrWhiteSpace($ReferenceRoot)) {
        Write-Host "  [WARN] REFERENCE_PROJECT_ROOT is empty; reference indexing is skipped." -ForegroundColor Yellow
        return
    }
    $resolvedRoot = [System.IO.Path]::GetFullPath($ReferenceRoot)
    if (-not (Test-Path -LiteralPath $resolvedRoot -PathType Container)) {
        Stop-WithError "Reference project directory does not exist: $resolvedRoot"
    }
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($env:AI_WORKFLOW_ADMIN_TOKEN)) {
        $headers["X-Admin-Token"] = $env:AI_WORKFLOW_ADMIN_TOKEN
    }
    $json = @{ path = $resolvedRoot } | ConvertTo-Json -Compress
    try {
        $response = Invoke-RestMethod -Method Post `
            -Uri "http://127.0.0.1:8111/api/project/reference" `
            -Headers $headers `
            -ContentType "application/json; charset=utf-8" `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) `
            -TimeoutSec 180
        if ($response.success -ne $true -or $response.data.ready -ne $true) {
            Stop-WithError "Part B did not report the reference project as ready: $resolvedRoot"
        }
        Write-Host "  [OK] Reference project ready: $resolvedRoot ($($response.data.indexedClasses) classes)" -ForegroundColor Green
    }
    catch {
        Stop-WithError "Reference project initialization failed: $($_.Exception.Message)"
    }
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
Ensure-PlanBundleSigningSecret $EnvPath
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
$referenceRoot = if ($null -ne $env:REFERENCE_PROJECT_ROOT) { $env:REFERENCE_PROJECT_ROOT.Trim() } else { "" }
if ($referenceRoot -and -not (Test-Path -LiteralPath ([System.IO.Path]::GetFullPath($referenceRoot)) -PathType Container)) {
    Stop-WithError "REFERENCE_PROJECT_ROOT does not exist: $referenceRoot"
}

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
if (-not (Wait-Http "Part B" "http://127.0.0.1:8111/actuator/health/readiness" 180)) {
    Stop-WithError "Part B 启动超时，请检查 ai-workflow 窗口中的错误日志。"
}

Initialize-ReferenceProject $referenceRoot

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
