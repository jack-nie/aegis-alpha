#requires -Version 5.1
<#
.SYNOPSIS
  Aegis Alpha 本地开发统一入口（Windows PowerShell 5.1+ / PowerShell 7）。与 ./aegis.sh 用法一致。

.DESCRIPTION
  用法: .\aegis.ps1 <命令> [服务...] [选项]
    命令: up | down | restart | status | logs | smoke | setup | help
    服务: orchestrator | backend | web | all（默认 all；别名 orch/langgraph、api、frontend/fe）
    选项: -EnvFile <文件>      读取的 env 文件（默认 .\.env，可用 AEGIS_ENV_FILE 指定）
          -WebMode dev|prod    前端用 next dev（默认）或 build + next start
          -SkipInstall         缺少 node_modules / .venv 时不自动安装
          -Force               down/up 时也结束占用端口的非本脚本进程
          -Follow              logs 持续跟踪
          -Lines <n>           logs 显示的行数（默认 80）
          -NoBrowser           up 完成后不打开浏览器

  详见 docs/本地启动说明.md。
#>
param(
    [Parameter(Position = 0)][string]$Command = "help",
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Services = @(),
    [string]$EnvFile = $(if ($env:AEGIS_ENV_FILE) { $env:AEGIS_ENV_FILE } else { "" }),
    [ValidateSet("dev", "prod")][string]$WebMode = $(if ($env:AEGIS_WEB_MODE) { $env:AEGIS_WEB_MODE } else { "dev" }),
    [switch]$SkipInstall,
    [switch]$Force,
    [switch]$Follow,
    [int]$Lines = 80,
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunDir = Join-Path $Root ".run"
$LogDir = Join-Path $Root "logs"
if (-not $EnvFile) { $EnvFile = Join-Path $Root ".env" }
$AllServices = @("orchestrator", "backend", "web")
$OnWindows = ($PSVersionTable.PSEdition -eq "Desktop") -or ($IsWindows -eq $true)

function Info([string]$m) { Write-Host "[aegis] $m" -ForegroundColor Cyan }
function Ok([string]$m) { Write-Host "[aegis] $m" -ForegroundColor Green }
function Warn([string]$m) { Write-Host "[aegis] WARN: $m" -ForegroundColor Yellow }
function Fail([string]$m) { Write-Host "[aegis] ERROR: $m" -ForegroundColor Red; exit 1 }

function Show-Usage {
    $text = Get-Content -LiteralPath $PSCommandPath -Encoding UTF8
    $start = [array]::IndexOf($text, "<#"); $end = [array]::IndexOf($text, "#>")
    if ($start -ge 0 -and $end -gt $start) { $text[($start + 1)..($end - 1)] | Where-Object { $_ -notmatch '^\.(SYNOPSIS|DESCRIPTION)' } | Out-Host }
}

# ------------------------------------------------------------------ env

function Import-EnvFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($raw in Get-Content -LiteralPath $Path) {
        $line = $raw.Trim()
        if (-not $line -or $line.StartsWith("#")) { continue }
        if ($line.StartsWith("export ")) { $line = $line.Substring(7) }
        $idx = $line.IndexOf("=")
        if ($idx -lt 1) { continue }
        $k = $line.Substring(0, $idx).Trim()
        $v = $line.Substring($idx + 1).Trim()
        if ($k -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { continue }
        if ($v.Length -ge 2 -and (($v.StartsWith('"') -and $v.EndsWith('"')) -or ($v.StartsWith("'") -and $v.EndsWith("'")))) {
            $v = $v.Substring(1, $v.Length - 2)
        }
        # 进程环境 / 用户环境变量优先，不被文件覆盖。
        if (-not [Environment]::GetEnvironmentVariable($k, "Process")) {
            $user = $null
            if ($OnWindows) { $user = [Environment]::GetEnvironmentVariable($k, "User") }
            if ($user) { $v = $user }
            [Environment]::SetEnvironmentVariable($k, $v, "Process")
        }
    }
}

function New-Secret {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return (($bytes | ForEach-Object { $_.ToString("x2") }) -join "")
}

function Test-ProdMarker([string]$v) {
    if (-not $v) { return $false }
    $t = $v.Trim().ToLowerInvariant()
    return ($t -eq "prod" -or $t -eq "production")
}

function Initialize-Env {
    Import-EnvFile $EnvFile
    # 兼容旧 Windows 启动脚本的行为：未在进程/文件中出现的模型相关变量也从 Windows 用户环境变量读取。
    if ($OnWindows) {
        foreach ($n in @("OPENAI_API_KEY", "OPENAI_BASE_URL", "AEGIS_ALPHA_LANGCHAIN_API_KEY", "AEGIS_ALPHA_LANGCHAIN_PROVIDER",
                "AEGIS_ALPHA_LANGCHAIN_MODEL", "AEGIS_ALPHA_LANGCHAIN_BASE_URL", "AEGIS_ALPHA_DB_USER", "AEGIS_ALPHA_DB_PASSWORD",
                "AEGIS_ALPHA_DB_URL", "AEGIS_ALPHA_TOKEN_SECRET", "AEGIS_ALPHA_REDIS_HOST", "AEGIS_ALPHA_REDIS_PORT",
                "AEGIS_ALPHA_REDIS_PASSWORD", "AEGIS_ALPHA_NODE_EXECUTION_TOKEN")) {
            if (-not [Environment]::GetEnvironmentVariable($n, "Process")) {
                $u = [Environment]::GetEnvironmentVariable($n, "User")
                if ($u) { [Environment]::SetEnvironmentVariable($n, $u, "Process") }
            }
        }
    }
    New-Item -ItemType Directory -Force -Path $RunDir, $LogDir | Out-Null

    $script:OrchPort = if ($env:AEGIS_ALPHA_LANGGRAPH_PORT) { [int]$env:AEGIS_ALPHA_LANGGRAPH_PORT } else { 8787 }
    $script:BackendPort = if ($env:AEGIS_ALPHA_PORT) { [int]$env:AEGIS_ALPHA_PORT } else { 5178 }
    $script:WebPort = if ($env:AEGIS_ALPHA_FRONTEND_PORT) { [int]$env:AEGIS_ALPHA_FRONTEND_PORT } else { 5174 }
    $script:OrchHost = if ($env:AEGIS_ORCHESTRATOR_BIND_HOST) { $env:AEGIS_ORCHESTRATOR_BIND_HOST } else { "127.0.0.1" }
    $env:AEGIS_ALPHA_LANGGRAPH_PORT = "$OrchPort"
    $env:AEGIS_ALPHA_PORT = "$BackendPort"

    if ($env:AEGIS_ALPHA_DB_URL -and $env:AEGIS_ALPHA_DB_URL.Contains("//mysql:")) {
        $env:AEGIS_ALPHA_DB_URL = $env:AEGIS_ALPHA_DB_URL.Replace("//mysql:", "//127.0.0.1:")
        if (-not $script:QuietEnv) { Info "AEGIS_ALPHA_DB_URL 指向 compose 主机名 mysql，本地运行改为 127.0.0.1" }
    }
    if ($env:AEGIS_ALPHA_REDIS_HOST -eq "redis") { $env:AEGIS_ALPHA_REDIS_HOST = "127.0.0.1" }
    $env:AEGIS_ALPHA_LANGCHAIN_ENGINE_URL = "http://127.0.0.1:$OrchPort"
    $env:AEGIS_ALPHA_BACKEND_URL = "http://127.0.0.1:$BackendPort"
    if (-not $env:AEGIS_ALPHA_NODE_CALLBACK_BASE_URL) { $env:AEGIS_ALPHA_NODE_CALLBACK_BASE_URL = "http://127.0.0.1:$BackendPort" }
    if (-not $env:AEGIS_ALPHA_LANGCHAIN_ENABLED) { $env:AEGIS_ALPHA_LANGCHAIN_ENABLED = "true" }
    if ($env:OPENAI_API_KEY -and -not $env:AEGIS_ALPHA_LANGCHAIN_API_KEY) { $env:AEGIS_ALPHA_LANGCHAIN_API_KEY = $env:OPENAI_API_KEY }
    if ($env:OPENAI_BASE_URL -and -not $env:AEGIS_ALPHA_LANGCHAIN_BASE_URL) { $env:AEGIS_ALPHA_LANGCHAIN_BASE_URL = $env:OPENAI_BASE_URL }

    # 共享服务令牌 / 签名密钥：未配置时生成随机值，保存在 .run\secrets.env（已 gitignore）。
    $secrets = Join-Path $RunDir "secrets.env"
    if (-not $env:AEGIS_ALPHA_NODE_EXECUTION_TOKEN -or -not $env:AEGIS_ALPHA_TOKEN_SECRET) {
        if (-not (Test-Path -LiteralPath $secrets)) {
            $content = @(
                "# 由 aegis 启动脚本生成的本地开发密钥，仅本工作区使用，勿提交。",
                "AEGIS_ALPHA_NODE_EXECUTION_TOKEN=$(New-Secret)",
                "AEGIS_ALPHA_TOKEN_SECRET=$(New-Secret)"
            )
            [System.IO.File]::WriteAllLines($secrets, $content)
            if (-not $script:QuietEnv) { Info "已生成本地开发密钥: .run\secrets.env" }
        }
        Import-EnvFile $secrets
    }

    if (-not $env:AEGIS_ALPHA_LANGCHAIN_MOCK -and -not $env:AEGIS_ALPHA_LANGCHAIN_API_KEY) {
        $env:AEGIS_ALPHA_LANGCHAIN_MOCK = "true"
        if (-not $script:QuietEnv) { Info "未配置 AEGIS_ALPHA_LANGCHAIN_API_KEY / OPENAI_API_KEY，orchestrator 使用 mock 模式" }
    }

    $prof = @()
    if ($env:SPRING_PROFILES_ACTIVE) { $prof = $env:SPRING_PROFILES_ACTIVE.Split(",") }
    $isProd = (Test-ProdMarker $env:AEGIS_ALPHA_ENV) -or (@($prof | Where-Object { Test-ProdMarker $_ }).Count -gt 0)
    if ($isProd) {
        $problems = @()
        if ("$env:AEGIS_ALPHA_DEV_MODE".Trim().ToLowerInvariant() -eq "true") { $problems += "AEGIS_ALPHA_DEV_MODE=true" }
        if ("$env:AEGIS_ALPHA_NODE_EXECUTION_TOKEN".Trim() -eq "local-workflow-node-token") { $problems += "示例 AEGIS_ALPHA_NODE_EXECUTION_TOKEN" }
        if ("$env:AEGIS_ALPHA_TOKEN_SECRET".Trim() -eq "local-aegis-demo-secret") { $problems += "demo AEGIS_ALPHA_TOKEN_SECRET" }
        if ($problems.Count -gt 0) {
            Fail "AEGIS_ALPHA_ENV / SPRING_PROFILES_ACTIVE 标记为生产，但配置了: $($problems -join ', ')；服务会被 ProductionSafetyGuard 拒绝启动。本地开发请去掉生产标记。"
        }
        Warn "检测到生产标记（AEGIS_ALPHA_ENV / SPRING_PROFILES_ACTIVE）。本脚本面向本地开发，生产请用 docker compose。"
    }
    if ($env:AEGIS_ALPHA_REPLICAS -and $env:AEGIS_ALPHA_REPLICAS -ne "1") {
        Warn "AEGIS_ALPHA_REPLICAS=$($env:AEGIS_ALPHA_REPLICAS)，本脚本只启动单个 orchestrator 进程（见 docs/多副本设计.md）"
    }
    $np = "127.0.0.1,localhost"
    if ($env:NO_PROXY) { $np = "$np,$($env:NO_PROXY)" }
    $env:NO_PROXY = $np; $env:no_proxy = $np
}

# ------------------------------------------------------------------ helpers

function Resolve-Service([string]$s) {
    switch ($s.ToLowerInvariant()) {
        { $_ -in @("orchestrator", "orch", "langgraph", "python") } { return "orchestrator" }
        { $_ -in @("backend", "api", "java", "spring") } { return "backend" }
        { $_ -in @("web", "frontend", "fe", "ui") } { return "web" }
    }
    return $null
}

function Get-SvcPort([string]$s) { switch ($s) { "orchestrator" { $OrchPort } "backend" { $BackendPort } "web" { $WebPort } } }
function Get-HealthUrl([string]$s) {
    switch ($s) {
        "orchestrator" { "http://127.0.0.1:$OrchPort/health" }
        "backend" { "http://127.0.0.1:$BackendPort/_backend/auth/me" }
        "web" { "http://127.0.0.1:$WebPort/" }
    }
}
function Get-SvcTimeout([string]$s) {
    switch ($s) {
        "orchestrator" { if ($env:AEGIS_ORCHESTRATOR_TIMEOUT) { [int]$env:AEGIS_ORCHESTRATOR_TIMEOUT } else { 60 } }
        "backend" { if ($env:AEGIS_BACKEND_TIMEOUT) { [int]$env:AEGIS_BACKEND_TIMEOUT } else { 300 } }
        "web" { if ($env:AEGIS_WEB_TIMEOUT) { [int]$env:AEGIS_WEB_TIMEOUT } else { 180 } }
    }
}
function Get-PidFile([string]$s) { Join-Path $RunDir "$s.pid" }
function Get-LogFile([string]$s) { Join-Path $LogDir "$s.log" }
function Get-ErrLogFile([string]$s) { Join-Path $LogDir "$s.err.log" }

function Get-SvcProcess([string]$s) {
    $f = Get-PidFile $s
    if (-not (Test-Path -LiteralPath $f)) { return $null }
    $procId = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $f -Raw).Trim(), [ref]$procId)) { return $null }
    return (Get-Process -Id $procId -ErrorAction SilentlyContinue)
}

function Test-Port([int]$Port) {
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $a = $c.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $a.AsyncWaitHandle.WaitOne(500)) { return $false }
        $c.EndConnect($a); return $true
    } catch { return $false } finally { $c.Close() }
}

function Get-PortOwner([int]$Port) {
    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        $c = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($c) { return [int]$c.OwningProcess }
    } elseif (Get-Command ss -ErrorAction SilentlyContinue) {
        $m = (& ss -ltnpH "sport = :$Port" 2>$null | Out-String) | Select-String -Pattern 'pid=(\d+)'
        if ($m) { return [int]$m.Matches[0].Groups[1].Value }
    }
    return 0
}

function Get-HttpCode([string]$Url, [int]$TimeoutSec = 3) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec $TimeoutSec -NoProxy -ErrorAction Stop
        return [int]$r.StatusCode
    } catch [System.Management.Automation.ParameterBindingException] {
        try { return [int](Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec $TimeoutSec -ErrorAction Stop).StatusCode }
        catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } return 0 }
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

function Test-CodeOk([string]$s, [int]$code) {
    if ($s -eq "orchestrator") { return ($code -eq 200) }
    return ($code -ge 200 -and $code -lt 500)
}
function Test-Healthy([string]$s) { return (Test-CodeOk $s (Get-HttpCode (Get-HealthUrl $s) 5)) }

function Stop-Tree([int]$ProcId) {
    if ($ProcId -le 0) { return }
    if ($OnWindows) {
        & taskkill.exe /PID $ProcId /T /F 2>$null | Out-Null
    } else {
        # 非 Windows（PowerShell 7）：按进程组结束，便于在 Linux/macOS 上验证本脚本。
        & kill -TERM -- "-$ProcId" 2>$null; & kill -TERM $ProcId 2>$null
        for ($i = 0; $i -lt 30 -and (Get-Process -Id $ProcId -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Milliseconds 500 }
        & kill -KILL -- "-$ProcId" 2>$null; & kill -KILL $ProcId 2>$null
    }
    Start-Sleep -Milliseconds 500
}

function Start-Svc([string]$s, [string]$Dir, [string]$Exe, [string[]]$Arguments) {
    $log = Get-LogFile $s; $err = Get-ErrLogFile $s
    foreach ($f in @($log, $err)) { if (Test-Path -LiteralPath $f) { Move-Item -Force -LiteralPath $f -Destination "$f.1" } }
    $p = @{ FilePath = $Exe; ArgumentList = $Arguments; WorkingDirectory = $Dir; PassThru = $true
            RedirectStandardOutput = $log; RedirectStandardError = $err }
    if ($OnWindows) { $p.WindowStyle = "Hidden" }
    elseif (Get-Command setsid -ErrorAction SilentlyContinue) { $p.FilePath = "setsid"; $p.ArgumentList = @($Exe) + $Arguments }
    $proc = Start-Process @p
    Set-Content -LiteralPath (Get-PidFile $s) -Value $proc.Id
    return $proc
}

function Resolve-Exe([string[]]$Names) {
    foreach ($n in $Names) { $c = Get-Command $n -ErrorAction SilentlyContinue; if ($c) { return $c.Source } }
    return $null
}

function Get-Python {
    $d = Join-Path $Root "aegis-alpha-orchestrator"
    foreach ($c in @((Join-Path $d ".venv\Scripts\python.exe"), (Join-Path $d ".venv/bin/python"))) { if (Test-Path -LiteralPath $c) { return $c } }
    $p = Resolve-Exe @("python", "python3")
    if (-not $p) { Fail "未找到 python（需要 Python 3.11+）" }
    return $p
}

function Wait-Healthy([string]$s, $Proc) {
    $timeout = Get-SvcTimeout $s
    for ($t = 0; $t -lt $timeout; $t++) {
        if ($Proc.HasExited) {
            Remove-Item -Force -LiteralPath (Get-PidFile $s) -ErrorAction SilentlyContinue
            Warn "$s 进程已退出，日志末尾："
            foreach ($f in @((Get-LogFile $s), (Get-ErrLogFile $s))) { if (Test-Path $f) { Get-Content $f -Tail 30 | Out-Host } }
            Fail "$s 启动失败，完整日志: logs\$s.log / logs\$s.err.log"
        }
        if (Test-Healthy $s) { Ok "$s 就绪 (PID $($Proc.Id), ${t}s)"; return }
        Start-Sleep -Seconds 1
        if ((($t + 1) % 30) -eq 0) { Info "  ... $s 仍在启动 ($($t + 1)s)" }
    }
    Fail "$s 在 ${timeout}s 内未就绪（进程 $($Proc.Id) 仍在运行，.\aegis.ps1 logs $s 查看，.\aegis.ps1 down $s 停止）"
}

# ------------------------------------------------------------------ commands

function Invoke-SetupOrchestrator {
    $d = Join-Path $Root "aegis-alpha-orchestrator"
    if (-not (Test-Path (Join-Path $d ".venv"))) {
        $base = Resolve-Exe @("python", "python3"); if (-not $base) { Fail "未找到 python（需要 Python 3.11+）" }
        Info "创建虚拟环境 aegis-alpha-orchestrator\.venv"
        & $base -m venv (Join-Path $d ".venv"); if ($LASTEXITCODE -ne 0) { Fail "创建 venv 失败" }
    }
    $py = Get-Python
    & $py -m pip install -q --upgrade pip
    & $py -m pip install -q -c (Join-Path $d "constraints.txt") -r (Join-Path $d "requirements.txt") -r (Join-Path $d "requirements-dev.txt")
    if ($LASTEXITCODE -ne 0) { Fail "orchestrator 依赖安装失败" }
    Ok "orchestrator 依赖已安装 ($py)"
}

function Invoke-SetupWeb {
    $npm = Resolve-Exe @("npm.cmd", "npm"); if (-not $npm) { Fail "未找到 npm，请安装 Node.js 20+" }
    Info "安装前端依赖 (npm ci) ..."
    Push-Location (Join-Path $Root "aegis-alpha-web")
    try {
        & $npm ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) {
            # 较新的 npm（11+）对 lock 文件里缺少的可选平台依赖更严格；退回 npm install 且不改写 lock 文件。
            Warn "npm ci 失败，改用 npm install --no-save（不修改 package-lock.json）"
            & $npm install --no-save --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) { Fail "前端依赖安装失败" }
        }
    } finally { Pop-Location }
}

function Invoke-SetupBackend {
    $mvn = Resolve-Exe @("mvn.cmd", "mvn"); if (-not $mvn) { Fail "未找到 mvn（Maven）" }
    Push-Location (Join-Path $Root "aegis-alpha-api")
    try { & $mvn -B -ntp -q "-Dmaven.test.skip=true" compile; if ($LASTEXITCODE -ne 0) { Fail "backend 编译失败" } } finally { Pop-Location }
}

function Start-AegisService([string]$s) {
    $port = Get-SvcPort $s
    $proc = Get-SvcProcess $s
    if ($proc) {
        if (Test-Healthy $s) { Ok "$s 已在运行 (PID $($proc.Id), 端口 $port)，跳过"; return }
        Info "$s 进程 $($proc.Id) 存在但尚未就绪，继续等待"; Wait-Healthy $s $proc; return
    }
    Remove-Item -Force -LiteralPath (Get-PidFile $s) -ErrorAction SilentlyContinue
    if (Test-Port $port) {
        $owner = Get-PortOwner $port
        if ($Force -and $owner -gt 0) { Warn "端口 $port 被 PID $owner 占用，-Force 结束它"; Stop-Tree $owner }
        else { Fail "端口 $port 已被占用$(if ($owner) { "（PID $owner）" })，不是本脚本启动的 $s。请先停止该进程，或使用 -Force。" }
    }
    switch ($s) {
        "orchestrator" {
            $py = Get-Python
            & $py -c "import fastapi, uvicorn, langgraph" 2>$null
            if ($LASTEXITCODE -ne 0) {
                if ($SkipInstall) { Fail "orchestrator 依赖缺失，请先运行 .\aegis.ps1 setup orchestrator" }
                Invoke-SetupOrchestrator; $py = Get-Python
            }
            Info "启动 orchestrator: http://${OrchHost}:$port (mock=$($env:AEGIS_ALPHA_LANGCHAIN_MOCK))"
            $proc = Start-Svc $s (Join-Path $Root "aegis-alpha-orchestrator") $py @("-m", "uvicorn", "app.main:app", "--host", $OrchHost, "--port", "$port")
        }
        "backend" {
            $mvn = Resolve-Exe @("mvn.cmd", "mvn")
            if (-not $mvn -and $env:MAVEN_HOME) { $c = Join-Path $env:MAVEN_HOME "bin\mvn.cmd"; if (Test-Path $c) { $mvn = $c } }
            if (-not $mvn) { Fail "未找到 mvn（Maven），请安装 Maven 3.6+ 并加入 PATH（或设置 MAVEN_HOME）" }
            if (-not (Test-Port 3306)) { Warn "MySQL 3306 未监听，backend 可能启动失败" }
            $redisPort = if ($env:AEGIS_ALPHA_REDIS_PORT) { [int]$env:AEGIS_ALPHA_REDIS_PORT } else { 6379 }
            if (-not (Test-Port $redisPort)) { Warn "Redis $redisPort 未监听，dashboard 缓存会回退到数据库" }
            $profiles = if ($env:AEGIS_SPRING_PROFILES) { $env:AEGIS_SPRING_PROFILES } else { "local" }
            Info "启动 backend: http://127.0.0.1:$port (Spring profile: $profiles，首次需编译，较慢)"
            $proc = Start-Svc $s (Join-Path $Root "aegis-alpha-api") $mvn @("-B", "-ntp", "-q", "-Dmaven.test.skip=true", "spring-boot:run", "-Dspring-boot.run.profiles=$profiles")
        }
        "web" {
            $webDir = Join-Path $Root "aegis-alpha-web"
            if (-not (Test-Path (Join-Path $webDir "node_modules"))) {
                if ($SkipInstall) { Fail "aegis-alpha-web\node_modules 不存在，请先运行 .\aegis.ps1 setup web" }
                Invoke-SetupWeb
            }
            if ($BackendPort -ne 5178) { Warn "前端 next.config.mjs 把 /_backend 固定代理到 127.0.0.1:5178，当前 backend 端口为 $BackendPort" }
            $node = Resolve-Exe @("node.exe", "node"); if (-not $node) { Fail "未找到 node，请安装 Node.js 20+" }
            $next = Join-Path $webDir "node_modules\next\dist\bin\next"
            if (-not (Test-Path $next)) { $next = Join-Path $webDir "node_modules/next/dist/bin/next" }
            if ($WebMode -eq "prod") {
                Info "构建前端 (npm run build) ..."
                $npm = Resolve-Exe @("npm.cmd", "npm")
                Push-Location $webDir
                try { & $npm run build *> (Join-Path $LogDir "web.build.log"); if ($LASTEXITCODE -ne 0) { Fail "前端构建失败，日志: logs\web.build.log" } } finally { Pop-Location }
                Info "启动 web (next start): http://127.0.0.1:$port"
                $proc = Start-Svc $s $webDir $node @($next, "start", "--hostname", "127.0.0.1", "--port", "$port")
            } else {
                Info "启动 web (next dev): http://127.0.0.1:$port"
                $proc = Start-Svc $s $webDir $node @($next, "dev", "--hostname", "127.0.0.1", "--port", "$port")
            }
        }
    }
    Wait-Healthy $s $proc
}

function Stop-AegisService([string]$s) {
    $port = Get-SvcPort $s
    $proc = Get-SvcProcess $s
    if ($proc) { Info "停止 $s (PID $($proc.Id))"; Stop-Tree $proc.Id; Ok "$s 已停止" }
    else { Info "$s 未由本脚本运行" }
    Remove-Item -Force -LiteralPath (Get-PidFile $s) -ErrorAction SilentlyContinue
    if (Test-Port $port) {
        $owner = Get-PortOwner $port
        if ($Force -and $owner -gt 0) { Warn "端口 $port 仍被 PID $owner 占用，-Force 结束它"; Stop-Tree $owner }
        else { Warn "端口 $port 仍被占用$(if ($owner) { "（PID $owner）" })，不是本脚本启动的进程；需要时用 .\aegis.ps1 down $s -Force" }
    }
}

function Show-Status([string[]]$List) {
    $envLabel = if (Test-Path -LiteralPath $EnvFile) { $EnvFile } else { "$EnvFile（未找到，使用默认值）" }
    Info "服务状态（env: $envLabel）"
    foreach ($s in $List) {
        $port = Get-SvcPort $s; $code = Get-HttpCode (Get-HealthUrl $s) 5; $proc = Get-SvcProcess $s
        if ($proc) { $state = if (Test-CodeOk $s $code) { "running" } else { "starting/unhealthy" }; $procId = $proc.Id }
        elseif (Test-Port $port) { $state = "外部进程占用端口"; $procId = Get-PortOwner $port }
        else { $state = "stopped"; $procId = "-" }
        Write-Host ("  {0,-13} {1,-20} pid={2,-8} port={3,-5} health={4}  log=logs/{0}.log" -f $s, $state, $procId, $port, $code)
    }
}

function Invoke-Smoke {
    $script = Join-Path $Root "scripts\smoke-aegis-alpha.ps1"
    if (-not (Test-Path $script)) { $script = Join-Path $Root "scripts/smoke-aegis-alpha.ps1" }
    & $script -FrontendUrl "http://127.0.0.1:$WebPort" -BackendUrl "http://127.0.0.1:$BackendPort" `
        -OrchestratorUrl "http://127.0.0.1:$OrchPort" -ServiceToken $env:AEGIS_ALPHA_NODE_EXECUTION_TOKEN
    exit $LASTEXITCODE
}

# ------------------------------------------------------------------ main

$cmd = $Command.ToLowerInvariant()
if ($cmd -in @("help", "-h", "--help", "/?")) { Show-Usage; exit 0 }

$selected = New-Object System.Collections.Generic.List[string]
foreach ($a in $Services) {
    if ($a -eq "all") { foreach ($x in $AllServices) { $selected.Add($x) }; continue }
    $r = Resolve-Service $a
    if (-not $r) { Fail "未知服务: $a（可选 orchestrator | backend | web | all）" }
    $selected.Add($r)
}
if ($selected.Count -eq 0) { foreach ($x in $AllServices) { $selected.Add($x) } }
$ordered = @($AllServices | Where-Object { $selected.Contains($_) })
$reversed = @($ordered); [array]::Reverse($reversed)

$script:QuietEnv = -not ($cmd -in @("up", "start", "restart", "setup", "install"))
Initialize-Env

switch ($cmd) {
    { $_ -in @("up", "start") } {
        foreach ($s in $ordered) { Start-AegisService $s }
        Write-Host ""; Show-Status $ordered
        Ok "完成。打开 http://127.0.0.1:$WebPort ；停止: .\aegis.ps1 down"
        if ($OnWindows -and -not $NoBrowser -and ($ordered -contains "web")) { Start-Process "http://127.0.0.1:$WebPort" }
    }
    { $_ -in @("down", "stop") } { foreach ($s in $reversed) { Stop-AegisService $s } }
    "restart" {
        foreach ($s in $reversed) { Stop-AegisService $s }
        foreach ($s in $ordered) { Start-AegisService $s }
        Write-Host ""; Show-Status $ordered
    }
    { $_ -in @("status", "ps") } { Show-Status $ordered }
    { $_ -in @("logs", "log") } {
        $files = @()
        foreach ($s in $ordered) { foreach ($f in @((Get-LogFile $s), (Get-ErrLogFile $s))) { if (Test-Path -LiteralPath $f) { $files += $f } } }
        if ($files.Count -eq 0) { Fail "还没有日志（logs\ 下无对应文件）" }
        if ($Follow) { Get-Content -LiteralPath $files -Tail $Lines -Wait }
        else { foreach ($f in $files) { Write-Host "==> $f <==" -ForegroundColor DarkGray; Get-Content -LiteralPath $f -Tail $Lines } }
    }
    "smoke" { Invoke-Smoke }
    { $_ -in @("setup", "install") } {
        foreach ($s in $ordered) { switch ($s) { "orchestrator" { Invoke-SetupOrchestrator } "backend" { Invoke-SetupBackend } "web" { Invoke-SetupWeb } } }
    }
    default { Show-Usage; Fail "未知命令: $Command" }
}
