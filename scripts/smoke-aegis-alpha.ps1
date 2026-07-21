#requires -Version 5.1
<#
.SYNOPSIS
  Runtime smoke / health checks for Aegis Alpha local stack (web + API + Python orchestrator).

.DESCRIPTION
  Verifies:
    1) Frontend root returns HTTP 200
    2) Backend /_backend/auth/me returns 401/403 without token
    3) Orchestrator /health returns 200
    4) Orchestrator POST /classify-intent without token returns 401
    5) Orchestrator POST /classify-intent with Bearer service token is not 401

  Exit code: 0 on all PASS, 1 if any FAIL.

.PARAMETER FrontendUrl
  Frontend base URL (default env AEGIS_ALPHA_FRONTEND_URL or http://127.0.0.1:5174)

.PARAMETER BackendUrl
  Backend base URL (default env AEGIS_ALPHA_BACKEND_URL or http://127.0.0.1:5178)

.PARAMETER OrchestratorUrl
  Orchestrator base URL (default env AEGIS_ALPHA_ORCHESTRATOR_URL / AEGIS_ALPHA_LANGGRAPH_URL or http://127.0.0.1:8787)

.PARAMETER ServiceToken
  Service token for orchestrator Bearer auth (default env AEGIS_ALPHA_NODE_EXECUTION_TOKEN or local-workflow-node-token)

.PARAMETER TimeoutSeconds
  Per-request timeout in seconds (default 5)
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

$ErrorActionPreference = "Continue"
$script:FailCount = 0
$script:PassCount = 0

function Get-HttpStatus {
    param(
        [string]$Method = "GET",
        [string]$Url,
        [hashtable]$Headers = @{},
        [string]$Body = $null,
        [string]$ContentType = "application/json"
    )

    try {
        $params = @{
            Uri             = $Url
            Method          = $Method
            UseBasicParsing = $true
            TimeoutSec      = $TimeoutSeconds
            Headers         = $Headers
        }
        if ($null -ne $Body) {
            $params["Body"] = $Body
            $params["ContentType"] = $ContentType
        }
        $response = Invoke-WebRequest @params
        return [int]$response.StatusCode
    } catch {
        $response = $_.Exception.Response
        if ($null -ne $response) {
            try {
                return [int]$response.StatusCode
            } catch {
                # Some PowerShell hosts expose StatusCode as enum / string
                return [int]([string]$response.StatusCode)
            }
        }
        return $null
    }
}

function Assert-Status {
    param(
        [string]$Name,
        [string]$Url,
        [int[]]$ExpectedStatuses,
        [string]$Method = "GET",
        [hashtable]$Headers = @{},
        [string]$Body = $null,
        [scriptblock]$ExtraAssert = $null
    )

    $status = Get-HttpStatus -Method $Method -Url $Url -Headers $Headers -Body $Body
    if ($null -eq $status) {
        Write-Host "[FAIL] $Name -> no HTTP response from $Url ($Method)" -ForegroundColor Red
        $script:FailCount++
        return
    }

    $ok = $ExpectedStatuses -contains $status
    if ($ok -and $null -ne $ExtraAssert) {
        try {
            $ok = [bool](& $ExtraAssert $status)
        } catch {
            $ok = $false
        }
    }

    if ($ok) {
        Write-Host "[PASS] $Name -> HTTP $status" -ForegroundColor Green
        $script:PassCount++
    } else {
        $expect = $ExpectedStatuses -join "/"
        Write-Host "[FAIL] $Name -> expected HTTP $expect, got $status from $Url ($Method)" -ForegroundColor Red
        $script:FailCount++
    }
}

Write-Host "Aegis Alpha smoke / health"
Write-Host "  frontend:     $FrontendUrl"
Write-Host "  backend:      $BackendUrl"
Write-Host "  orchestrator: $OrchestratorUrl"
Write-Host ""

# 1) Frontend
Assert-Status -Name "frontend" -Url "$FrontendUrl/" -ExpectedStatuses @(200)

# 2) Backend auth guard (unauthenticated)
Assert-Status -Name "backend-auth-guard" -Url "$BackendUrl/_backend/auth/me" -ExpectedStatuses @(401, 403)

# 3) Orchestrator health (public)
Assert-Status -Name "orchestrator-health" -Url "$OrchestratorUrl/health" -ExpectedStatuses @(200)

# 4) Orchestrator auth: classify-intent without token -> 401
Assert-Status `
    -Name "orchestrator-auth-denied" `
    -Url "$OrchestratorUrl/classify-intent" `
    -Method "POST" `
    -Body "{}" `
    -ExpectedStatuses @(401)

# 5) Orchestrator auth: classify-intent with Bearer service token -> not 401
$authHeaders = @{ Authorization = "Bearer $ServiceToken" }
$authStatus = Get-HttpStatus `
    -Method "POST" `
    -Url "$OrchestratorUrl/classify-intent" `
    -Headers $authHeaders `
    -Body "{}"

if ($null -eq $authStatus) {
    Write-Host "[FAIL] orchestrator-auth-bearer -> no HTTP response from $OrchestratorUrl/classify-intent (POST)" -ForegroundColor Red
    $script:FailCount++
} elseif ($authStatus -eq 401 -or $authStatus -eq 503) {
    Write-Host "[FAIL] orchestrator-auth-bearer -> expected not 401/503 with Bearer token, got $authStatus" -ForegroundColor Red
    $script:FailCount++
} else {
    # 200 / 422 / 500 are all acceptable for auth pass (body may fail validation or LLM mock)
    Write-Host "[PASS] orchestrator-auth-bearer -> HTTP $authStatus (auth accepted)" -ForegroundColor Green
    $script:PassCount++
}

Write-Host ""
Write-Host "Result: $($script:PassCount) passed, $($script:FailCount) failed"

if ($script:FailCount -gt 0) {
    Write-Host "Aegis Alpha smoke FAILED." -ForegroundColor Red
    exit 1
}

Write-Host "Aegis Alpha smoke passed." -ForegroundColor Green
exit 0
