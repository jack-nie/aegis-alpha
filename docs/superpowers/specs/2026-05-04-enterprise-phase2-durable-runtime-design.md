# Enterprise Phase 2 Durable Runtime Design

**Date:** 2026-05-04

**System:** Aegis Alpha Platform

**Goal:** Move workflow execution from a synchronous MVP path toward a durable enterprise runtime that can publish immutable workflow versions, queue runs, dispatch runs separately from creation, control queued/running work, retry failed nodes, and expose the full operational state in the run center.

## Scope

Phase 2 focuses on reliability of workflow execution. It does not finish all enterprise governance work. Model registry, budgets, prompt governance, evidence catalog, and production observability dashboards stay in later phases unless a small hook is needed for runtime traceability.

## Runtime Model

Java remains the durable execution owner. A workflow run is created first as a persisted record and can then be executed by a dispatcher. The existing synchronous endpoint remains compatible for the current UI and tests, while a new async path returns a `QUEUED` run and lets the worker or an explicit dispatch command execute it.

The execution states are:

- Run: `QUEUED`, `RUNNING`, `PAUSED`, `COMPLETED`, `FAILED`, `CANCELLED`.
- Node: `RUNNING`, `RETRYING`, `COMPLETED`, `FAILED`, `CANCELLED`.

## Versioning

Publishing a workflow creates an immutable `workflow_version` row with a snapshot of the current layout JSON and validation result. A run pins `workflow_version_id` when available. If a workflow has no published version, the runtime can still execute the editable layout in local/dev mode and records `workflow_version_id = null`.

## Queue And Dispatch

`POST /_backend/workflows/{workflowKey}/run?async=true` creates a run with:

- `status = QUEUED`
- `inputs_json`
- `workflow_version_id`
- `idempotency_key`

The dispatcher executes queued runs through `WorkflowService.dispatchQueuedRun(runId)`. A scheduled in-process dispatcher picks recent queued runs. Tests can call `POST /_backend/workflow/runs/{runId}/dispatch` to avoid timing flakes.

## Control Commands

Control endpoints:

- `POST /_backend/workflow/runs/{runId}/cancel`
- `POST /_backend/workflow/runs/{runId}/pause`
- `POST /_backend/workflow/runs/{runId}/resume`
- `POST /_backend/workflow/runs/{runId}/dispatch`

Queue-state controls are strict:

- `QUEUED -> PAUSED`
- `PAUSED -> QUEUED`
- `QUEUED|PAUSED -> CANCELLED`

Running-state controls are cooperative. The executor checks `cancel_requested` and `pause_requested` before each node boundary. Cancellation marks the run and remaining work as `CANCELLED`. Pause is recorded as a requested state and stops at the next node boundary.

## Retry Policy

Each node can define retry settings in `node.data.retryPolicy`:

```json
{
  "maxAttempts": 3,
  "backoffMs": 0
}
```

Default is one attempt. On retryable failure, the runtime records `NODE_RETRYING`, creates a new attempt for the same logical node, and retries until success or exhaustion. The node run records `attempt`, `max_attempts`, `retry_policy_json`, and `timeout_ms`.

## Frontend

The run center becomes an operational table:

- status, workflow, subject, version, node count, idempotency key, timestamps
- action buttons for dispatch, pause, resume, cancel where the state allows it
- detail drawer keeps timeline, node attempts, JSON input/output, and error inspection

The UI stays dense, table-first, and operational, with status pills and action buttons rather than marketing-style cards.

## Acceptance

Phase 2 is complete when:

1. A workflow can be published to an immutable version.
2. An async workflow run is created as `QUEUED`.
3. A queued run can be dispatched and completes with node events.
4. A queued run can be paused, resumed, and cancelled through API commands.
5. A failed node retries according to `retryPolicy`.
6. Run center exposes version, queue status, and control actions.
7. Backend tests, frontend contract tests, LangGraph smoke, build, and release smoke pass.
