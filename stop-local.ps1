# stop-local.ps1 — Stop all Aegis Alpha local services
# Usage: .\stop-local.ps1

$ErrorActionPreference = "Continue"
Write-Host ""
Write-Host "Stopping Aegis Alpha services..." -ForegroundColor Yellow

foreach ($port in @(8787, 5178, 5174)) {
    $conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
    if ($conn) {
        $pids = $conn | Select-Object -ExpandProperty OwningProcess -Unique
        foreach ($p in $pids) {
            $proc = Get-Process -Id $p -ErrorAction SilentlyContinue
            $name = if ($proc) { $proc.ProcessName } else { "unknown" }
            Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
            Write-Host "  Stopped PID $p ($name) on port $port" -ForegroundColor Green
        }
    } else {
        Write-Host "  Port $port — not in use" -ForegroundColor DarkGray
    }
}

Write-Host ""
Write-Host "All services stopped." -ForegroundColor Green
Write-Host ""
