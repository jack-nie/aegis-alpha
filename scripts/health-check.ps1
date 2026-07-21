#requires -Version 5.1
<#
.SYNOPSIS
  One-click health check for Aegis Alpha local stack.

.DESCRIPTION
  Thin entry point over smoke-aegis-alpha.ps1 (same checks, same exit codes).
  Prefer this name when you only care about service liveness/auth guards.

  Override URLs/token via parameters or env:
    AEGIS_ALPHA_FRONTEND_URL
    AEGIS_ALPHA_BACKEND_URL
    AEGIS_ALPHA_ORCHESTRATOR_URL (or AEGIS_ALPHA_LANGGRAPH_URL)
    AEGIS_ALPHA_NODE_EXECUTION_TOKEN
#>
param(
    [string]$FrontendUrl = $(if ($env:AEGIS_ALPHA_FRONTEND_URL) { $env:AEGIS_ALPHA_FRONTEND_URL } else { "http://127.0.0.1:5174" }),
    [string]$BackendUrl = $(if ($env:AEGIS_ALPHA_BACKEND_URL) { $env:AEGIS_ALPHA_BACKEND_URL } else { "http://127.0.0.1:5178" }),
    [string]$OrchestratorUrl = $(
        if ($env:AEGIS_ALPHA_ORCHESTRATOR_URL) { $env:AEGIS_ALPHA_ORCHESTRATOR_URL }
        elseif ($env:AEGIS_ALPHA_LANGGRAPH_URL) { $env:AEGIS_ALPHA_LANGGRAPH_URL }
        else { "http://127.0.0.1:8787" }
    ),
    [string]$ServiceToken = $(if ($env:AEGIS_ALPHA_NODE_EXECUTION_TOKEN) { $env:AEGIS_ALPHA_NODE_EXECUTION_TOKEN } else { "local-workflow-node-token" }),
    [int]$TimeoutSeconds = 5
)

$smokeScript = Join-Path $PSScriptRoot "smoke-aegis-alpha.ps1"
if (-not (Test-Path -LiteralPath $smokeScript)) {
    Write-Host "[FAIL] missing $smokeScript" -ForegroundColor Red
    exit 1
}

& $smokeScript `
    -FrontendUrl $FrontendUrl `
    -BackendUrl $BackendUrl `
    -OrchestratorUrl $OrchestratorUrl `
    -ServiceToken $ServiceToken `
    -TimeoutSeconds $TimeoutSeconds

exit $LASTEXITCODE
