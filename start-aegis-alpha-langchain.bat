@echo off
setlocal

cd /d "%~dp0"

echo [Aegis Alpha] Starting frontend, backend, and LangGraph/LangChain engine...
echo [Aegis Alpha] Current directory: %CD%
echo.
echo [Aegis Alpha] Note: without AEGIS_ALPHA_LANGCHAIN_PROVIDER=openai and
echo [Aegis Alpha] AEGIS_ALPHA_LANGCHAIN_API_KEY, the LangGraph engine returns local test output.
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-aegis-alpha.ps1" -StartLangGraph %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
  echo [Aegis Alpha] Startup failed. Check the logs directory for details.
) else (
  echo [Aegis Alpha] Startup command completed.
)

echo Press any key to close this window...
pause >nul
exit /b %EXIT_CODE%
