# Aegis Alpha Platform

Aegis Alpha is an investment research and AI agent workflow orchestration platform. It covers portfolio management, market-data hydration, LangGraph-based agent execution, backtests, audit traces, recommendation governance, and model governance.

## Modules

| Module | Stack | Role |
|--------|--------|------|
| `aegis-alpha-api` | Spring Boot / Java 17 / MyBatis / MySQL | REST API, auth, portfolio/backtest/governance, workflow runtime glue |
| `aegis-alpha-web` | Next.js 15 / React 19 / JSX / Tailwind | Web console (App Router) |
| `aegis-alpha-orchestrator` | Python 3.11 / FastAPI / LangGraph | Agent execution, intent routing, streaming workflow engine |

Runtime still accepts existing `AEGIS_ALPHA_*` environment variables for local compatibility.

## Local Start

```powershell
.\start-aegis-alpha.ps1
```

Open:

```text
http://127.0.0.1:5174
```

## Local Stop

```powershell
.\stop-aegis-alpha.ps1
```

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

Runtime smoke / health (services must already be up via `.\start-aegis-alpha.ps1`):

```powershell
# Full smoke (frontend + API auth guard + orchestrator health + token auth)
.\scripts\smoke-aegis-alpha.ps1

# Same checks, health-only entry point
.\scripts\health-check.ps1
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
