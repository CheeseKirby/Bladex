@echo off
setlocal
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"

rem Delegate to the unified PowerShell launcher with policy bypass.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\start.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo One-click startup failed. Review the messages above and retry.
) else (
    echo All services are ready. This window can be closed.
)
pause
exit /b %EXIT_CODE%
