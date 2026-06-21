# P2 Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the P2 backend contracts needed by the UI/UX review so the frontend can stop inferring missing data and placeholder capabilities.

**Architecture:** Add focused Java service methods that return explicit contract maps while keeping existing domain objects and mappers intact. Frontend changes should consume the new contracts only where it removes current inference.

**Tech Stack:** Spring Boot 2.7, MyBatis annotation mappers, H2-backed MockMvc contract tests, Next/React frontend.

---

### Task 1: Portfolio Detail Contracts

**Files:**
- Modify: `aegis-alpha-api/src/test/java/com/marketmind/alpha/ApiContractTest.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/controller/PortfolioController.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/PortfolioService.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/mapper/PortfolioMapper.java`

- [ ] **Step 1: Write failing MockMvc assertions**

Add a test that creates/list-seeds portfolio data and verifies:
`/_backend/portfolio/{id}/summary`, `/_backend/portfolio/{id}/positions`, and `/_backend/portfolio/{id}/trades` return `portfolioId`, `asOf`, `sourceStatus`, `dataCompleteness`, and the expected payload section.

- [ ] **Step 2: Verify RED**

Run: `mvn -Dtest=ApiContractTest#portfolioDetailContractsExposeSummaryPositionsAndTrades test`
Expected: FAIL with 404 for the new portfolio detail endpoints.

- [ ] **Step 3: Implement minimal endpoints**

Add controller methods that authorize via `AuthService` and call `PortfolioService.summaryContract`, `positionsContract`, and `tradesContract`.

- [ ] **Step 4: Implement contract assembly**

Use `PortfolioMapper.findById` and `PortfolioTradeService.findAll(portfolioId, null)` to compute summary, position rows, and data-completeness states:
`NO_PORTFOLIO`, `SEEDED_SUMMARY_ONLY`, `NO_OPEN_POSITIONS`, `DETAILS_SYNCED`.

- [ ] **Step 5: Verify GREEN**

Run the same focused test and confirm it passes.

### Task 2: Workflow Run Action Contract

**Files:**
- Modify: `aegis-alpha-api/src/test/java/com/marketmind/alpha/ApiContractTest.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/domain/WorkflowRun.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/WorkflowService.java`

- [ ] **Step 1: Write failing contract assertions**

Extend durable runtime test to assert run list entries contain `availableActions`, `actionReasons`, `lastEvent`, and `priority`.

- [ ] **Step 2: Verify RED**

Run: `mvn -Dtest=ApiContractTest#durableRuntimePublishesQueuesControlsDispatchesAndRetriesRuns test`
Expected: FAIL because the serialized `WorkflowRun` lacks the new fields.

- [ ] **Step 3: Implement transient getters/setters**

Add non-persisted fields to `WorkflowRun` and hydrate them in `WorkflowService.runs()` and `WorkflowService.run(String)`.

- [ ] **Step 4: Verify GREEN**

Run the focused durable runtime test and confirm it passes.

### Task 3: Market Data Metadata Contract

**Files:**
- Modify: `aegis-alpha-api/src/test/java/com/marketmind/alpha/ApiContractTest.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/MarketDataService.java`

- [ ] **Step 1: Write failing metadata assertions**

Assert market-data overview sections expose `asOfLocal`, `timezone`, `delayHint`, and normalized `label` for financial metrics.

- [ ] **Step 2: Verify RED**

Run: `mvn -Dtest=ApiContractTest#marketDataContractExposesLiveProviderPayloads test`
Expected: FAIL where mocked or service payload lacks metadata.

- [ ] **Step 3: Add metadata normalization**

Centralize enrichment in `MarketDataService.base` and metric construction so all provider payloads include stable metadata.

- [ ] **Step 4: Verify GREEN**

Run the focused market data test and confirm it passes.

### Task 4: Frontend Contract Consumption

**Files:**
- Modify: `aegis-alpha-web/app/App.jsx`
- Modify: `aegis-alpha-web/scripts/ui-ux-audit-regression.test.mjs`

- [ ] **Step 1: Add frontend static regression assertions**

Assert portfolio pages call `portfolio/{id}/positions` or `summary` instead of inferring completeness only from list counts.

- [ ] **Step 2: Verify RED**

Run: `npm run test:ui-ux-audit`
Expected: FAIL until the frontend consumes the new contract.

- [ ] **Step 3: Update portfolio loading**

Use the new `positions` and `trades` contracts when a selected portfolio exists, preserving the old list fallback only for older backends.

- [ ] **Step 4: Verify GREEN**

Run `npm run test:ui-ux-audit` and the Java focused tests.
