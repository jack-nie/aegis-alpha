
$ErrorActionPreference="Stop"

function Stop-PortListeners([int[]]$ports){
  foreach($p in $ports){
    try {
      Get-NetTCPConnection -State Listen -LocalPort $p -EA SilentlyContinue | ForEach-Object {
        $pid=[int]$_.OwningProcess
        if($pid -gt 0){ taskkill.exe /PID $pid /T /F | Out-Null }
      }
    } catch {}
  }
}

$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Join-Path $RootDir "aegis-alpha-api"
$FrontendDir = Join-Path $RootDir "aegis-alpha-web"
$LangGraphDir = Join-Path $RootDir "aegis-alpha-orchestrator"
$BackendPort = 5178
$FrontendPort = 5174
$LangGraphPort = 8787
$TimeoutSec = 180

Write-Host "[Aegis Alpha] Stopping current services ..."
Stop-PortListeners @($BackendPort, $FrontendPort, $LangGraphPort)

Write-Host "[Aegis Alpha] Building backend ..."
Push-Location $BackendDir
& mvn.cmd -DskipTests "-Dmaven.test.skip=true" package
if ($LASTEXITCODE -ne 0) { throw "Backend build failed." }
Pop-Location

Write-Host "[Aegis Alpha] Starting backend ..."
$backendProc = Start-Process -FilePath "mvn.cmd" -ArgumentList "-DskipTests","-Dmaven.test.skip=true","spring-boot:run" -WorkingDirectory $BackendDir -WindowStyle Hidden -PassThru

$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
  if ($backendProc.HasExited) { throw "Backend process exited early." }
  try {
    if (Test-NetConnection 127.0.0.1 -Port $BackendPort -InformationLevel Quiet) {
      try {
        Invoke-WebRequest -UseBasicParsing http://127.0.0.1:$BackendPort/_backend/auth/me -TimeoutSec 2 | Out-Null
        break
      } catch {
        if ($_.Exception.Response) { break }
      }
    }
  } catch {}
  Start-Sleep -Seconds 2
}
if (-not (Test-NetConnection 127.0.0.1 -Port $BackendPort -InformationLevel Quiet)) {
  throw "Backend failed to become ready."
}
Write-Host "[Aegis Alpha] Backend ready on http://127.0.0.1:$BackendPort"

Write-Host "[Aegis Alpha] Building frontend ..."
Push-Location $FrontendDir
& npm.cmd run build
if ($LASTEXITCODE -ne 0) { throw "Frontend build failed." }
Pop-Location

Write-Host "[Aegis Alpha] Starting frontend ..."
Start-Process -FilePath "npm.cmd" -ArgumentList "run","start" -WorkingDirectory $FrontendDir -WindowStyle Hidden | Out-Null

Write-Host "[Aegis Alpha] Preparing LangGraph engine ..."
$venvPython = Join-Path $LangGraphDir ".venv\Scripts\python.exe"
if (-not (Test-Path $venvPython)) {
  Write-Host "[Aegis Alpha] Creating Python venv for orchestrator ..."
  Push-Location $LangGraphDir
  & python -m venv .venv
  if ($LASTEXITCODE -ne 0) { throw "Venv creation failed." }
  & $venvPython -m pip install --upgrade pip
  if ($LASTEXITCODE -ne 0) { throw "Pip upgrade failed." }
  & $venvPython -m pip install -r requirements.txt
  if ($LASTEXITCODE -ne 0) { throw "Orchestrator requirements install failed." }
  Pop-Location
}
Start-Process -FilePath $venvPython -ArgumentList "-m","uvicorn","app.main:app","--host","127.0.0.1","--port","$LangGraphPort" -WorkingDirectory $LangGraphDir -WindowStyle Hidden | Out-Null

$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
  $fe = Test-NetConnection 127.0.0.1 -Port $FrontendPort -InformationLevel Quiet
  $lg = Test-NetConnection 127.0.0.1 -Port $LangGraphPort -InformationLevel Quiet
  if ($fe -and $lg) { break }
  Start-Sleep -Seconds 2
}

Write-Host "[Aegis Alpha] Build and restart complete."
Write-Host "Backend:    http://127.0.0.1:$BackendPort"
Write-Host "Frontend:   http://127.0.0.1:$FrontendPort"
Write-Host "LangGraph:  http://127.0.0.1:$LangGraphPort"

