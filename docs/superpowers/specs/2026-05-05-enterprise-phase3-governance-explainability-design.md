# Enterprise Phase 3 Governance And Explainability Design

**Date:** 2026-05-05

**System:** Aegis Alpha Platform

**Goal:** Add the first production-grade governance loop for financial AI recommendations: model registry visibility, model-call accounting, evidence capture, explainable recommendation records, and recommendation review state.

## External Governance Baseline

This phase uses three current official baselines:

- NIST AI RMF: manage AI risks through governance, mapping, measurement, and monitoring.
- SEC Regulation Best Interest guidance: securities recommendations require care, disclosure, and conflict-awareness.
- FINRA Regulatory Notice 24-09: GenAI tools should be evaluated before deployment and used in a way that preserves existing regulatory obligations.

The system will not claim legal compliance. It will make each recommendation traceable, reviewable, and explainable.

## Scope

Phase 3 implements a working governance foundation. It does not yet implement production-grade policy engines, full RBAC, regulatory filing workflows, or third-party compliance attestation.

In scope:

- model registry read API and seeded active/default model
- LLM/model call table derived from workflow node spans
- evidence items extracted from workflow node outputs
- recommendation record linked to workflow run and backtest history
- recommendation rationale, risks, missing data, disclaimer, confidence, and approval status
- UI pages for model governance and recommendation detail

## Backend Design

### Tables

`model_config`

- `model_config_id`
- `provider`
- `model_name`
- `status`
- `context_window`
- `prompt_token_cost_usd`
- `completion_token_cost_usd`
- `fallback_model`
- `created_at`

`llm_call`

- `llm_call_id`
- `workflow_run_id`
- `node_run_id`
- `trace_id`
- `provider`
- `model_name`
- `status`
- `prompt_tokens`
- `completion_tokens`
- `total_tokens`
- `estimated_cost_usd`
- `started_at`
- `completed_at`

`evidence_item`

- `evidence_id`
- `workflow_run_id`
- `node_run_id`
- `source_type`
- `title`
- `url`
- `trust_tier`
- `summary`
- `retrieved_at`

`recommendation`

- `recommendation_id`
- `workflow_run_id`
- `backtest_run_id`
- `trace_id`
- `symbol`
- `recommendation`
- `confidence`
- `time_horizon`
- `rationale_json`
- `risk_json`
- `missing_data_json`
- `disclaimer`
- `approval_status`
- `created_at`

### Services

`ModelGovernanceService`

- list active model configs
- calculate estimated model cost from token usage
- persist model call records from `agent_call_span`

`EvidenceService`

- extract `sources` and `signals` from node output JSON
- persist evidence records linked to workflow run and node run

`RecommendationService`

- build explainable recommendation from workflow result JSON, evidence, and inputs
- default recommendation is `INSUFFICIENT_DATA` when final output is degraded or missing
- approval states: `PENDING_REVIEW`, `APPROVED`, `REJECTED`

### Integration

When `BacktestService.createFromWorkflowRun(...)` creates a backtest history row, it will call governance services to:

1. persist LLM calls from spans
2. persist evidence from node outputs
3. create recommendation record linked to the workflow run and backtest row

This keeps the workflow runtime simple and puts financial explainability in the governance layer.

## API Design

- `GET /_backend/governance/models`
- `GET /_backend/governance/llm-calls?workflowRunId=...`
- `GET /_backend/recommendations`
- `GET /_backend/recommendations/{workflowRunId}`
- `POST /_backend/recommendations/{workflowRunId}/approve`
- `POST /_backend/recommendations/{workflowRunId}/reject`

## Frontend Design

Navigation additions:

- `治理 / 模型治理`
- `研究与推�?/ 推荐历史`

Model governance page:

- model registry table
- model usage/cost table when data exists
- quiet operational style with compact metrics

Recommendation detail page:

- recommendation, confidence, approval status
- rationale, risks, missing data
- evidence list with source type/trust tier/url
- workflow run and trace links
- approve/reject controls
- disclaimer visible near recommendation output

## Acceptance

Phase 3 is complete when:

1. model registry API returns active model configs
2. a workflow-generated backtest creates evidence and recommendation records
3. recommendation detail includes rationale, risks, missing data, evidence, disclaimer, and approval status
4. approval/rejection API updates recommendation state
5. UI exposes model governance and recommendation detail pages
6. backend tests, frontend contract tests, LangGraph smoke, build, compose config, and release smoke pass
