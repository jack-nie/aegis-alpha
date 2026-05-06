# Aegis Alpha Platform

Aegis Alpha Platform is an enterprise-style investment research and agent workflow orchestration system. It combines portfolio workflows, market-data hydration, LangGraph-based agent execution, backtest history, audit traces, and model governance surfaces.

## Modules

- `aegis-alpha-api` - Spring Boot API, workflow runtime, portfolio/backtest/governance services.
- `aegis-alpha-web` - Next.js web application.
- `aegis-alpha-orchestrator` - Node/LangGraph execution engine.

The runtime still accepts existing `MARKETMIND_*` environment variables for compatibility with earlier local configuration.

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

```powershell
Push-Location .\aegis-alpha-api
mvn test
Pop-Location

Push-Location .\aegis-alpha-orchestrator
npm run smoke
npm run test:market-data
Pop-Location

Push-Location .\aegis-alpha-web
npm run build
Pop-Location

.\scripts\smoke-aegis-alpha.ps1
```
