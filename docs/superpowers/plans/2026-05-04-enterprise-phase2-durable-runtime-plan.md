# Enterprise Phase 2 Durable Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add durable workflow runtime foundations: immutable versions, queued execution, explicit dispatch, pause/resume/cancel controls, node retry metadata, and run-center controls.

**Architecture:** Java remains the workflow state owner and LangGraph remains the node execution worker. Existing synchronous run endpoints remain compatible, while async mode persists queued runs and separates creation from dispatch. Frontend reads the same run APIs and adds operational controls.

**Tech Stack:** Spring Boot 2.7, MyBatis annotations, H2/MySQL schemas, React/Next App, Node test scripts.

---

## Task 1: Workflow Versioning

**Files:**
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/domain/WorkflowVersion.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/mapper/WorkflowMapper.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/WorkflowService.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/controller/WorkflowController.java`
- Modify: `aegis-alpha-api/src/main/resources/db/mysql/schema.sql`
- Modify: `aegis-alpha-api/src/test/resources/schema-h2.sql`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/config/SchemaMigrationRunner.java`
- Test: `aegis-alpha-api/src/test/java/com/marketmind/alpha/ApiContractTest.java`

- [x] Add a failing MockMvc test that saves a layout, publishes it with `POST /_backend/workflows/{workflowKey}/publish-version`, and receives `version = 1` with immutable `layoutJson`.
- [x] Add the `workflow_version` schema to MySQL/H2 and migration runner.
- [x] Add mapper methods `insertVersion`, `findLatestVersion`, and `findVersion`.
- [x] Add `WorkflowService.publishVersion(workflowKey, username)` that validates the current layout and inserts a snapshot.
- [x] Add `POST /_backend/workflows/{workflowKey}/publish-version`.
- [x] Run `mvn -Dtest=ApiContractTest test`.

## Task 2: Durable Queue And Dispatch

**Files:**
- Modify: `WorkflowRun.java`
- Modify: `WorkflowMapper.java`
- Modify: `WorkflowService.java`
- Modify: `WorkflowController.java`
- Modify: MySQL/H2 schema and migration runner.
- Test: `ApiContractTest.java`

- [x] Add a failing test that `POST /_backend/workflows/{workflowKey}/run?async=true` returns `QUEUED` and no node runs exist yet.
- [x] Add `inputs_json`, `workflow_version_id`, `control_status`, `pause_requested`, `cancel_requested`, and `queued_at` columns to `workflow_run`.
- [x] Extend `WorkflowRun` and mapper select/insert/update statements.
- [x] Add `queueStart(...)` and keep synchronous `start(...)` compatible.
- [x] Add `dispatchQueuedRun(runId)` that moves `QUEUED -> RUNNING`, executes, and persists final state.
- [x] Add `POST /_backend/workflow/runs/{runId}/dispatch`.
- [x] Run `mvn -Dtest=ApiContractTest test`.

## Task 3: Pause, Resume, Cancel, Retry

**Files:**
- Modify: `WorkflowNodeRun.java`
- Modify: `WorkflowMapper.java`
- Modify: `WorkflowService.java`
- Modify: `WorkflowController.java`
- Modify: MySQL/H2 schema and migration runner.
- Test: `ApiContractTest.java`

- [x] Add failing tests for `QUEUED -> PAUSED -> QUEUED -> CANCELLED`.
- [x] Add failing test where a node with `retryPolicy.maxAttempts = 2` fails once and then completes.
- [x] Add `attempt`, `max_attempts`, `retry_policy_json`, and `timeout_ms` to `workflow_node_run`.
- [x] Add service methods `pauseRun`, `resumeRun`, and `cancelRun`.
- [x] Add cooperative checks before each node execution.
- [x] Implement retry loop around node execution and record `NODE_RETRYING`.
- [x] Add controller endpoints for pause/resume/cancel.
- [x] Run `mvn -Dtest=ApiContractTest test`.

## Task 4: Run Center Controls

**Files:**
- Modify: `aegis-alpha-web/app/App.jsx`
- Modify: `aegis-alpha-web/scripts/enterprise-console-foundation.test.mjs`

- [x] Add a source contract test that the run center contains dispatch, pause, resume, and cancel actions.
- [x] Show workflow version/idempotency/queue status columns in the run center table.
- [x] Add action buttons that call dispatch/pause/resume/cancel endpoints and refresh the list.
- [x] Keep the trace drawer and node timeline working.
- [x] Run `node scripts/enterprise-console-foundation.test.mjs` and `npm run build`.

## Task 5: Release Verification

**Files:**
- Modify: `scripts/smoke-aegis-alpha.ps1` only if a new endpoint health check is needed.
- Modify: `docs/superpowers/plans/2026-05-04-enterprise-phase2-durable-runtime-plan.md` checkboxes as work completes.

- [x] Run Java full tests: `mvn test`.
- [x] Run LangGraph checks: `node --check server.mjs`, `node --check scripts/smoke.mjs`, `npm run smoke`.
- [x] Run frontend tests/build: `npm run test:portfolio-list`, `node scripts/enterprise-console-foundation.test.mjs`, `npm run build`.
- [x] Restart local stack with `.\stop-aegis-alpha.ps1` and `.\start-aegis-alpha.ps1 -NoBrowser -SkipInstall`.
- [x] Run `.\scripts\smoke-aegis-alpha.ps1`.
- [x] Record a session-memory checkpoint with changed files and verification results.
