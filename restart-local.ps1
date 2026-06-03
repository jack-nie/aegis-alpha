# restart-local.ps1 — Aegis Alpha one-click restart (all 3 services)
# Usage: .\restart-local.ps1

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "  Aegis Alpha — One-Click Restart" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan

# ── 1. Stop all existing processes ──
Write-Host ""
Write-Host "[1/4] Stopping existing processes..." -ForegroundColor Yellow
foreach ($port in @(8787, 5178, 5174)) {
    $conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
    if ($conn) {
        $pids = $conn | Select-Object -ExpandProperty OwningProcess -Unique | Where-Object { $_ -gt 0 }
        foreach ($p in $pids) {
            Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
            Write-Host "  Stopped PID $p (port $port)"
        }
    }
}
Start-Sleep -Seconds 3
Write-Host "  All stopped." -ForegroundColor Green

# ── 2. Start Orchestrator (port 8787) ──
Write-Host ""
Write-Host "[2/4] Starting Orchestrator on port 8787..." -ForegroundColor Yellow
$orchDir = Join-Path $Root "aegis-alpha-orchestrator"
$orchLog = "$env:TEMP\aegis-orchestrator.log"
$orchErr = "$env:TEMP\aegis-orchestrator-err.log"

$orchProc = Start-Process -FilePath "node" -ArgumentList "server.mjs" `
    -WorkingDirectory $orchDir -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $orchLog -RedirectStandardError $orchErr
Write-Host "  PID: $($orchProc.Id)"

# Wait for orchestrator to listen
$orchReady = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 500
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:8787/health" -TimeoutSec 1 -ErrorAction Stop
        $health = $r.Content | ConvertFrom-Json
        if ($health.ok) {
            $orchReady = $true
            Write-Host "  Orchestrator ready! (mock=$($health.mock), apiKey=$($health.hasApiKey))" -ForegroundColor Green
            break
        }
    } catch {}
}
if (-not $orchReady) {
    Write-Host "  WARNING: Orchestrator not responding after 15s" -ForegroundColor Red
    Write-Host "  Check: $orchErr" -ForegroundColor Red
}

# ── 3. Start Spring Boot (port 5178) ──
Write-Host ""
Write-Host "[3/4] Starting Spring Boot on port 5178 (profile: local)..." -ForegroundColor Yellow
$apiDir = Join-Path $Root "aegis-alpha-api"
$apiLog = "$env:TEMP\aegis-backend.log"
$apiErr = "$env:TEMP\aegis-backend-err.log"

# Load .env and pass as env vars to mvn
$envContent = Get-Content (Join-Path $Root ".env")
$envVars = @()
foreach ($line in $envContent) {
    if ($line -match '^\s*([^#][^=]+)=(.*)$') {
        $k = $Matches[1].Trim(); $v = $Matches[2].Trim()
        [System.Environment]::SetEnvironmentVariable($k, $v, "Process")
    }
}

$apiProc = Start-Process -FilePath "mvn" `
    -ArgumentList "spring-boot:run", "-Dspring-boot.run.profiles=local" `
    -WorkingDirectory $apiDir -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $apiLog -RedirectStandardError $apiErr
Write-Host "  PID: $($apiProc.Id)"

$backendReady = $false
for ($i = 0; $i -lt 120; $i++) {
    Start-Sleep -Seconds 1
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:5178/actuator/health" -TimeoutSec 2 -ErrorAction Stop
        if ($r.StatusCode -eq 200) {
            $backendReady = $true
            Write-Host "  Spring Boot ready!" -ForegroundColor Green
            break
        }
    } catch {}
    if ($i % 20 -eq 19) {
        Write-Host "  ... still starting ($($i+1)s)" -ForegroundColor DarkGray
    }
}
if (-not $backendReady) {
    Write-Host "  Still starting... (will continue in background)" -ForegroundColor DarkYellow
    Write-Host "  Check: $apiLog" -ForegroundColor DarkYellow
}

# ── 4. Start Frontend (port 5174) ──
Write-Host ""
Write-Host "[4/4] Starting Frontend on port 5174..." -ForegroundColor Yellow
$webDir = Join-Path $Root "aegis-alpha-web"
$webLog = "$env:TEMP\aegis-frontend.log"
$webErr = "$env:TEMP\aegis-frontend-err.log"

$feProc = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "npm run dev" `
    -WorkingDirectory $webDir -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $webLog -RedirectStandardError $webErr
Write-Host "  PID: $($feProc.Id)"

Start-Sleep -Seconds 5
Write-Host "  Frontend started." -ForegroundColor Green

# ── Summary ──
Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "  All services running!" -ForegroundColor Green
Write-Host "======================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Orchestrator : http://127.0.0.1:8787  PID: $($orchProc.Id)"
Write-Host "  Backend      : http://127.0.0.1:5178  PID: $($apiProc.Id)"
Write-Host "  Frontend     : http://127.0.0.1:5174  PID: $($feProc.Id)"
Write-Host ""
Write-Host "  Logs:" -ForegroundColor DarkGray
Write-Host "    Orchestrator: $orchLog" -ForegroundColor DarkGray
Write-Host "    Backend     : $apiLog" -ForegroundColor DarkGray
Write-Host "    Frontend    : $webLog" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  Open browser: http://127.0.0.1:5174" -ForegroundColor White
Write-Host "  Stop all    : .\stop-local.ps1" -ForegroundColor DarkGray
Write-Host ""

Start-Process "http://127.0.0.1:5174"
