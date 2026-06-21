# setup-venv.ps1 — Setup Python virtual environment for Orchestrator
# Usage: .\setup-venv.ps1

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "  Setting up Python Virtual Environment" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan
Write-Host ""

# Check Python version
Write-Host "[1/4] Checking Python version..." -ForegroundColor Yellow
$pythonVersion = python --version 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "  ERROR: Python not found. Please install Python 3.11+" -ForegroundColor Red
    exit 1
}
Write-Host "  $pythonVersion" -ForegroundColor Green

# Create virtual environment
Write-Host ""
Write-Host "[2/4] Creating virtual environment..." -ForegroundColor Yellow
$venvPath = Join-Path $ScriptDir ".venv"
if (Test-Path $venvPath) {
    Write-Host "  Virtual environment already exists at $venvPath" -ForegroundColor DarkGray
} else {
    python -m venv $venvPath
    if ($LASTEXITcode -ne 0) {
        Write-Host "  ERROR: Failed to create virtual environment" -ForegroundColor Red
        exit 1
    }
    Write-Host "  Created: $venvPath" -ForegroundColor Green
}

# Activate and install dependencies
Write-Host ""
Write-Host "[3/4] Installing dependencies..." -ForegroundColor Yellow
$pythonExe = Join-Path $venvPath "Scripts\python.exe"
$pipExe = Join-Path $venvPath "Scripts\pip.exe"

& $pipExe install --upgrade pip -q
& $pipExe install -r (Join-Path $ScriptDir "requirements.txt") -q
& $pipExe install -r (Join-Path $ScriptDir "requirements-dev.txt") -q

if ($LASTEXITcode -ne 0) {
    Write-Host "  ERROR: Failed to install dependencies" -ForegroundColor Red
    exit 1
}
Write-Host "  Dependencies installed" -ForegroundColor Green

# Verify installation
Write-Host ""
Write-Host "[4/4] Verifying installation..." -ForegroundColor Yellow
$checkResult = & $pythonExe -c "import langgraph; import fastapi; print('OK')" 2>&1
if ($checkResult -eq "OK") {
    Write-Host "  Verification passed" -ForegroundColor Green
} else {
    Write-Host "  WARNING: Verification failed - $checkResult" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "  Setup Complete!" -ForegroundColor Green
Write-Host "======================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Virtual environment: $venvPath" -ForegroundColor White
Write-Host "  Python: $pythonExe" -ForegroundColor White
Write-Host ""
Write-Host "  To start the orchestrator:" -ForegroundColor DarkGray
Write-Host "    .\restart-local.ps1" -ForegroundColor White
Write-Host ""
