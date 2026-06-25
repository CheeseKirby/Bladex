@echo off
chcp 65001 >nul
:: 一键启动 3 个服务(双击即可运行,不受 PowerShell 执行策略限制)
:: 读 .env(deploy 生成)注入环境变量,各开独立窗口:
::   ai-workflow (Part B, Java)  -> 8110
::   ai-designer BFF (Part A)    -> 3001
::   ai-designer 前端 (Vite)     -> 3000
:: 关闭对应窗口即停止服务。
::
:: 注意: .env 中密码若含 & | < > ^ 等 cmd 特殊字符, 需用 ^ 转义(如 secret^&pass)。
:: 普通字母数字密码无此问题。
setlocal
:: 取脚本所在目录,去掉尾反斜杠(避免 start /D "path\" 的 \" 转义坑)
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"

cd /d "%ROOT%"

if not exist "%ROOT%\.env" (
    echo [错误] 找不到 .env 配置文件
    echo 请先运行 deploy 完成部署生成 .env
    echo.
    pause
    exit /b 1
)

echo 正在加载 .env 环境变量...
:: for /f: eol=# 跳过注释行; tokens=1,* delims== 取 key 与 value(含等号)
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ROOT%\.env") do (
    if not "%%a"=="" set "%%a=%%b"
)

echo.
echo ========================================
echo   启动 3 个服务(各开独立窗口)
echo ========================================
echo.

echo [1/3] 启动 ai-workflow (Part B, 端口 8110)...
:: 不用 -o:别的电脑首次可能没有完整 .m2 缓存,需联网拉依赖
start "ai-workflow (8110)" /D "%ROOT%\ai-developer" cmd /k "mvn spring-boot:run -pl ai-workflow -Dspring-boot.run.profiles=dev"

timeout /t 3 >nul

echo [2/3] 启动 ai-designer BFF (Part A, 端口 3001)...
start "ai-designer BFF (3001)" /D "%ROOT%\ai-designer" cmd /k "npm run server"

timeout /t 2 >nul

echo [3/3] 启动 ai-designer 前端 (端口 3000)...
start "ai-designer 前端 (3000)" /D "%ROOT%\ai-designer" cmd /k "npm run dev"

echo.
echo ========================================
echo   启动完成! 三个窗口已打开
echo   关闭对应窗口即停止该服务
echo ========================================
echo.
echo   前端页面: http://localhost:3000/
echo   BFF API:  http://localhost:3001/
echo   Part B:   http://localhost:8110/doc.html
echo.
echo 提示: ai-workflow 首次启动需联网拉依赖,可能要 1-2 分钟
echo       看到 "Started AiWorkflowApplication" 才算就绪
echo.
pause
