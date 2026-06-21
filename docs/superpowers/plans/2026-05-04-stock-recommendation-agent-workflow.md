# Stock Recommendation Agent Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a runnable financial agent workflow for stock recommendation research with node-level trace persistence and history visibility.

**Architecture:** Java remains the source of truth for workflow orchestration and persistence. The Node LangGraph service executes structured finance nodes through `/execute-node`, returning deterministic mock output without an API key and LLM-backed output when configured. The Next frontend exposes the workflow catalog, run inputs, and a backtest-history view that drills into workflow node traces.

**Tech Stack:** Spring Boot 2.7, MyBatis, MySQL/H2, Next.js/ReactFlow, Node.js Express, LangChain/LangGraph.

---

### Task 1: Java Persistence And Contracts

**Files:**
- Modify: `aegis-alpha-api/src/test/java/com/marketmind/alpha/ApiContractTest.java`
- Modify: `aegis-alpha-api/src/test/resources/schema-h2.sql`
- Modify: `aegis-alpha-api/src/main/resources/db/mysql/schema.sql`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/config/SchemaMigrationRunner.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/domain/BacktestRun.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/mapper/BacktestMapper.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/BacktestService.java`
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/domain/AgentCallSpan.java`
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/mapper/AgentCallSpanMapper.java`
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/AgentTraceService.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/WorkflowService.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/LangChainGateway.java`

- [ ] Add failing MockMvc assertions for workflow run, backtest-history association, and node trace lookup.
- [ ] Extend H2/MySQL schemas and migration runner.
- [ ] Add backtest fields and agent span persistence.
- [ ] Update workflow execution to create a backtest history row and trace spans.
- [ ] Run `mvn -Dtest=ApiContractTest test`, then full compile.

### Task 2: LangGraph Finance Node Executor

**Files:**
- Modify: `aegis-alpha-orchestrator/server.mjs`
- Modify: `aegis-alpha-orchestrator/package.json`
- Create: `aegis-alpha-orchestrator/scripts/smoke.mjs`

- [ ] Add `/execute-node` with handler registry for finance and web-search nodes.
- [ ] Keep `/execute-agent` compatibility.
- [ ] Return deterministic structured JSON when no API key is configured.
- [ ] Update `/execute-workflow` to call the node executor and return trace output.
- [ ] Run `node --check server.mjs` and `npm run smoke` against a running service.

### Task 3: Next Workflow And History UI

**Files:**
- Modify: `aegis-alpha-web/app/App.jsx`

- [ ] Add screenshot-aligned node catalog entries and a stock recommendation workflow label.
- [ ] Add run inputs for ticker, industry, and subject.
- [ ] Replace `/backtest/history` placeholder with a workflow-aware history list.
- [ ] Add call-stack detail lookup through `/workflow/runs/{workflowRunId}/nodes`.
- [ ] Run `npm run build`.

### Task 4: Integration Verification

- [ ] Restart LangGraph, Java backend, and Next frontend.
- [ ] Login and trigger `POST /_backend/workflows/stock_recommendation_research/run`.
- [ ] Verify `GET /_backend/backtest/history` returns the linked workflow run.
- [ ] Verify `GET /_backend/workflow/runs/{runId}/nodes` returns ordered node outputs.
- [ ] Record a session-memory checkpoint.
