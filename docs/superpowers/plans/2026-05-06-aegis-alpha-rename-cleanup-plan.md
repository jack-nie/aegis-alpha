# Aegis Alpha Rename And Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the Aegis Alpha prototype workspace into the more formal Aegis Alpha Platform and remove obsolete prototype/capture/build artifacts without breaking the runnable stack.

**Architecture:** Keep the current three-service architecture: Spring Boot API, Next.js web app, and Node/LangGraph orchestrator. Rename only module directories and user-facing/product metadata; keep Java packages stable to avoid a broad package refactor.

**Tech Stack:** PowerShell, Spring Boot/Maven, Next.js/npm, Node.js, Docker Compose.

---

### Task 1: Stop Services

**Files:** none

- [ ] Stop listeners on ports `5174`, `5178`, and `8787`.
- [ ] Verify ports are free before moving directories.

### Task 2: Rename Core Modules

**Files:**
- Move: `aegis-alpha-api` to `aegis-alpha-api`
- Move: `aegis-alpha-web` to `aegis-alpha-web`
- Move: `aegis-alpha-orchestrator` to `aegis-alpha-orchestrator`

- [ ] Move the three active module directories.
- [ ] Confirm `pom.xml`, `package.json`, and `server.mjs` are present in the renamed directories.

### Task 3: Update Runtime References

**Files:**
- Modify: `start-aegis-alpha.ps1`
- Modify: `stop-aegis-alpha.ps1`
- Modify: `docker-compose.aegis-alpha.yml`
- Modify: `scripts/smoke-aegis-alpha.ps1`
- Modify: root batch wrappers

- [ ] Replace old module paths with renamed module paths.
- [ ] Keep existing ports and environment variable names stable for compatibility.
- [ ] Update visible script output from Aegis Alpha to Aegis Alpha.

### Task 4: Update Branding

**Files:**
- Modify: `aegis-alpha-web/package.json`
- Modify: `aegis-alpha-web/app/layout.jsx`
- Modify: `aegis-alpha-web/app/App.jsx`
- Modify: `aegis-alpha-api/pom.xml`
- Modify: `aegis-alpha-api/README.md`
- Modify: `aegis-alpha-orchestrator/package.json`
- Modify: `aegis-alpha-orchestrator/README.md`
- Modify: docs that describe the active product

- [ ] Replace user-facing product name with `Aegis Alpha Platform`.
- [ ] Leave internal `MARKETMIND_*` environment variables in place unless a file is pure documentation.

### Task 5: Remove Obsolete Artifacts

**Delete:**
- `marketmind-copy`
- `marketmind-copy-react`
- `alpha-source`
- `alpha-capture`
- root `local-*.png`
- root `local-*.json`
- root `target-portfolio*.png/json`
- `capture-alpha.ps1`
- `capture-portfolio.ps1`
- runtime logs and generated build folders: `logs`, `aegis-alpha-api/target`, `aegis-alpha-web/.next`, `aegis-alpha-web/node_modules`, `aegis-alpha-orchestrator/node_modules`

- [ ] Delete only these paths.
- [ ] Do not delete `.ai-memory`, `docs`, `.github`, `scripts`, `dify-docker`, or unrelated backup folders.

### Task 6: Restore Dependencies And Verify

**Commands:**
- `npm install` in `aegis-alpha-web`
- `npm install` in `aegis-alpha-orchestrator`
- `mvn test` in `aegis-alpha-api`
- `npm run smoke` in `aegis-alpha-orchestrator`
- `npm run test:market-data` in `aegis-alpha-orchestrator`
- `npm run build` in `aegis-alpha-web`
- `.\start-aegis-alpha.ps1`
- `.\scripts\smoke-aegis-alpha.ps1`

- [ ] Run every command and fix path/branding regressions until all pass.
