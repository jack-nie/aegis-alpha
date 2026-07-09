# Detailed Design: Equity Research Agent Platform

**Date:** 2026-07-09  
**Status:** Draft for approval  
**Blueprint:** `docs/superpowers/plans/2026-07-09-equity-research-agent-blueprint.md`  
**Council notes:** `docs/superpowers/specs/2026-07-09-expert-council-discussion.md`

---

## 1. Goals and Non-Goals

### 1.1 Goals

1. Deliver a **governed equity research agent** loop that is trustworthy for an internal PM review.
2. Keep **Java as system of record** and **LangGraph as bounded executor**.
3. Enforce **evidence-backed numbers** before `PENDING_REVIEW`.
4. Make a **3-person research desk** able to use the product daily (run → review → approve).

### 1.2 Non-Goals

- Autonomous trading or agent-initiated portfolio writes.
- Building a full market-data terminal (Bloomberg parity).
- Free-form multi-agent swarms outside published topologies.
- Full SaaS multi-tenant RBAC in Phase 0–1.
- Full-text filings RAG in Phase 0–1.

---

## 2. Personas and Primary Journeys

| Persona | Need |
|---------|------|
| Researcher | Deep dive ticker, see tools/evidence, draft recommendation |
| Approver | One-screen approve/reject with evidence and risks |
| Desk lead | Run success rates, degraded rates, cost per run |

### Journey A — Single-name research (P0)

```text
Chat/template
  → IntentRouter (published workflow only)
  → Java creates workflow_run (idempotency_key)
  → Orchestrator executes published DAG
  → Tools fetch market/financials/news
  → Critique checks evidence completeness
  → Aggregate emits RecommendationDraft
  → Java materializes recommendation + evidence (PENDING_REVIEW, maybe degraded)
  → SSE updates canvas/run center
  → Approver approves/rejects in Inbox
```

### Journey B — Explore mode (P1, optional)

```text
Chat explore agent (bounded tools)
  → Research notes only
  → NOT auto-inserted into approval pool
  → User may "Promote to formal run" → Journey A
```

---

## 3. Logical Architecture

### 3.1 Components

| Component | Responsibility |
|-----------|----------------|
| **Web Console** | Chat, canvas, run center, evidence viewer, approval inbox; thin client |
| **API / Domain (Java)** | Auth, run lifecycle, recommendation/evidence persistence, governance, portfolio writes, model registry |
| **Orchestrator (Python)** | Intent classify, LangGraph execution, tools, critique, checkpoint, fine-grained SSE |
| **Market providers** | Quote/financials/news via existing race layer |
| **Eval harness** | Golden fixtures, contract tests, scorecards |

### 3.2 Trust boundaries

```text
Browser --user token--> Java --service token + run context--> Orchestrator
                              --service token--> Market/Portfolio APIs
```

- Orchestrator **never** trusts anonymous inbound calls in non-local profiles.
- Portfolio **write** never exposed as agent tools.
- Portfolio **read** tools require **run-scoped delegated token** (user/tenant/portfolio scope).

---

## 4. Agent Runtime Design

### 4.1 Maturity target

- **Phase 0:** Solid L2 (tool loop + interrupt + error taxonomy + eval seed)
- **Phase 1:** L3 skeleton (supervisor + specialists + critique gate)
- **Phase 2:** L3 productized (versioned SKU + online metrics)

### 4.2 Topology pattern (Hybrid)

```text
START
  → ingest/normalize (symbol, market, asOf context)
  → [optional supervisor]
  → specialists (fundamentals | news | valuation | risk | portfolio_context)
  → critique
  → aggregate (RecommendationDraft)
  → approval_gate (interrupt)   # when workflow.require_approval
  → END
```

Rules:

1. Only **published** workflow definitions are intent targets.
2. Supervisor may skip optional specialists; may **not** invent new topology edges.
3. `general.agent` / specialists use **role-filtered tool allowlists**.
4. Tool messages must carry `source_agent_id`; tools node routes back to caller (fail closed if missing).

### 4.3 Canonical loop mapping

| Step | Owner | Implementation |
|------|-------|----------------|
| observe | Java + Orch | Run inputs + memory slots + market bootstrap |
| plan | Orch | Supervisor / agent prompt with schema |
| tool | Orch → Java APIs | ToolNode + ToolSpec registry |
| critique | Orch (+ Java rules) | Critique node + policy checks |
| decide | Orch aggregate | Structured draft only |
| human gate | Orch interrupt + Java approve API | Resume via Java |
| persist | Java | recommendation, evidence_item, spans, audit |

### 4.4 SSE event contract

| Event | Payload (min) | Consumer |
|-------|----------------|----------|
| `node_update` | nodeId, status, ok, degraded? | Canvas / run center |
| `tool_call` | tool, args_digest, agentId, ok, code? | Trace panel |
| `agent_plan` | steps[] (P1) | Trace panel |
| `critique_result` | missing_data[], conflicts[] | Evidence UX |
| `human_interrupt` | message, runId | Approval / resume CTA |
| `degraded` | reasons[] | Banner; block approve |
| `workflow_complete` | runId, status, recommendationId? | Toast + navigate |
| `error` | code, message, retryable | Error UX |

Frontend must keep SSE `event:` state across TCP chunks (already fixed pattern).

---

## 5. Tool System Design

### 5.1 ToolSpec

```json
{
  "name": "get_stock_quote",
  "version": "1",
  "sideEffect": "read",
  "sandboxClass": "research",
  "authzScopes": ["market:read"],
  "timeoutMs": 8000,
  "retryPolicy": { "maxAttempts": 2, "retryableCodes": ["TIMEOUT", "UPSTREAM_5XX"] },
  "inputSchema": { "symbol": "string", "market": "US|SH|SZ|HK|..." },
  "outputSchema": { "ok": "bool", "asOf": "datetime", "provider": "string", "data": {} },
  "javaRoute": "GET /_backend/market-data/quote"
}
```

### 5.2 Initial registry

| Tool | Class | Phase |
|------|-------|-------|
| get_stock_quote | research | 0 |
| get_financials | research | 0 |
| get_news | research | 0 |
| get_company_overview | research | 0 |
| get_portfolio_positions | portfolio_read | 0 (delegated auth) |
| get_portfolio_summary | portfolio_read | 0 (delegated auth) |
| get_filings_metadata | research | 2 |
| place_order / rebalance | trade | **never as agent tool** |

### 5.3 Error taxonomy

`VALIDATION_ERROR | AUTHZ_DENIED | TIMEOUT | UPSTREAM_5XX | RATE_LIMITED | EMPTY_DATA | SCHEMA_MISMATCH | TOOL_UNAVAILABLE`

Agent-visible shape:

```json
{ "ok": false, "code": "TIMEOUT", "retryable": true, "userMessage": "Market data timed out" }
```

### 5.4 Auth for tools

1. **Service token** for market-data internal mesh (existing `node-execution-token`, fail-closed).
2. **Run-scoped delegated token** for portfolio_read:
   - Issued by Java at run start: claims `{ runId, userId, tenantId, portfolioIds[], exp }`
   - Signed with existing HMAC TokenService extension or dedicated service key
   - Orchestrator attaches token only to portfolio tools
3. Orchestrator inbound: require service auth header on `/execute-*`, `/stream-*`, `/classify-intent`.

---

## 6. Research Output & Evidence Design

### 6.1 RecommendationDraft (orchestrator → Java)

```json
{
  "symbol": "AAPL",
  "market": "US",
  "asOf": "2026-07-09T12:00:00Z",
  "recommendation": "BUY|HOLD|SELL|INSUFFICIENT_DATA",
  "confidence": 0.0,
  "timeHorizon": "3M|6M|12M",
  "thesis": { "bull": [], "bear": [], "base": "" },
  "catalysts": [{ "event": "", "window": "", "direction": "", "evidenceIds": [] }],
  "risks": [{ "risk": "", "severity": "L|M|H", "severity": "" }],
  "valuationAnchors": {
    "method": "peer_multiples|hist_band|simple_dcf|none",
    "current": {},
    "fairRange": {},
    "assumptions": [],
    "evidenceIds": []
  },
  "positionHint": null,
  "missingData": [],
  "claims": [
    {
      "claimId": "c1",
      "field": "last_price",
      "value": 190.2,
      "unit": "USD",
      "asOf": "...",
      "evidenceId": "e1"
    }
  ],
  "degraded": false,
  "degradedReasons": [],
  "disclaimer": "Draft for internal review only."
}
```

### 6.2 Hard policy rules

1. BUY/SELL requires:
   - valid quote claim with asOf within SLA (or explicit STALE + not used as sole anchor)
   - at least one financial claim (revenue or earnings equivalent) **or** explicit `INSUFFICIENT_DATA`
2. Any numeric claim without evidenceId → strip claim; if was material → force `INSUFFICIENT_DATA` or block PENDING.
3. `confidence = min(llm_self, rule_score)`; rule_score from coverage/stale/conflicts; no evidence ⇒ cap 0.3.
4. `degraded=true` ⇒ Java sets flag; **approve API rejects** until re-run without degraded (or admin override permission later).
5. Delete/override prompts that forbid admitting insufficient data.

### 6.3 Evidence item

Align existing `evidence_item` + materialization:

| Field | Meaning |
|-------|---------|
| sourceType | market / financials / news / filing / portfolio / model |
| trustTier | T0 filing/exchange · T1 vendor · T2 news · T3 model-only |
| url | optional |
| asOf | fact time |
| retrievedAt | fetch time |
| summary / payload | claim payload |
| nodeRunId / workflowRunId | lineage |

Phase 0: store claims array in rationale/missing JSON if schema migration deferred.  
Phase 1: first-class claim columns or child table if query UX needs it.

### 6.4 Insufficient data triggers

- No quote or missing asOf  
- No usable financials core fields  
- Symbol/market normalization failure  
- Critical tool failure count ≥ threshold  
- Material numbers unbound to evidence  

---

## 7. Workflow & Run Lifecycle (Java)

### 7.1 States (logical)

`QUEUED → RUNNING → PAUSED → COMPLETED | DEGRADED_COMPLETED | FAILED | CANCELLED`

- `DEGRADED_COMPLETED`: finished with partial data; recommendation may exist but not approvable.
- Resume of approval interrupt: Java issues resume command; orchestrator rebuilds with stored `require_approval` + topology meta.

### 7.2 Idempotency

- Create run requires `idempotencyKey` (client UUID or hash of user+workflow+subject+day bucket).
- Approve/reject idempotent on `(recommendationId, action, actor)`.

### 7.3 Materialization timing

On orchestrator terminal event:

1. Persist node outputs / spans  
2. Upsert recommendation draft (`PENDING_REVIEW`)  
3. Materialize evidence list  
4. Emit audit `recommendation.drafted`

Approve:

1. Validate not degraded / policy checks  
2. Status → `APPROVED`  
3. Audit with before/after  

---

## 8. Intent Routing Design

1. Java loads **published** workflow definitions (key, name, triggerKeywords, routingDescription).  
2. Orchestrator `IntentResult` serializes **camelCase** (`workflowKey`) and accepts snake_case input.  
3. Function-calling maps `run_<sanitized>` → **original** workflowKey (no underscore/hyphen lossy reverse).  
4. Fallback: keyword/regex (existing IntentRouterService).  
5. Explore mode (P1): special `explore_research` key that **does not** create approvable recommendation.

---

## 9. Memory Design

| Type | Store | Injection | TTL |
|------|-------|-----------|-----|
| Thread short-term | LangGraph checkpoint + messages | automatic | run lifetime |
| User prefs | PersistentStore `("user", id)` | observe slot | permanent until erase |
| Ticker insights | `("ticker", symbol)` | observe slot with asOf | ~30m |
| Episodic | Java runs + recommendations | retrieval API P2 | SoR |
| Workflow patterns | `("workflow", key)` | routing hints | versioned |

**Rules:**

- Memory hits recorded on spans as `memory_hits[]`.  
- Memory never stores secrets/tokens.  
- Ticker insights cannot override tool facts (facts win).  
- Cross-user namespace isolation mandatory.

---

## 10. Symbol & Market Normalization

Pipeline (Java or shared pre-node):

1. Parse raw text → candidates  
2. Assign `market` (US/SH/SZ/HK/...)  
3. Canonical symbol (e.g. `600519.SH`, `AAPL`)  
4. On conflict → fail run with `VALIDATION_ERROR` (no silent US default for pure digits)

Multiples conventions table (doc + prompt): TTM vs LFY, currency, ADR vs local.

---

## 11. Security Design (minimum)

### Internal deploy (Phase 0–1)

| Control | Requirement |
|---------|-------------|
| User auth | Existing HMAC token acceptable short-term |
| Password hashing | Schedule salted KDF (not Phase 0 blocker for loop) |
| Service auth | Orchestrator inbound + all node callbacks fail-closed |
| Network | Orchestrator not public; compose internal network |
| Secrets | Env-based; no default prod token |
| Audit | login, run, approve/reject, model config change |
| Profile API | Never return password hash |

### SaaS later

OIDC, hard tenant filters, mTLS/workload identity, KMS, permission RBAC (`recommendation:approve`, etc.).

---

## 12. UX Design Requirements

| Surface | Requirements |
|---------|--------------|
| Chat | Show workflowKey, run deep link, degraded badge |
| Canvas | Node running/success/error/degraded; tool_call subtrace |
| Run Center | Only `availableActions`; timeline; error codes localized |
| Evidence viewer | List by trustTier; asOf; open source URL; claim highlights |
| Approval inbox | Summary + bull/bear + risks + evidence + approve/reject reason |
| Draft watermark | Unapproved recommendations visually marked 草稿/待审 |

Deep links (P0):

- `/runs/:runId`
- `/recommendations/:id` (or governance route existing)

---

## 13. Observability

### Structured log/trace fields

`traceId, requestId, tenantId, userId, workflowRunId, nodeRunId, workflowKey, agentId, toolName, degraded, errorCode`

### Metrics (minimum)

| Metric | Use |
|--------|-----|
| run_success_rate | reliability |
| run_degraded_rate | data quality |
| tool_error_rate{tool,code} | provider health |
| time_to_first_node_ms | UX |
| run_cost_tokens / run_cost_usd | budget |
| approval_reject_rate | research quality signal |

---

## 14. Evaluation Design

### 14.1 Golden fixtures (Phase 0: 10, Phase 1: 30)

Each case:

- fixed symbol + frozen tool responses (mock)
- expected tool sequence (subset)
- expected recommendation enum constraints
- numerical fidelity checks (values ⊆ tool JSON)
- forbid BUY/SELL when fixtures mark missing financials

### 14.2 Scorecard dimensions (0–2 each)

1. Numerical fidelity (gate)  
2. Source coverage (gate)  
3. Freshness compliance  
4. Bull/Bear balance  
5. Horizon clarity  
6. Valuation discipline  
7. Risk specificity  
8. Market convention  
9. Actionability hygiene  
10. Contradiction handling  

**CI gate:** dimensions 1–2 must be full marks on golden set.

### 14.3 Contract tests Java ↔ Python

- `/classify-intent` returns `workflowKey`
- NodeResult schema
- SSE event names
- Resume payload + require_approval restoration
- Error code enum parity

---

## 15. Data Model Deltas (incremental)

Prefer extend existing tables over big-bang.

| Change | Phase | Notes |
|--------|-------|-------|
| recommendation.degraded / degraded_reasons | 0 | columns or columns |
| recommendation.time_horizon | 0 | column or rationale_json |
| evidence asOf / trust_tier consistency | 0 | align materializer |
| claim_json on recommendation | 0 | until claim table |
| workflow_run.delegated_token_id / hash | 0 | optional audit |
| recommendation_claim table | 1 | if query needs |
| eval_case / eval_run tables | 1–2 | harness results |

Schema changes require version comment per project SQL rules; DDL only with user confirmation at implementation time.

---

## 16. API Sketch (additive)

### Java

| API | Purpose |
|-----|---------|
| `POST /_backend/workflows/{key}/run` | existing; require idempotencyKey |
| `GET /_backend/workflow-runs/{id}` | include availableActions, degraded |
| `POST /_backend/workflow-runs/{id}/resume` | approval resume |
| `GET /_backend/recommendations/{id}` | draft + evidence + claims |
| `POST /_backend/recommendations/{id}/approve` | reject if degraded |
| `POST /_backend/recommendations/{id}/reject` | reason required |
| `POST /_backend/runs/{id}/delegated-token` | internal; portfolio scope |

### Orchestrator

| API | Purpose |
|-----|---------|
| `POST /classify-intent` | camelCase IntentResult |
| `POST /stream-workflow` | SSE events per §4.4 |
| `POST /execute-workflow` | sync path |
| Auth | `X-Service-Token` or `Authorization: Bearer <service>` |

---

## 17. Phase 0 Implementation Work Breakdown

| ID | Area | Tasks |
|----|------|-------|
| D0.1 | Prompts | Remove anti-insufficient wording; schema-first aggregate |
| D0.2 | Aggregate validation | Post-parse validate claims/evidence; force INSUFFICIENT |
| D0.3 | Java materialize | Map draft → recommendation + evidence; degraded flags |
| D0.4 | Approve policy | Block approve when degraded or missing gates |
| D0.5 | Tools | Path OK; portfolio delegated token; agent return attribution |
| D0.6 | Orchestrator auth | Inbound middleware |
| D0.7 | SSE | Emit degraded/human_interrupt; web banners |
| D0.8 | Symbol normalize | Shared utility + hard fail |
| D0.9 | Eval | 10 golden + contract tests in CI |
| D0.10 | UX | Deep link + draft watermark + inbox approve |

---

## 18. Consistency with Prior Enterprise Docs

This design **narrows** the May 2026 enterprise architecture to a shippable spine:

| Enterprise theme | Treatment here |
|------------------|----------------|
| Durable runtime | Keep Java lifecycle authority; enhance don't replace |
| Evidence & recommendation | Make them the **product core**, not appendix |
| Model governance | Phase 1 cost/fallback semantics; full budget P2 |
| Multi-tenant RBAC | Fields + filters first; full engine later |
| Observability | Minimal metrics first; full OTel later |

Where prior docs conflict with research DoD (e.g. forcing full reports without insufficient), **this design supersedes**.

---

## 19. Open Items After Approval

1. Exact delegated token claim format (extend TokenService vs new signer).  
2. Whether approval_gate sits only pre-final or also mid-graph for specific workflows.  
3. US vs A-share golden set split sizes.  
4. Whether explore mode ships in Phase 1 or slips to Phase 2.

---

## 20. Approval

Product/engineering approval of this design authorizes writing a Phase 0 implementation plan (`docs/superpowers/plans/2026-07-09-phase0-governed-research-agent-plan.md`) and subsequent coding.

**Checklist:**

- [ ] Positioning accepted  
- [ ] RecommendationDraft + evidence policy accepted  
- [ ] Hybrid agent topology accepted  
- [ ] Phase 0 WBS accepted  
- [ ] Non-goals accepted  
