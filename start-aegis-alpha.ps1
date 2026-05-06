param(
    [int]$BackendPort = 5178,
    [int]$FrontendPort = 5174,
    [int]$LangGraphPort = 8787,
    [int]$BackendTimeoutSeconds = 180,
    [switch]$SkipInstall,
    [switch]$StartLangGraph,
    [switch]$SkipLangGraph,
    [switch]$FrontendDev,
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
if (-not $PSBoundParameters.ContainsKey("StartLangGraph")) {
    $StartLangGraph = -not $SkipLangGraph
}

$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Join-Path $RootDir "aegis-alpha-api"
$FrontendDir = Join-Path $RootDir "aegis-alpha-web"
$LangGraphDir = Join-Path $RootDir "aegis-alpha-orchestrator"
$LogDir = Join-Path $RootDir "logs"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

foreach ($ProxyVariable in @("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "all_proxy", "npm_config_proxy", "npm_config_https_proxy")) {
    Remove-Item -Path "Env:$ProxyVariable" -ErrorAction SilentlyContinue
}

function Write-Step {
    param([string]$Message)
    Write-Host "[Aegis Alpha] $Message"
}

function Resolve-FirstExistingPath {
    param([string[]]$Candidates)
    foreach ($Candidate in $Candidates) {
        if ($Candidate -and (Test-Path $Candidate)) {
            return (Resolve-Path $Candidate).Path
        }
    }
    return $null
}

function Resolve-CommandPath {
    param(
        [string[]]$Names,
        [string[]]$Fallbacks
    )
    foreach ($Name in $Names) {
        $Command = Get-Command $Name -ErrorAction SilentlyContinue
        if ($Command) {
            return $Command.Source
        }
    }
    return Resolve-FirstExistingPath $Fallbacks
}

function Test-Port {
    param([int]$Port)
    $Client = New-Object System.Net.Sockets.TcpClient
    try {
        $Async = $Client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $Async.AsyncWaitHandle.WaitOne(500)) {
            return $false
        }
        $Client.EndConnect($Async)
        return $true
    } catch {
        return $false
    } finally {
        $Client.Close()
    }
}

function Test-HttpResponsive {
    param([string]$Url)
    try {
        Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2 | Out-Null
        return $true
    } catch {
        if ($_.Exception.Response) {
            return $true
        }
        return $false
    }
}

function Stop-ProcessTree {
    param([System.Diagnostics.Process]$Process)
    if ($Process -and -not $Process.HasExited) {
        & taskkill.exe /PID $Process.Id /T /F | Out-Null
    }
}

function Wait-BackendReady {
    param(
        [int]$Port,
        [int]$TimeoutSeconds,
        [System.Diagnostics.Process]$Process
    )

    $Deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $Url = "http://127.0.0.1:$Port/_backend/auth/me"

    while ((Get-Date) -lt $Deadline) {
        if ($Process -and $Process.HasExited) {
            throw "Backend process exited. Check log: $(Join-Path $LogDir 'backend.err.log')"
        }

        if ((Test-Port $Port) -and (Test-HttpResponsive $Url)) {
            return
        }

        Start-Sleep -Seconds 2
    }

    Stop-ProcessTree $Process
    throw "Backend startup timed out after $TimeoutSeconds seconds. Check log: $(Join-Path $LogDir 'backend.out.log')"
}

function Ensure-Directory {
    param([string]$Path, [string]$Name)
    if (-not (Test-Path $Path)) {
        throw "Missing $Name directory: $Path"
    }
}

function Import-UserEnvironmentVariable {
    param([string]$Name)
    if (-not [Environment]::GetEnvironmentVariable($Name, "Process")) {
        $Value = [Environment]::GetEnvironmentVariable($Name, "User")
        if ($Value) {
            Set-Item -Path "Env:$Name" -Value $Value
        }
    }
}

Ensure-Directory $BackendDir "backend"
Ensure-Directory $FrontendDir "frontend"
if ($StartLangGraph) {
    Ensure-Directory $LangGraphDir "LangGraph engine"
}

$Maven = Resolve-CommandPath `
    -Names @("mvn.cmd", "mvn") `
    -Fallbacks @(
        "$env:MAVEN_HOME\bin\mvn.cmd",
        "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.8.7-bin\678cc9d4\apache-maven-3.8.7\bin\mvn.cmd",
        "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.6.3-bin\1iopthnavndlasol9gbrbg6bf2\apache-maven-3.6.3\bin\mvn.cmd",
        "C:\Program Files\JetBrains\IntelliJ IDEA 2024.3.5\plugins\maven\lib\maven3\bin\mvn.cmd"
    )

$Bun = Resolve-CommandPath `
    -Names @("bun.exe", "bun") `
    -Fallbacks @(
        "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Oven-sh.Bun_Microsoft.Winget.Source_8wekyb3d8bbwe\bun-windows-x64\bun.exe",
        "$env:USERPROFILE\.bun\bin\bun.exe"
    )

$Npm = Resolve-CommandPath `
    -Names @("npm.cmd", "npm") `
    -Fallbacks @(
        "$env:ProgramFiles\nodejs\npm.cmd",
        "$env:APPDATA\npm\npm.cmd"
    )

$Node = Resolve-CommandPath `
    -Names @("node.exe", "node") `
    -Fallbacks @(
        "$env:ProgramFiles\nodejs\node.exe"
    )

if (-not $Maven) {
    throw "Maven was not found. Install Maven or add mvn.cmd to PATH."
}
if (-not $Npm) {
    throw "npm was not found. Install Node.js or add npm.cmd to PATH."
}
if (-not $Node) {
    throw "Node.js was not found. Install Node.js or add node.exe to PATH."
}

Write-Step "Maven: $Maven"
Write-Step "npm: $Npm"
Write-Step "Node: $Node"

Import-UserEnvironmentVariable "OPENAI_API_KEY"
Import-UserEnvironmentVariable "MARKETMIND_LANGCHAIN_API_KEY"
Import-UserEnvironmentVariable "MARKETMIND_LANGCHAIN_PROVIDER"
Import-UserEnvironmentVariable "MARKETMIND_LANGCHAIN_MODEL"
Import-UserEnvironmentVariable "OPENAI_BASE_URL"
Import-UserEnvironmentVariable "MARKETMIND_LANGCHAIN_BASE_URL"
Import-UserEnvironmentVariable "MARKETMIND_LANGCHAIN_TIMEOUT_MS"
if ($env:OPENAI_API_KEY -and (-not $env:MARKETMIND_LANGCHAIN_API_KEY)) {
    $env:MARKETMIND_LANGCHAIN_API_KEY = $env:OPENAI_API_KEY
}
if ($env:OPENAI_BASE_URL -and (-not $env:MARKETMIND_LANGCHAIN_BASE_URL)) {
    $env:MARKETMIND_LANGCHAIN_BASE_URL = $env:OPENAI_BASE_URL
}
if (-not $env:MARKETMIND_LANGCHAIN_PROVIDER) {
    $env:MARKETMIND_LANGCHAIN_PROVIDER = "openai"
}
if (-not $env:MARKETMIND_LANGCHAIN_MODEL) {
    $env:MARKETMIND_LANGCHAIN_MODEL = "deepseek-v4-flash"
}
if (-not $env:MARKETMIND_LANGCHAIN_TIMEOUT_MS) {
    $env:MARKETMIND_LANGCHAIN_TIMEOUT_MS = "25000"
}

if ($StartLangGraph) {
    $env:MARKETMIND_LANGCHAIN_ENABLED = "true"
    $env:MARKETMIND_LANGCHAIN_ENGINE_URL = "http://127.0.0.1:$LangGraphPort"
    $env:MARKETMIND_LANGGRAPH_PORT = "$LangGraphPort"
    $LangGraphNodeModules = Join-Path $LangGraphDir "node_modules"
    if ((-not $SkipInstall) -and (-not (Test-Path $LangGraphNodeModules))) {
        Write-Step "LangGraph node_modules is missing. Running npm install ..."
        Push-Location $LangGraphDir
        try {
            & $Npm install
            if ($LASTEXITCODE -ne 0) {
                throw "LangGraph npm install failed."
            }
        } finally {
            Pop-Location
        }
    }
    if (Test-Port $LangGraphPort) {
        Write-Step "LangGraph port $LangGraphPort is already listening. Skip engine startup."
    } else {
        Write-Step "Starting LangGraph engine on port $LangGraphPort ..."
        $LangGraphProcess = Start-Process `
            -FilePath $Node `
            -ArgumentList @("server.mjs") `
            -WorkingDirectory $LangGraphDir `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $LogDir "langgraph.out.log") `
            -RedirectStandardError (Join-Path $LogDir "langgraph.err.log") `
            -PassThru
        $LangGraphDeadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $LangGraphDeadline) {
            if ($LangGraphProcess.HasExited) {
                throw "LangGraph process exited. Check log: $(Join-Path $LogDir 'langgraph.err.log')"
            }
            if ((Test-Port $LangGraphPort) -and (Test-HttpResponsive "http://127.0.0.1:$LangGraphPort/health")) {
                break
            }
            Start-Sleep -Seconds 1
        }
        if (-not (Test-Port $LangGraphPort)) {
            throw "LangGraph startup timed out. Check log: $(Join-Path $LogDir 'langgraph.err.log')"
        }
    }
}

$MySqlReady = Test-Port 3306
$RedisReady = Test-Port 6379
if (-not $MySqlReady) {
    Write-Warning "MySQL port 3306 is not listening. Backend may fail to start."
}
if (-not $RedisReady) {
    Write-Warning "Redis port 6379 is not listening. Backend may fail to start."
}

$BackendProcess = $null
if (Test-Port $BackendPort) {
    Write-Step "Backend port $BackendPort is already listening. Skip backend startup."
} else {
    Write-Step "Starting backend on port $BackendPort ..."
    $BackendOut = Join-Path $LogDir "backend.out.log"
    $BackendErr = Join-Path $LogDir "backend.err.log"

    $BackendProcess = Start-Process `
        -FilePath $Maven `
        -ArgumentList @("-DskipTests", "-Dmaven.test.skip=true", "spring-boot:run") `
        -WorkingDirectory $BackendDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput $BackendOut `
        -RedirectStandardError $BackendErr `
        -PassThru
}

Write-Step "Waiting for backend readiness ..."
Wait-BackendReady -Port $BackendPort -TimeoutSeconds $BackendTimeoutSeconds -Process $BackendProcess
Write-Step "Backend is ready: http://127.0.0.1:$BackendPort"

$NodeModules = Join-Path $FrontendDir "node_modules"
if ((-not $SkipInstall) -and (-not (Test-Path $NodeModules))) {
    Write-Step "Frontend node_modules is missing. Running npm install ..."
    Push-Location $FrontendDir
    try {
        & $Npm install
        if ($LASTEXITCODE -ne 0) {
            throw "npm install failed."
        }
    } finally {
        Pop-Location
    }
}

if (Test-Port $FrontendPort) {
    Write-Step "Frontend port $FrontendPort is already listening. Skip frontend startup."
} else {
    if (-not $FrontendDev) {
        Write-Step "Building frontend for production ..."
        Push-Location $FrontendDir
        try {
            & $Npm run build
            if ($LASTEXITCODE -ne 0) {
                throw "Frontend build failed."
            }
        } finally {
            Pop-Location
        }
    }

    $FrontendCommand = if ($FrontendDev) { "dev" } else { "start" }
    Write-Step "Starting frontend ($FrontendCommand) on port $FrontendPort ..."
    $FrontendOut = Join-Path $LogDir "frontend.out.log"
    $FrontendErr = Join-Path $LogDir "frontend.err.log"

    Start-Process `
        -FilePath $Npm `
        -ArgumentList @("run", $FrontendCommand) `
        -WorkingDirectory $FrontendDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput $FrontendOut `
        -RedirectStandardError $FrontendErr | Out-Null
}

Start-Sleep -Seconds 3
if (-not (Test-Port $FrontendPort)) {
    throw "Frontend failed to start or timed out. Check log: $(Join-Path $LogDir 'frontend.err.log')"
}

Write-Step "Frontend is ready: http://127.0.0.1:$FrontendPort"
Write-Step "Done. Open http://127.0.0.1:$FrontendPort"

if (-not $NoBrowser) {
    Start-Process "http://127.0.0.1:$FrontendPort"
}
