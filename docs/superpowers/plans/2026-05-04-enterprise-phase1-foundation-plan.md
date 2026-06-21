# Enterprise Phase 1 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the production foundation for Aegis Alpha Platform: safe auth/profile handling, audit logging, workflow validation/event visibility, model-call safety boundaries, enterprise UI shell improvements, and release verification gates.

**Architecture:** Keep the current Java + Next + LangGraph split. Java remains the source of durable workflow state and enterprise governance. LangGraph remains a node execution worker. Frontend becomes an operations console with explicit audit, run, and governance surfaces.

**Tech Stack:** Spring Boot 2.7, MyBatis, MySQL/H2 tests, Next.js 15, React 19, Tailwind, Node/LangChain.

---

## Workstream A: Senior Backend Engineer - Security and Audit

**Ownership:** `aegis-alpha-api/src/main/java/com/marketmind/alpha/security/**`, `aegis-alpha-api/src/main/java/com/marketmind/alpha/audit/**`, `AuthService`, `AuthController`, `TokenService`, audit schema/test files.

- [ ] Write a failing test that `/profile` does not expose `passwordHash`.
  - File: `aegis-alpha-api/src/test/java/com/marketmind/alpha/AuthServiceTest.java`
  - Expected before fix: JSON/profile or service object can expose password hash.

- [ ] Add a principal DTO and auth context.
  - Create `AuthenticatedPrincipal` with `userId`, `username`, `tenantId`, `roles`.
  - Add `AuthService.principal(String authorization)`.
  - Keep `AuthService.me()` compatible with existing frontend responses.

- [ ] Include roles in issued tokens.
  - Modify `TokenService.issue(...)` to accept roles.
  - Preserve old tests by keeping `tenant_id` in `/auth/me`.

- [ ] Change `/profile` to return a safe map/DTO.
  - Do not return `User`.
  - Include only `user_id`, `username`, `tenant_id`, `roles`.

- [ ] Add minimal audit logging.
  - Create `AuditEvent` domain and `AuditEventMapper`.
  - Create `AuditService.record(...)`.
  - Add `audit_event` table to MySQL and H2 schema/migration runner.
  - Log login success/failure, workflow run start, agent run, portfolio export/create when easy to instrument.

- [ ] Add audit endpoint.
  - `GET /_backend/admin/audit-events`
  - Must require authenticated user.
  - Return latest 100 events ordered descending.

- [ ] Run `mvn -Dtest=AuthServiceTest,ApiContractTest test`.

## Workstream B: Senior Backend Engineer - Workflow Runtime Foundation

**Ownership:** `WorkflowService`, `WorkflowController`, `WorkflowMapper`, workflow domain classes, workflow schema/test files.

- [ ] Add workflow validation service.
  - Create `WorkflowValidationService`.
  - Validate: at least one start node, no empty node IDs, all edges reference existing nodes, graph has no cycle, at least one terminal node.

- [ ] Write failing tests for invalid graph rejection.
  - Extend `ApiContractTest` or add `WorkflowValidationServiceTest`.
  - Test cycle and missing edge target.

- [ ] Validate layout on save and run.
  - `saveLayout` rejects invalid layout with clear message.
  - `start` rejects invalid layout before inserting completed data.

- [ ] Add workflow run events.
  - Create `WorkflowRunEvent` domain and mapper.
  - Add `workflow_run_event` table to MySQL/H2/schema migration runner.
  - Record `RUN_CREATED`, `NODE_STARTED`, `NODE_COMPLETED`, `NODE_FAILED`, `RUN_COMPLETED`, `RUN_FAILED`.

- [ ] Add run events endpoint.
  - `GET /_backend/workflow/runs/{runId}/events`
  - Return ordered events.

- [ ] Add basic idempotency support.
  - Accept `Idempotency-Key` in `POST /workflow/runs` and `/workflows/{workflowKey}/run`.
  - If the same key is seen for the same workflow/subject, return existing run.
  - Add `idempotency_key` column and index.

- [ ] Run `mvn -Dtest=ApiContractTest test`.

## Workstream C: Senior AI Platform Engineer - Model Gateway Safety

**Ownership:** `LangChainGateway`, LangGraph `server.mjs`, LangGraph smoke/tests.

- [ ] Add Java HTTP timeouts.
  - Replace raw `new RestTemplate()` with a configured request factory.
  - Add connect/read timeout properties with defaults.

- [ ] Preserve real LLM output compatibility.
  - Keep `runAgent` content/message compatibility from `summary`.

- [ ] Distinguish fallback from success.
  - LangGraph should return `ok=false` or `degraded=true` for model fallback depending on handler policy.
  - Java trace should preserve degraded/fallback status.

- [ ] Add token usage extraction when provider returns usage metadata.
  - Populate `prompt_tokens`, `completion_tokens`, `total_tokens` in node result data when available.

- [ ] Expand LangGraph smoke.
  - Test `/execute-node` success shape.
  - Test missing API key mock.
  - Test unsupported provider.
  - Test malformed model output normalization.

- [ ] Run `node --check server.mjs` and `npm run smoke`.

## Workstream D: Senior Frontend Engineer - Enterprise Console Foundation

**Ownership:** `aegis-alpha-web/app/App.jsx`, `globals.css`, new frontend tests under `scripts/`.

- [ ] Fix visible mojibake in primary navigation and core pages.
  - Scope: sidebar, header, Agent, Workflow, Backtest history, Portfolio.
  - Keep behavior unchanged.

- [ ] Add run center entry.
  - Navigation: `AI+ / 运行中心`.
  - Page uses existing `/workflow/runs` and `/workflow/runs/{runId}/nodes`.
  - Show status, workflow, subject, traceId, node count, started/completed, action to inspect trace.

- [ ] Add audit log page entry.
  - Navigation: `治理 / 审计日志`.
  - Page calls `/_backend/admin/audit-events`.
  - Show action, actor, resource, traceId, createdAt.
  - Gracefully handles 404/empty while backend work is in progress.

- [ ] Improve workflow run detail panel.
  - Show node timeline, status, duration, handler, output summary, error.
  - Keep JSON viewer available but secondary.

- [ ] Add frontend contract tests.
  - Ensure navigation contains 运行中心 and 审计日志.
  - Ensure workflow run center table markers exist.

- [ ] Run `npm run test:portfolio-list`, new frontend contract test, and `npm run build`.

## Workstream E: Senior Test/DevOps Engineer - Release Foundation

**Ownership:** root scripts/docs, Dockerfiles, CI config, package scripts. Avoid editing application business logic.

- [ ] Add `.env.example`.
  - Include backend DB/Redis/token/LangGraph variables.
  - Include frontend backend URL variable.
  - Include LangGraph provider/model/base URL variables.

- [ ] Add Dockerfiles.
  - `aegis-alpha-api/Dockerfile`
  - `aegis-alpha-web/Dockerfile`
  - `aegis-alpha-orchestrator/Dockerfile`

- [ ] Add local compose.
  - `docker-compose.aegis-alpha.yml`
  - Services: mysql, redis, backend, frontend, langgraph.

- [ ] Add CI workflow skeleton.
  - `.github/workflows/marketmind-ci.yml`
  - Jobs: backend-test, frontend-build, langgraph-smoke.

- [ ] Add release smoke script.
  - `scripts/smoke-aegis-alpha.ps1`
  - Check frontend 200, backend auth unauthorized returns expected, LangGraph health.

- [ ] Do not store real secrets in any committed file.

---

## Phase 1 Acceptance Criteria

- `mvn test` passes.
- `npm run build` passes.
- `npm run test:portfolio-list` and new frontend contract tests pass.
- `node --check server.mjs` passes.
- LangGraph smoke passes.
- `/profile` no longer exposes password hash.
- Workflow invalid layouts are rejected.
- Workflow run events are queryable.
- Audit events are queryable.
- Local frontend/backend/LangGraph still run on ports 5174/5178/8787.
