param(
    [int]$BackendPort = 5178,
    [int]$FrontendPort = 5174,
    [int]$LangGraphPort = 8787,
    [switch]$IncludeLegacy,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Ports = New-Object System.Collections.Generic.List[int]
$Ports.Add($BackendPort)
$Ports.Add($FrontendPort)
$Ports.Add($LangGraphPort)
$StoppedProcessIds = New-Object System.Collections.Generic.HashSet[int]

if ($IncludeLegacy) {
    foreach ($Port in @(5173, 5177, 4173)) {
        if (-not $Ports.Contains($Port)) {
            $Ports.Add($Port)
        }
    }
}

function Write-Step {
    param([string]$Message)
    Write-Host "[Aegis Alpha] $Message"
}

function Get-ProcessCommandLine {
    param([int]$ProcessId)
    try {
        $ProcessInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop
        return $ProcessInfo.CommandLine
    } catch {
        return ""
    }
}

function Stop-ProcessTree {
    param(
        [int]$ProcessId,
        [string]$Reason
    )

    if ($ProcessId -le 0) {
        return
    }
    if ($StoppedProcessIds.Contains($ProcessId)) {
        return
    }
    [void]$StoppedProcessIds.Add($ProcessId)

    $Process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $Process) {
        return
    }

    $CommandLine = Get-ProcessCommandLine -ProcessId $ProcessId
    Write-Step "Stopping PID $ProcessId ($($Process.ProcessName)) - $Reason"
    if ($CommandLine) {
        Write-Host "  $CommandLine"
    }

    if ($DryRun) {
        return
    }

    & taskkill.exe /PID $ProcessId /T /F | Out-Null
}

function Stop-ListenersByPort {
    param([int[]]$TargetPorts)

    foreach ($Port in $TargetPorts) {
        $Connections = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
        if (-not $Connections) {
            Write-Step "Port $Port is not listening."
            continue
        }

        foreach ($Connection in $Connections) {
            $OwningProcessId = [int]$Connection.OwningProcess
            if ($OwningProcessId -le 0) {
                continue
            }
            Stop-ProcessTree -ProcessId $OwningProcessId -Reason "listening on port $Port"
        }
    }
}

function Stop-OrphanProjectProcesses {
    $OwnProcessIds = New-Object System.Collections.Generic.HashSet[int]
    $Current = Get-CimInstance Win32_Process -Filter "ProcessId = $PID" -ErrorAction SilentlyContinue
    while ($Current) {
        [void]$OwnProcessIds.Add([int]$Current.ProcessId)
        if (-not $Current.ParentProcessId) {
            break
        }
        $Current = Get-CimInstance Win32_Process -Filter "ProcessId = $($Current.ParentProcessId)" -ErrorAction SilentlyContinue
    }

    $ProjectMarkers = @(
        "aegis-alpha-api",
        "aegis-alpha-web",
        "aegis-alpha-orchestrator",
        "start-aegis-alpha.ps1"
    )

    $Candidates = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $CommandLine = $_.CommandLine
            if (-not $CommandLine) {
                return $false
            }
            foreach ($Marker in $ProjectMarkers) {
                if ($CommandLine -like "*$Marker*") {
                    return $true
                }
            }
            return $false
        }

    foreach ($Candidate in $Candidates) {
        if ($OwnProcessIds.Contains([int]$Candidate.ProcessId)) {
            continue
        }
        Stop-ProcessTree -ProcessId ([int]$Candidate.ProcessId) -Reason "matches Aegis Alpha command line"
    }
}

Write-Step "Stopping Aegis Alpha services from $RootDir"
if ($DryRun) {
    Write-Step "Dry run mode: no processes will be killed."
}

Stop-ListenersByPort -TargetPorts $Ports.ToArray()
Stop-OrphanProjectProcesses

Write-Step "Done."
