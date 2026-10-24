# Aegis Alpha Platform

Aegis Alpha is an investment research and AI agent workflow orchestration platform. It covers portfolio management, market-data hydration, LangGraph-based agent execution, backtests, audit traces, recommendation governance, and model governance.

## Modules

| Module | Stack | Role |
|--------|--------|------|
| `aegis-alpha-api` | Spring Boot 2.7 / Java 8 source level (built with JDK 17 in CI) / MyBatis / MySQL | REST API, auth, portfolio/backtest/governance, workflow runtime glue |
| `aegis-alpha-web` | Next.js 15 / React 19 / JSX / Tailwind | Web console (App Router) |
| `aegis-alpha-orchestrator` | Python 3.11+ / FastAPI / LangGraph 1.2 (tested pins: `constraints.txt`) | Agent execution, intent routing, streaming workflow engine |

Runtime still accepts existing `AEGIS_ALPHA_*` environment variables for local compatibility.

## Local Start / Stop（本地启动与停止）

所有服务（orchestrator → backend → web）由一个入口统一管理：Linux / macOS / Git Bash 用 `./aegis.sh`，
Windows 用 `.\aegis.ps1`（或双击 / 在 cmd 中运行 `aegis.cmd`），子命令和参数一致；也可以用 `make up` 等快捷方式。

```bash
./aegis.sh up                 # 按顺序启动全部服务并等待健康检查通过
./aegis.sh status             # 进程、端口、健康状态
./aegis.sh logs web -f        # 查看 / 跟踪日志（logs/<服务>.log）
./aegis.sh down               # 停止本脚本启动的全部进程
./aegis.sh up backend         # 只启动某个服务：orchestrator | backend | web
./aegis.sh setup              # 安装依赖（orchestrator .venv、web node_modules、backend 编译）
```

```powershell
.\aegis.ps1 up               # 参数同上，例如 .\aegis.ps1 restart -WebMode prod -NoBrowser
.\aegis.ps1 down
```

Open:

```text
http://127.0.0.1:5174
```

`.env`（参考 `.env.example`）会被自动读取；未配置服务令牌时脚本会生成本地随机值，不需要 `AEGIS_ALPHA_DEV_MODE`。
MySQL / Redis 需要事先运行（`./aegis.sh infra up` 用 docker compose 只启动这两个）。详见
[docs/本地启动说明.md](docs/本地启动说明.md)。

## Verification

Unit/build checks (no running services required for API/orchestrator tests; web needs `npm`):

```powershell
# API
Push-Location .\aegis-alpha-api; mvn -B -ntp test; Pop-Location

# Orchestrator (mock LLM; no real model key required)
Push-Location .\aegis-alpha-orchestrator
$env:AEGIS_ALPHA_LANGCHAIN_MOCK = "true"
$env:AEGIS_ALPHA_LANGCHAIN_API_KEY = "test-key"
$env:AEGIS_ALPHA_NODE_EXECUTION_TOKEN = "local-workflow-node-token"
python -m pytest tests -q --tb=short
Pop-Location

# Web
Push-Location .\aegis-alpha-web; npm ci; npm run test:portfolio-list; npm run build; Pop-Location
```

Runtime smoke / health (services must already be up via `./aegis.sh up` / `.\aegis.ps1 up`):

```powershell
# Full smoke (frontend + API auth guard + orchestrator health + token auth), uses the launcher's token/ports
.\aegis.ps1 smoke            # Linux / macOS: ./aegis.sh smoke

# Or call the PowerShell smoke script directly
.\scripts\smoke-aegis-alpha.ps1
```

Optional URL / token overrides:

```powershell
$env:AEGIS_ALPHA_FRONTEND_URL = "http://127.0.0.1:5174"
$env:AEGIS_ALPHA_BACKEND_URL = "http://127.0.0.1:5178"
$env:AEGIS_ALPHA_ORCHESTRATOR_URL = "http://127.0.0.1:8787"
$env:AEGIS_ALPHA_NODE_EXECUTION_TOKEN = "local-workflow-node-token"
.\scripts\smoke-aegis-alpha.ps1
```

Default ports: web `5174`, API `5178`, orchestrator `8787`.

Service token / CORS (Phase 1 hardening):

- `AEGIS_ALPHA_NODE_EXECUTION_TOKEN` has **no default** in the API or the orchestrator; set the same
  value for both (generate your own random string; do not commit it). Without it the orchestrator
  answers non-public routes with `503 service_token_not_configured`.
- The sample value `local-workflow-node-token` used above is accepted by the orchestrator **only** when
  `AEGIS_ALPHA_DEV_MODE=true` or `AEGIS_ALPHA_LANGCHAIN_MOCK=true` is set explicitly; otherwise it
  answers `503 service_token_is_example_value` and logs a warning at startup.
- `AEGIS_ALPHA_CORS_ALLOWED_ORIGINS` (comma-separated, `*` ignored) controls the orchestrator CORS
  allow-list; the default only allows the local web/API origins (`http://127.0.0.1:5174`,
  `http://localhost:5174`, `http://127.0.0.1:5178`, `http://localhost:5178`).
- `docker-compose.aegis-alpha.yml` now requires `AEGIS_ALPHA_NODE_EXECUTION_TOKEN` in `.env`.
