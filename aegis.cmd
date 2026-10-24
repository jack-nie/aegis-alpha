@echo off
rem aegis.cmd - cmd / double-click entry for aegis.ps1 (same commands: up, down, restart, status, logs, smoke, setup).
rem Double-click without arguments runs "up" and keeps the window open.
setlocal
cd /d "%~dp0"
if "%~1"=="" goto interactive
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0aegis.ps1" %*
exit /b %ERRORLEVEL%

:interactive
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0aegis.ps1" up
set "EXIT_CODE=%ERRORLEVEL%"
echo.
echo Press any key to close this window...
pause >nul
exit /b %EXIT_CODE%
