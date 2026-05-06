@echo off
setlocal

cd /d "%~dp0"

echo [Aegis Alpha] Stopping project services...
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-aegis-alpha.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
  echo [Aegis Alpha] Stop command failed.
) else (
  echo [Aegis Alpha] Stop command completed.
)

echo Press any key to close this window...
pause >nul
exit /b %EXIT_CODE%
