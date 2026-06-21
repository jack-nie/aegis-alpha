# Real-Time Market Data Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect live quote, SEC financial, and recent news data into the backend, workflow node runtime, LangGraph context, and the dashboard query UI.

**Architecture:** Java Spring Boot becomes the canonical market data gateway with provider adapters and short TTL caching. Workflow nodes call the same gateway for `fdb.*`, news, market analysis, and financial interpretation handlers. LangGraph hydrates node context from the Java gateway before LLM execution so models receive structured quote, financial, and news evidence.

**Tech Stack:** Spring Boot MVC, `RestTemplate`, Jackson, JUnit/MockMvc, Node 18+ fetch, Next React UI.

---

### Task 1: Backend Market Data Contract

**Files:**
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/MarketDataService.java`
- Create: `aegis-alpha-api/src/main/java/com/marketmind/alpha/controller/MarketDataController.java`
- Create: `aegis-alpha-api/src/test/java/com/marketmind/alpha/service/MarketDataServiceTest.java`
- Modify: `aegis-alpha-api/src/main/resources/application.yml`

- [ ] **Step 1: Write failing service tests**

Create `MarketDataServiceTest` with fixture-backed HTTP responses and assert:
- `quote("AAPL")` returns `symbol`, `price`, `changePct`, `provider`, `asOf`, `isRealtime`, `delayHint`, `sources`.
- `financials("AAPL")` returns SEC facts including revenue or net income metrics and SEC source metadata.
- `news("AAPL")` returns normalized article rows from RSS/XML with source metadata.

Run: `mvn -Dtest=MarketDataServiceTest test`
Expected: FAIL because `MarketDataService` does not exist.

- [ ] **Step 2: Implement service and provider parsing**

Implement `MarketDataService` using `RestTemplate` and Jackson:
- Free route: Yahoo chart -> Stooq for quote fallback.
- Financials route: SEC ticker map -> SEC CompanyFacts JSON.
- News route: Yahoo Finance RSS -> GDELT fallback.
- Keyed route placeholders: Finnhub, Alpha Vantage, FMP, Twelve Data, Marketstack, Polygon config is exposed but not called unless keys exist.

- [ ] **Step 3: Run service tests**

Run: `mvn -Dtest=MarketDataServiceTest test`
Expected: PASS.

### Task 2: Backend API And Workflow Node Binding

**Files:**
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/service/WorkflowNodeExecutionService.java`
- Modify: `aegis-alpha-api/src/main/java/com/marketmind/alpha/controller/MarketDataController.java`
- Modify: `aegis-alpha-api/src/test/java/com/marketmind/alpha/ApiContractTest.java`

- [ ] **Step 1: Write failing API tests**

Add MockMvc assertions:
- `GET /_backend/market-data/overview?symbol=AAPL` returns `quote`, `financials`, `news`.
- `POST /_backend/workflow-nodes/execute` for `fdb.daily_ohlc` returns quote rows.
- `POST /_backend/workflow-nodes/execute` for `fdb.fundamental_data` returns financial rows.
- `POST /_backend/workflow-nodes/execute` for `fdb.global_news` returns news rows.

Run: `mvn -Dtest=ApiContractTest#marketDataContractExposesLiveProviderPayloads test`
Expected: FAIL because endpoints and workflow mapping do not exist.

- [ ] **Step 2: Implement controller and workflow mapping**

Expose authenticated endpoints:
- `GET /_backend/market-data/quote?symbol=AAPL`
- `GET /_backend/market-data/financials?symbol=AAPL`
- `GET /_backend/market-data/news?symbol=AAPL`
- `GET /_backend/market-data/overview?symbol=AAPL`

Map workflow handlers:
- `fdb.daily_ohlc`, `finance.market_analysis` -> quote overview.
- `fdb.fundamental_data`, `fdb.financial_ratios`, `finance.financial_interpretation` -> financial overview.
- `fdb.global_news`, `news.fetch_window`, `finance.industry_news`, `finance.sentiment_monitor`, `general.fetch_news`, `general.get_sector_news`, `general.web_search` -> news overview.

- [ ] **Step 3: Run API tests**

Run: `mvn -Dtest=ApiContractTest#marketDataContractExposesLiveProviderPayloads test`
Expected: PASS.

### Task 3: LangGraph Data Hydration

**Files:**
- Modify: `aegis-alpha-orchestrator/server.mjs`
- Modify: `aegis-alpha-orchestrator/package.json`
- Create: `aegis-alpha-orchestrator/scripts/market-data-hydration.test.mjs`

- [ ] **Step 1: Write failing Node test**

Create a Node script that starts a tiny local backend stub, imports the LangGraph server helper, executes `finance.market_analysis`, and asserts the result contains hydrated `marketData.quote.provider`.

Run: `node scripts/market-data-hydration.test.mjs`
Expected: FAIL because hydration helper is not exported or implemented.

- [ ] **Step 2: Implement hydration**

Add `MARKETMIND_BACKEND_URL` and `MARKETMIND_NODE_EXECUTION_TOKEN` support. Before deterministic or LLM execution, call `/_backend/internal/workflow-nodes/execute` for finance/news handlers and attach result under `state.marketDataContext`.

- [ ] **Step 3: Run Node test**

Run: `node scripts/market-data-hydration.test.mjs`
Expected: PASS.

### Task 4: Dashboard Real-Time Query UI

**Files:**
- Modify: `aegis-alpha-web/app/App.jsx`
- Create: `aegis-alpha-web/scripts/market-data-query.test.mjs`

- [ ] **Step 1: Write failing UI test**

Add a lightweight Playwright-style script following existing scripts that loads the app, logs in, opens `/data-center/dashboard`, searches `AAPL`, and asserts provider labels for quote, financials, and news.

Run: `npm run test:market-data-query`
Expected: FAIL because the query UI is not wired.

- [ ] **Step 2: Implement dashboard query panel**

Update `Dashboard` to:
- Keep existing visual style.
- Add symbol input and query button.
- Call `/market-data/overview?symbol=...`.
- Render quote price/change/provider/asOf, key financial metrics, and recent news with source labels.

- [ ] **Step 3: Run frontend test**

Run: `npm run test:market-data-query`
Expected: PASS.

### Task 5: Verification

**Files:**
- No new code files unless verification reveals a defect.

- [ ] **Step 1: Full backend test**

Run: `mvn test`
Expected: PASS.

- [ ] **Step 2: LangGraph smoke**

Run: `node scripts/market-data-hydration.test.mjs`
Expected: PASS.

- [ ] **Step 3: Frontend build and UI test**

Run: `npm run build`
Run: `npm run test:portfolio-list`
Run: `npm run test:market-data-query`
Expected: PASS.

- [ ] **Step 4: Start services**

Run existing `start-aegis-alpha.ps1` or project-specific start commands. Verify:
- `http://127.0.0.1:5174` loads.
- Backend health/data endpoints respond.
- LangGraph health responds.
