# 源机打包脚本:把当前工作区压成 bladex-deploy-*.{zip|rar}
# 跑完后把压缩包拷到目标机,解压,跑 deploy.ps1 即可。
#
# 排除:node_modules / target / dist / logs / .git / 生成产物 / 参考项目 / 凭证
# 工具优先级:7-Zip(zip,推荐) > WinRAR(rar) > Compress-Archive(zip,慢)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

$Stamp     = Get-Date -Format 'yyyyMMdd-HHmmss'
$BaseName  = "bladex-deploy-$Stamp"

Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  打包 bladex 工作区" -ForegroundColor Cyan
Write-Host "  源:   $Root" -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

# 探测压缩工具
$sevenZ = $null
foreach ($p in @(
    "$env:ProgramFiles\7-Zip\7z.exe",
    "${env:ProgramFiles(x86)}\7-Zip\7z.exe",
    "C:\Program Files\7-Zip\7z.exe"
)) {
    if (Test-Path $p) { $sevenZ = $p; break }
}

$winRar = $null
foreach ($p in @(
    "$env:ProgramFiles\WinRAR\Rar.exe",
    "${env:ProgramFiles(x86)}\WinRAR\Rar.exe",
    "C:\Program Files\WinRAR\Rar.exe"
)) {
    if (Test-Path $p) { $winRar = $p; break }
}

# 排除清单(目录与文件混合,递归)
$excludeDirs = @(
    "node_modules", "target", "dist", "logs",
    ".git", ".idea", ".vscode",
    "ai-generated-modules",   # 工作流产物,目标机会重新生成
    "blade_hgsjy",            # 参考项目,工作流不依赖
    "_compile-check"          # 临时编译验证目录
)
$excludeFiles = @(".env", "*.log", "*.tmp")

# 选定输出路径(扩展名根据工具决定)
if ($sevenZ -or -not $winRar) {
    $Ext = "zip"
} else {
    $Ext = "rar"  # WinRAR Rar.exe 原生输出 .rar,目标机用 WinRAR/Win11 内置都能解
}
$OutPath = Join-Path $Root "..\$BaseName.$Ext"

Write-Host "  目标: $OutPath" -ForegroundColor Cyan
Write-Host ""

if ($sevenZ) {
    Write-Host "[INFO] 使用 7-Zip: $sevenZ" -ForegroundColor Green
    $args = @("a", "-tzip", "-mx5", $OutPath, ".\*")
    foreach ($d in $excludeDirs) { $args += "-xr!$d" }
    foreach ($f in $excludeFiles) { $args += "-xr!$f" }
    & $sevenZ @args
    if ($LASTEXITCODE -ne 0) { Write-Host "[FAIL] 7-Zip 打包失败" -ForegroundColor Red; exit 1 }
}
elseif ($winRar) {
    Write-Host "[INFO] 使用 WinRAR: $winRar (rar 格式)" -ForegroundColor Green
    # -r 递归  -ep1 不含本目录前缀  -m3 中等压缩  -y 全部确认  -idq 静默
    # -x*\name\* 排除目录及其下;-xname 也排除空目录条目
    $rarArgs = @("a", "-r", "-ep1", "-m3", "-y", $OutPath)
    foreach ($d in $excludeDirs) {
        $rarArgs += "-x*\$d\*"
        $rarArgs += "-x*\$d"
    }
    foreach ($f in $excludeFiles) {
        $rarArgs += "-x$f"
    }
    $rarArgs += ".\*"
    & $winRar @rarArgs
    if ($LASTEXITCODE -gt 1) { Write-Host "[FAIL] WinRAR 打包失败 (exit=$LASTEXITCODE)" -ForegroundColor Red; exit 1 }
}
else {
    Write-Host "[INFO] 未找到 7-Zip / WinRAR,使用 Compress-Archive(很慢且排除有限)" -ForegroundColor Yellow
    Write-Host "[TIP] 装 7-Zip(https://www.7-zip.org/)或 WinRAR 可显著加速,且排除更彻底" -ForegroundColor Yellow
    # PowerShell 原生:Compress-Archive 不支持递归排除,只能拦顶层
    $items = Get-ChildItem -Path $Root -Force | Where-Object {
        $_.Name -notin $excludeDirs -and $_.Name -notin @(".env") -and $_.Extension -ne ".log"
    }
    Compress-Archive -Path $items.FullName -DestinationPath $OutPath -CompressionLevel Optimal -Force
}

# ─── 自检:产物里别混进 node_modules / target / dist ─────
$size = (Get-Item $OutPath).Length / 1MB
Write-Host ""
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Green
Write-Host "  打包完成" -ForegroundColor Green
Write-Host "  文件: $OutPath" -ForegroundColor Green
Write-Host "  大小: $([math]::Round($size, 1)) MB" -ForegroundColor Green
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Green
Write-Host ""

# 体积超过 50 MB 通常意味着 node_modules / dist 混进去了
if ($size -gt 50) {
    Write-Host "[WARN] 包体 $([math]::Round($size,1)) MB 超出预期(轻包应 < 20 MB)。" -ForegroundColor Yellow
    Write-Host "       可能是 node_modules / dist 没被正确排除。" -ForegroundColor Yellow
    Write-Host "       建议装 7-Zip 或 WinRAR(本机已检测到 WinRAR 则已使用)。" -ForegroundColor Yellow
    Write-Host ""
}

# ─── 核心自检:包内文件数 vs 源文件数对账(防"只打了空目录"的漏光)─────
# 统计源文件数(应用与打包相同的排除规则)
$srcCount = (Get-ChildItem -Path $Root -Recurse -File -Force | Where-Object {
    $rel = $_.FullName.Substring($Root.Length)
    $ok = $true
    foreach ($d in $excludeDirs) {
        if ($rel -like "*\$d\*" -or $rel -like "*\$d") { $ok = $false; break }
    }
    if ($ok) {
        foreach ($f in $excludeFiles) {
            $pat = $f -replace '\*','.*'
            if ($_.Name -like $f) { $ok = $false; break }
        }
    }
    $ok
}).Count

# 列出包内文件数(按工具)
$archCount = 0
if ($sevenZ) {
    $listing = & $sevenZ l $OutPath 2>&1
    # 7z 输出:文件行 = 至少一个字符在路径列,目录行带斜杠结尾
    $archCount = ($listing | Select-String -Pattern '^\s+\S+\s+\S+\s+\d{4}-\d\d-\d\d\s+\d\d:\d\d:\d\d\s+\S+$' | Measure-Object).Count
    # 备用:统计非目录的条目(路径不以 \ 或 / 结尾)
    $archCount = ($listing | Where-Object { $_ -match '^\s+\S+\s+\d+\s+\d{4}-' -and $_ -notmatch '[\\/]$' }).Count
} elseif ($winRar) {
    $listing = & $winRar l $OutPath 2>&1
    # Rar 输出文件行标记 ..A.... ,目录行 ...D...
    $archCount = ($listing | Select-String -Pattern '^\s*\.\.A').Count
} else {
    # Compress-Archive 产 zip,用 .NET 读
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [System.IO.Compression.ZipFile]::OpenRead($OutPath)
    try { $archCount = ($z.Entries | Where-Object { $_.Length -ge 0 -and -not $_.FullName.EndsWith('/') }).Count }
    finally { $z.Dispose() }
}

Write-Host "[自检] 源文件数: $srcCount | 包内文件数: $archCount" -ForegroundColor Cyan
if ($archCount -lt ($srcCount * 0.9)) {
    Write-Host ""
    Write-Host "[FAIL] 包内文件数 $archCount 远少于源文件数 $srcCount!" -ForegroundColor Red
    Write-Host "       打包漏文件了(可能只打了空目录)。请检查工具参数。" -ForegroundColor Red
    exit 1
}
if ($archCount -gt ($srcCount * 1.1)) {
    Write-Host "[WARN] 包内文件数 $archCount 明显多于源文件数 $srcCount,可能混入了被排除目录的内容。" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "下一步:" -ForegroundColor Yellow
Write-Host "  1. 把 $BaseName.$Ext 拷到目标机" -ForegroundColor Yellow
Write-Host "  2. 解压到任意目录" -ForegroundColor Yellow
Write-Host "  3. 在解压目录跑 .\deploy.ps1" -ForegroundColor Yellow
