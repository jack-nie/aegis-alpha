param(
    [string]$FrontendUrl = "http://127.0.0.1:5174",
    [string]$BackendUrl = "http://127.0.0.1:5178",
    [string]$LangGraphUrl = "http://127.0.0.1:8787",
    [int]$TimeoutSeconds = 5
)

$ErrorActionPreference = "Stop"

function Invoke-SmokeRequest {
    param(
        [string]$Name,
        [string]$Url,
        [int[]]$ExpectedStatuses
    )

    try {
        $Response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec $TimeoutSeconds
        $StatusCode = [int]$Response.StatusCode
    } catch {
        if ($_.Exception.Response) {
            $StatusCode = [int]$_.Exception.Response.StatusCode
        } else {
            throw "[$Name] request failed before HTTP response: $($_.Exception.Message)"
        }
    }

    if ($ExpectedStatuses -notcontains $StatusCode) {
        throw "[$Name] expected HTTP $($ExpectedStatuses -join '/') but got $StatusCode from $Url"
    }

    Write-Host "[PASS] $Name -> HTTP $StatusCode"
}

Invoke-SmokeRequest -Name "frontend" -Url "$FrontendUrl/" -ExpectedStatuses @(200)
Invoke-SmokeRequest -Name "backend-auth-guard" -Url "$BackendUrl/_backend/auth/me" -ExpectedStatuses @(401, 403)
Invoke-SmokeRequest -Name "langgraph-health" -Url "$LangGraphUrl/health" -ExpectedStatuses @(200)

Write-Host "Aegis Alpha release smoke passed."
