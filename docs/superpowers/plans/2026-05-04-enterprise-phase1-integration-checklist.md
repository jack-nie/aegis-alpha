# Enterprise Phase 1 Integration Checklist

Use this checklist after the five workstreams return.

## Merge Safety

- [ ] Confirm no two workers edited the same file in incompatible ways.
- [ ] Read every changed schema file before running migrations/tests.
- [ ] Confirm no real API key, token secret, database password, or cookie appears in new files.
- [ ] Confirm frontend copy changes do not remove existing route behavior.
- [ ] Confirm all new endpoints still use `_backend` prefix.

## Backend Verification

- [ ] `mvn test`
- [ ] `/profile` response excludes `passwordHash`.
- [ ] `/auth/me` remains compatible with the existing frontend.
- [ ] `/admin/audit-events` returns 401 without token and 200 with token.
- [ ] Invalid workflow layout is rejected with a clear error.
- [ ] Valid stock recommendation workflow still creates workflow run, node runs, trace, and backtest history.
- [ ] `GET /workflow/runs/{runId}/events` returns ordered events.

## LangGraph Verification

- [ ] `node --check server.mjs`
- [ ] `npm run smoke`
- [ ] Missing API key path is explicitly marked mock/degraded.
- [ ] Unsupported provider path is explicitly failed or degraded.
- [ ] Real LLM path still returns `summary` and Java `runAgent` still exposes `content/message`.

## Frontend Verification

- [ ] `npm run test:portfolio-list`
- [ ] New frontend contract tests pass.
- [ ] `npm run build`
- [ ] Frontend starts on port 5174.
- [ ] UI can navigate to Agent, Workflow, Run Center, Backtest History, Portfolio, and Audit Log.

## Release Verification

- [ ] Dockerfiles build or at least parse correctly.
- [ ] Compose contains frontend/backend/langgraph/mysql/redis and no real secrets.
- [ ] CI workflow does not depend on local absolute paths.
- [ ] Smoke script checks frontend, backend, and LangGraph.

## Final Local Runtime

- [ ] Backend listening on 5178.
- [ ] Frontend listening on 5174.
- [ ] LangGraph listening on 8787.
- [ ] `GET http://127.0.0.1:8787/health` returns `ok=true`.
