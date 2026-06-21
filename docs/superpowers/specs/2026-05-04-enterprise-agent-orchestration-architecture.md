# Enterprise Agent Orchestration Architecture

**Date:** 2026-05-04

**System:** Aegis Alpha Platform

**Goal:** Turn the current runnable LangGraph/LLM workflow MVP into an enterprise-grade financial AI orchestration platform with governed workflow execution, traceable recommendations, auditability, observability, model governance, controlled deployment, and verifiable test coverage.

**Current Baseline:** The system has a Java Spring Boot backend, a Next.js frontend, and a Node LangGraph service. It can run stock recommendation workflows, invoke real LLM nodes, record workflow node outputs, and link workflow results into backtest history. It is suitable as an Alpha/MVP, not yet as a production-grade enterprise platform.

**High-Risk Findings From Code Review:**
- The Java authentication path uses a custom HMAC token and SHA-256 password hash without salt. It must be hardened before production.
- `/profile` must not return the domain `User` object because it can expose password hash fields.
- Business tables and queries are not consistently tenant-scoped.
- The current workflow runtime is synchronous and serial inside Java, with no durable queue, retries, pause/resume, cancellation, or idempotent replay.
- The Node service imports LangChain components but currently executes nodes through a custom sequential HTTP handler rather than a durable LangGraph `StateGraph` checkpoint runtime.
- Java `RestTemplate` model/Dify calls need connection/read timeout, retry boundaries, and circuit breaker behavior.
- Model fallback currently can return `ok=true`; enterprise behavior must distinguish degraded success from model failure.
- Database evolution currently mixes `schema.sql` and a custom `SchemaMigrationRunner`; this must move to versioned migrations.

---

## 1. Target Architecture

### Logical Components

1. **Web Console**
   - Technology: Next.js, React, Tailwind, ReactFlow.
   - Responsibility: Workflow design, run center, trace inspection, agent management, portfolio/backtest views, audit and governance UI.
   - Boundary: No business rules that affect authorization, execution, persistence, or model governance. It only renders state and submits commands.

2. **API Gateway Layer**
   - Technology: Spring Boot controllers and filters.
   - Responsibility: authentication, authorization, tenant scoping, request correlation, API error normalization, audit event capture.
   - Boundary: Every endpoint receives an authenticated principal with `tenantId`, `userId`, roles, and permissions.

3. **Domain Services**
   - `WorkflowService`: definition, layout, validation, versioning, publishing.
   - `WorkflowRunService`: execution lifecycle, retries, cancellation, pause/resume, node status.
   - `AgentService`: agent templates, prompt versions, tool permissions, run history.
   - `ModelGovernanceService`: provider/model registry, rate limits, cost budgets, fallback policy.
   - `AuditService`: immutable user/system event stream.
   - `ObservabilityService`: run metrics, span ingestion, health snapshots.
   - `DataGovernanceService`: source catalog, lineage, evidence links, recommendation explainability.
   - `BacktestService`: strategy/backtest history and workflow result linking.

4. **Workflow Execution Engine**
   - Java owns durable workflow state and enterprise controls.
   - LangGraph service executes LLM/tool nodes and returns normalized node results.
   - Java schedules node execution, persists state transitions, enforces retries, validates idempotency, and records spans.

5. **LangGraph / LLM Worker**
   - Technology: Node.js, LangChain/OpenAI-compatible providers.
   - Responsibility: execute one node at a time or a bounded subgraph, normalize model output, collect usage, sources, signals, confidence, and fallback metadata.
   - Boundary: Stateless or lightly stateful worker. Durable execution state remains in Java database.

6. **Data Platform**
   - MySQL: system of record.
   - Redis: cache, leases, rate limit counters, run locks.
   - Optional later: object storage for large artifacts and exported evidence packs.

7. **Observability Stack**
   - Structured logs with `traceId`, `workflowRunId`, `nodeRunId`, `tenantId`, `userId`.
   - Metrics: Micrometer/Prometheus-compatible backend metrics and LangGraph worker metrics.
   - Traces: OpenTelemetry spans for request -> workflow run -> node -> model/tool call.
   - UI: run center and operations dashboard.

---

## 2. Security, Tenant Isolation, RBAC, Audit

### Required Capabilities

- Every persisted business object must carry `tenant_id`.
- Every API query must be tenant-scoped.
- Roles are not enough; introduce permissions such as:
  - `workflow:read`
  - `workflow:write`
  - `workflow:publish`
  - `workflow:run`
  - `agent:manage`
  - `model:admin`
  - `audit:read`
  - `portfolio:write`
  - `recommendation:approve`
- Add endpoint-level authorization with a reusable `AuthorizationService`.
- Add immutable audit events for:
  - login/logout
  - workflow create/update/delete/publish/run/cancel/retry
  - agent prompt/tool/model change
  - model config change
  - portfolio import/export
  - recommendation report export

### Core Tables

- `tenant`
- `user_role`
- `role_permission`
- `api_token`
- `audit_event`
- `audit_event_field_diff`

### Audit Event Shape

```json
{
  "eventId": "uuid",
  "tenantId": "tenant-1",
  "userId": "u-1",
  "actorType": "USER",
  "action": "workflow.publish",
  "resourceType": "workflow_definition",
  "resourceId": "stock_recommendation_research",
  "traceId": "uuid",
  "requestId": "uuid",
  "beforeJson": {},
  "afterJson": {},
  "ipAddress": "127.0.0.1",
  "userAgent": "browser",
  "createdAt": "2026-05-04T00:00:00"
}
```

---

## 3. Production Workflow Engine

### Lifecycle

Workflow definitions move through:

1. `DRAFT`
2. `VALIDATED`
3. `PUBLISHED`
4. `DEPRECATED`
5. `ARCHIVED`

Workflow runs move through:

1. `QUEUED`
2. `RUNNING`
3. `PAUSED`
4. `COMPLETED`
5. `FAILED`
6. `CANCELLED`
7. `PARTIALLY_COMPLETED`

Node runs move through:

1. `PENDING`
2. `RUNNING`
3. `RETRYING`
4. `PAUSED`
5. `SKIPPED`
6. `COMPLETED`
7. `FAILED`
8. `CANCELLED`

### Execution Guarantees

- **Retry:** per node retry policy: max attempts, backoff, retryable error categories.
- **Timeout:** workflow timeout and node timeout.
- **Idempotency:** `idempotency_key` on workflow run requests and node calls.
- **Pause/Resume:** pause after node boundary; persist state snapshot.
- **Cancellation:** cooperative cancellation before each node and before model call.
- **Compensation:** optional compensation handler for stateful tool nodes.
- **Version Pinning:** every run pins workflow definition version, agent version, prompt version, model config version.
- **Validation:** before publish and before run: acyclic graph, start/end reachability, required inputs, handler availability, output mapping, permissions.

### Core Tables

- `workflow_definition`
  - add `tenant_id`, `status`, `published_version`, `created_by`, `updated_by`
- `workflow_version`
  - immutable version snapshot containing layout JSON and validation result
- `workflow_run`
  - add `tenant_id`, `workflow_version_id`, `idempotency_key`, `requested_by`, `cancel_requested`, `paused_at`, `resume_token`
- `workflow_node_run`
  - add `attempt`, `max_attempts`, `timeout_ms`, `retry_policy_json`, `idempotency_key`, `state_snapshot_json`
- `workflow_run_event`
  - append-only timeline for user and system events
- `workflow_validation_issue`

---

## 4. Observability and Traceability

### Trace Model

Keep `agent_call_span`, but evolve it from a node-only trace table into full distributed span records:

- `trace_id`
- `span_id`
- `parent_span_id`
- `tenant_id`
- `workflow_run_id`
- `node_run_id`
- `span_kind`: `HTTP`, `WORKFLOW`, `NODE`, `MODEL`, `TOOL`, `DATABASE`
- `name`
- `status`
- `started_at`
- `completed_at`
- `latency_ms`
- `input_json`
- `output_json`
- `error_message`
- `prompt_tokens`
- `completion_tokens`
- `total_tokens`
- `cost_usd`
- `provider`
- `model_name`

### Metrics

- Workflow runs by status.
- Node runs by handler/status.
- P50/P95/P99 node latency.
- LLM token usage and cost by tenant/model/workflow.
- Fallback count.
- Retry count.
- Error category count.
- Queue depth and oldest queued run age.

### UI

- `/workflow/runs`: run center.
- `/workflow/runs/:runId`: run detail with timeline, node state, model/tool spans, input/output, error stack.
- `/ops/observability`: operational dashboard.

---

## 5. Model Governance

### Required Capabilities

- Model registry with provider, model, context limit, cost, status, default fallback.
- Prompt versioning for every agent and workflow inline prompt.
- Rate limits by tenant, user, workflow, and provider.
- Cost budget by tenant/day/month and workflow run.
- Fallback policy:
  - primary model
  - secondary model
  - deterministic fallback
  - fail-closed for high-risk financial outputs
- Output evaluation:
  - JSON schema validation
  - citation/source requirement
  - confidence calibration
  - refusal/insufficient-data state
  - recommendation risk disclaimer

### Core Tables

- `model_provider`
- `model_config`
- `model_rate_limit`
- `model_budget`
- `prompt_template`
- `prompt_version`
- `llm_call`
- `llm_output_evaluation`

---

## 6. Data Governance and Financial Explainability

### Source Governance

Every external or internal data item used by an agent must become an evidence record:

- source type: market, filing, news, web, portfolio, analyst, internal calculation
- source title
- source URL or internal object ID
- retrieval time
- freshness
- trust tier
- extraction method
- checksum

### Recommendation Explainability

Every stock recommendation must include:

- recommendation: `BUY`, `HOLD`, `SELL`, `WATCH`, `INSUFFICIENT_DATA`
- confidence
- time horizon
- target price or valuation range when available
- key positive evidence
- key negative evidence
- missing data
- risk factors
- portfolio impact
- compliance disclaimer
- source list
- workflow run and trace links

### Core Tables

- `data_source`
- `evidence_item`
- `workflow_evidence_link`
- `recommendation`
- `recommendation_rationale`
- `recommendation_risk`
- `recommendation_approval`

---

## 7. Deployment, Environments, Migration

### Environments

- `local`
- `test`
- `staging`
- `production`

### Docker

Services:

- `frontend`
- `backend`
- `langgraph`
- `mysql`
- `redis`

Required changes:

- Backend image builds Java jar.
- Frontend image uses Next standalone or production server.
- LangGraph image runs `server.mjs`.
- Next rewrite target must be configurable, not hardcoded to `127.0.0.1:5178`.
- LangGraph host must be configurable, not fixed to `127.0.0.1`.

### Migration

- Replace implicit `schema.sql` production initialization with Flyway or Liquibase.
- Keep dev seed separate from production migration.
- Add migration validation in CI.

### Secrets

- No production default password or token secret.
- `.env.example` only.
- Real secrets from environment or secret manager.
- CI secret scanning.

---

## 8. Frontend Information Architecture

### Navigation

- `首页`
- `AI 工作台`
  - `Agent 管理`
  - `工作流设计`
  - `运行中心`
  - `模型治理`
- `投资组合`
  - `组合列表`
  - `持仓`
  - `交易流水`
  - `组合快照`
- `研究与推荐`
  - `推荐历史`
  - `证据链`
  - `报告`
- `回测`
  - `回测管理`
  - `回测历史`
- `治理`
  - `审计日志`
  - `权限管理`
  - `数据源管理`
  - `系统健康`

### Design Direction

- Dense financial operations UI, not marketing UI.
- White/neutral base, restrained accent colors, clear hierarchy.
- Table-first for operational views.
- Drawers for detail inspection.
- Timeline for runs and audit.
- Inline status pills for states.
- No decorative gradients or large hero layouts.
- Use lucide icons for actions and states.

### Key UI Components

- `Shell`
- `PageHeader`
- `DataTable`
- `StatusPill`
- `MetricCard`
- `Timeline`
- `Drawer`
- `JsonViewer`
- `EvidenceList`
- `RunTracePanel`
- `WorkflowValidationPanel`
- `PermissionGate`
- `AuditEventTable`

---

## 9. Testing and Release Gates

### Backend

- Unit tests for services and validators.
- MockMvc contract tests for every API.
- Testcontainers MySQL/Redis integration tests.
- Workflow engine fault tests:
  - node timeout
  - retry success
  - retry exhaustion
  - pause/resume
  - cancellation
  - idempotent replay
  - invalid graph
  - unauthorized run

### LangGraph

- API fixture tests for:
  - success
  - malformed output
  - timeout
  - unsupported provider
  - mock/fallback
  - token usage extraction

### Frontend

- Static contract tests for page routes and critical labels.
- Component tests for shared UI.
- Playwright smoke:
  - login
  - create/open workflow
  - run stock recommendation workflow
  - inspect run trace
  - view backtest history
  - view portfolio list

### CI/CD

- `backend-test`
- `frontend-build-test`
- `langgraph-smoke`
- `integration-compose`
- `e2e-playwright`
- `security-scan`
- `migration-validate`

Release requires all gates green.

---

## 10. Senior Engineer Workstreams

### A. Senior Backend Engineer: Security and Governance

Scope:
- Principal context, tenant scoping, RBAC, audit event service.
- Endpoint authorization.
- Tenant-aware data access.

Primary files:
- `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/AuthService.java`
- `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/TokenService.java`
- `aegis-alpha-api/src/main/java/com/marketmind/alpha/controller/*`
- `aegis-alpha-api/src/main/resources/db/mysql/schema.sql`

Deliverables:
- `SecurityContextService`
- `AuthorizationService`
- `AuditService`
- `audit_event` schema
- tests for unauthorized/tenant isolation cases

### B. Senior Backend Engineer: Workflow Runtime

Scope:
- Workflow versioning, validation, run lifecycle, retries, timeouts, pause/resume/cancel, idempotency.

Primary files:
- `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/WorkflowService.java`
- `aegis-alpha-api/src/main/java/com/marketmind/alpha/mapper/WorkflowMapper.java`
- `aegis-alpha-api/src/main/java/com/marketmind/alpha/domain/WorkflowRun.java`
- `aegis-alpha-api/src/main/java/com/marketmind/alpha/domain/WorkflowNodeRun.java`

Deliverables:
- `WorkflowValidationService`
- `WorkflowVersionService`
- `WorkflowRunService`
- `NodeExecutionPolicy`
- `workflow_run_event`
- integration tests for retry, cancel, pause/resume, idempotency

### C. Senior AI Platform Engineer: LangGraph and Model Governance

Scope:
- Model registry, LLM call normalization, usage/cost tracking, rate limits, fallback policy, output evaluation.

Primary files:
- `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/LangChainGateway.java`
- `aegis-alpha-orchestrator/server.mjs`
- `aegis-alpha-orchestrator/scripts/smoke.mjs`

Deliverables:
- model config API
- `llm_call` persistence
- token/cost capture
- JSON schema validation
- model fallback tests

### D. Senior Data/Financial Engineer: Data Governance and Recommendation Explainability

Scope:
- Evidence records, source catalog, recommendation schema, portfolio impact, disclaimer and approval state.

Primary files:
- `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/BacktestService.java`
- `aegis-alpha-api/src/main/java/com/marketmind/alpha/domain/BacktestRun.java`
- `aegis-alpha-api/src/main/resources/db/mysql/schema.sql`

Deliverables:
- `EvidenceService`
- `RecommendationService`
- recommendation tables
- workflow evidence links
- tests for explainability payloads

### E. Senior Frontend Engineer: Enterprise Console

Scope:
- UI decomposition, professional financial design, run center, trace detail, audit views, model governance views.

Primary files:
- `aegis-alpha-web/app/App.jsx`
- `aegis-alpha-web/app/globals.css`

Deliverables:
- shared component library
- workflow run center
- backtest/recommendation detail
- audit log page
- model governance page
- Playwright smoke tests

### F. Senior Test/DevOps Engineer: Release Engineering

Scope:
- Docker, compose, CI/CD, migration tooling, test pyramid, fault injection, secret hygiene.

Primary files:
- `start-aegis-alpha.ps1`
- `stop-aegis-alpha.ps1`
- `aegis-alpha-api/pom.xml`
- `aegis-alpha-web/package.json`
- `aegis-alpha-orchestrator/package.json`

Deliverables:
- Dockerfiles
- compose stack
- CI jobs
- Flyway/Liquibase migration setup
- integration smoke scripts
- e2e tests

---

## 11. Delivery Phases

### Phase 1: Production Foundation

Objective: make the existing MVP controllable and auditable.

Deliver:
- tenant-aware principal
- RBAC checks
- audit events
- workflow validation
- run lifecycle states
- structured error contract
- frontend shared components and cleaned Chinese copy
- CI for current tests/builds

### Phase 2: Durable Runtime

Objective: make workflows reliable.

Deliver:
- workflow versioning/publish
- retry/timeout/idempotency
- pause/resume/cancel
- run events and trace spans
- model call persistence
- run center UI
- integration tests

### Phase 3: Governance and Explainability

Objective: make financial recommendations reviewable.

Deliver:
- model registry/budgets/rate limits
- prompt versions
- evidence catalog
- recommendation schema
- portfolio impact
- audit export
- recommendation detail UI

### Phase 4: Enterprise Release

Objective: make it deployable and operable.

Deliver:
- Docker/compose
- migration tooling
- staging/prod profiles
- secret hygiene
- observability dashboard
- Playwright E2E
- failure injection suite
- all release gates green

---

## 12. Acceptance Criteria

The system is enterprise-ready only when all conditions are true:

1. Every API endpoint enforces authentication, tenant scoping, and permission checks.
2. Every workflow definition has immutable versions and publish history.
3. Every run records lifecycle events, node runs, model/tool spans, token usage, and errors.
4. A failed node can retry according to policy; a run can be cancelled and resumed at node boundaries.
5. Every stock recommendation links to evidence, sources, trace, model version, prompt version, and risk explanation.
6. UI has run center, trace detail, audit log, model governance, and recommendation detail.
7. Docker compose can start frontend/backend/LangGraph/MySQL/Redis from a clean machine.
8. CI runs backend tests, frontend build/tests, LangGraph smoke, integration tests, E2E smoke, migration validation, and security scans.
9. No production secret relies on default values in source code.
10. All release gates pass from a clean checkout.
