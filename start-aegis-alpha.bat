@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-aegis-alpha.ps1" -StartLangGraph %*
echo.
echo Press any key to close this window...
pause >nul
