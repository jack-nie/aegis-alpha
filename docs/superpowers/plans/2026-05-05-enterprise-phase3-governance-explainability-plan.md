# Enterprise Phase 3 Governance And Explainability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add model governance, LLM call accounting, evidence capture, explainable recommendations, and review UI.

**Architecture:** Keep workflow execution unchanged. Add a governance layer that consumes workflow runs, node runs, and agent spans after a workflow-backed backtest history row is created. Expose compact governance APIs and render table-first UI pages.

**Tech Stack:** Spring Boot 2.7, MyBatis, H2/MySQL schemas, React/Next, existing source contract tests.

---

## Task 1: Governance Data Model And API Contract

**Files:**
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/domain/ModelConfig.java`
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/domain/LlmCall.java`
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/domain/EvidenceItem.java`
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/domain/Recommendation.java`
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/mapper/GovernanceMapper.java`
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/controller/GovernanceController.java`
- Modify: `aegis-alpha-api/src/main/resources/db/mysql/schema.sql`
- Modify: `aegis-alpha-api/src/test/resources/schema-h2.sql`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/config/SchemaMigrationRunner.java`
- Test: `aegis-alpha-api/src/test/java/com/marketmind/alpha/ApiContractTest.java`

- [x] Add failing test for `GET /_backend/governance/models` returning a default active model.
- [x] Add failing test for `GET /_backend/recommendations` requiring auth and returning a list.
- [x] Add schemas for `model_config`, `llm_call`, `evidence_item`, `recommendation`.
- [x] Add mapper select/insert/update methods.
- [x] Add governance controller endpoints for model list and recommendation list/detail.
- [x] Run `mvn -Dtest=ApiContractTest test`.

## Task 2: Workflow-To-Governance Materialization

**Files:**
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/ModelGovernanceService.java`
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/EvidenceService.java`
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/RecommendationService.java`
- Modify: `BacktestService.java`
- Test: `ApiContractTest.java`

- [x] Add failing test that running `stock_recommendation_research` creates one recommendation linked to workflowRunId.
- [x] Add failing test that recommendation detail includes at least one evidence item and a compliance disclaimer.
- [x] Implement model-call materialization from `agent_call_span`.
- [x] Implement evidence extraction from `workflow_node_run.output_json.sources`.
- [x] Implement recommendation creation from workflow result, inputs, evidence, and backtest row.
- [x] Run `mvn -Dtest=ApiContractTest test`.

## Task 3: Recommendation Review State

**Files:**
- Modify: `RecommendationService.java`
- Modify: `GovernanceController.java`
- Modify: `GovernanceMapper.java`
- Test: `ApiContractTest.java`

- [x] Add failing test for approve endpoint changing `approvalStatus` to `APPROVED`.
- [x] Add failing test for reject endpoint changing `approvalStatus` to `REJECTED`.
- [x] Implement `approve(workflowRunId)` and `reject(workflowRunId)`.
- [x] Run `mvn -Dtest=ApiContractTest test`.

## Task 4: Frontend Governance And Recommendation UI

**Files:**
- Modify: `aegis-alpha-web/app/App.jsx`
- Modify: `aegis-alpha-web/scripts/enterprise-console-foundation.test.mjs`

- [x] Add source contract test for `治理 / 模型治理` navigation and `/governance/models` route.
- [x] Add source contract test for recommendation history/detail route and approval controls.
- [x] Implement model governance page with model table.
- [x] Implement recommendation page with evidence list, rationale/risk sections, disclaimer, approve/reject buttons.
- [x] Run `node scripts/enterprise-console-foundation.test.mjs` and `npm run build`.

## Task 5: Verification

**Files:**
- Modify this plan checklist.

- [x] Run `mvn test`.
- [x] Run `npm run test:portfolio-list`.
- [x] Run `node scripts/enterprise-console-foundation.test.mjs`.
- [x] Run `npm run build`.
- [x] Run LangGraph checks and smoke.
- [x] Run compose config validation.
- [x] Restart local stack and run release smoke.
- [x] Record session-memory checkpoint.
