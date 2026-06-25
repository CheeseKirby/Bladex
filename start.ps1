# 启动 3 个服务到独立 PowerShell 窗口
#
# 读 .env(由 deploy.ps1 生成),把环境变量注入到每个子窗口,然后启动:
#   - ai-workflow (Part B, Java)  → 8110
#   - ai-designer BFF (Part A)    → 3001
#   - ai-designer 前端 (Vite)     → 3000
#
# 关闭子窗口即停服务。

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$envPath = Join-Path $Root ".env"
if (-not (Test-Path $envPath)) {
    Write-Host "[FAIL] 找不到 .env。请先跑 deploy.ps1。" -ForegroundColor Red
    exit 1
}

# 解析 .env 成 KEY=VALUE 列表,供子窗口注入
$envLines = Get-Content $envPath | Where-Object {
    $_ -and -not $_.StartsWith("#") -and $_.Contains("=")
}

function Start-Window($title, $workDir, $cmd) {
    # 构造子 PowerShell 命令:先 set 所有环境变量,再 cd,再跑 cmd
    $setEnv = ($envLines | ForEach-Object {
        $kv = $_ -split "=", 2
        "`$env:$($kv[0]) = '$($kv[1] -replace "'", "''")'"
    }) -join "; "
    $full = "$setEnv; Set-Location '$workDir'; Write-Host '[$title] 启动...' -ForegroundColor Cyan; $cmd"
    Start-Process powershell -ArgumentList "-NoExit", "-Command", $full -WindowStyle Normal
}

Write-Host "启动 3 个服务..." -ForegroundColor Cyan

# 启动前清理占用 8110 的旧进程(上次没正常关闭的 ai-workflow),避免端口冲突启动失败
# 与 start.bat 的清理逻辑保持一致
$staleConn = Get-NetTCPConnection -LocalPort 8110 -State Listen -ErrorAction SilentlyContinue
if ($staleConn) {
    foreach ($c in @($staleConn)) {
        $p = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
        if ($p) {
            Write-Host "[清理] 停止占用 8110 的旧进程 $($p.Name) (PID $($c.OwningProcess))" -ForegroundColor Yellow
            Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep -Seconds 2
}

# Part B: ai-workflow
Start-Window "ai-workflow (8110)" `
    (Join-Path $Root "ai-developer") `
    "mvn spring-boot:run -pl ai-workflow -Dspring-boot.run.profiles=dev"

Start-Sleep -Seconds 2

# Part A BFF
Start-Window "ai-designer BFF (3001)" `
    (Join-Path $Root "ai-designer") `
    "npm run server"

Start-Sleep -Seconds 1

# Part A 前端
Start-Window "ai-designer 前端 (3000)" `
    (Join-Path $Root "ai-designer") `
    "npm run dev"

Write-Host ""
Write-Host "三个服务已在独立窗口启动,关闭窗口即停止。" -ForegroundColor Green
Write-Host "前端:    http://localhost:3000/" -ForegroundColor Yellow
Write-Host "BFF:     http://localhost:3001/" -ForegroundColor Yellow
Write-Host "Part B:  http://localhost:8110/doc.html" -ForegroundColor Yellow
