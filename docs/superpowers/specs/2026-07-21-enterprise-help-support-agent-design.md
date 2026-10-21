# Detailed Design: Enterprise Help / Support Agent (Aegis Copilot Extension)

**Date:** 2026-07-21  
**Status:** P0/P1/P2 implemented and verified 2026-10-21 (see §1.3)
**Design ID:** `20619fd6`  
**Related:**  
- `docs/superpowers/specs/2026-07-09-equity-research-agent-platform-design.md`  
- Branch context: `feat/llm-intent-routing`  
**Contract code:** `docs/superpowers/specs/help-agent-contracts/`

---

## 1. Goals and Non-Goals

### 1.1 Goals

1. Extend the existing **AICopilot → ChatService → Intent → Workflow|Copilot** spine with a third channel: **Help / Support Runtime**.
2. Deliver adaptive routing: **Research workflows first**, then product help, then run diagnosis — never a free-form autonomous swarm as the default path.
3. Enforce **zero-trust data boundaries**: public knowledge vs private user facts; ACL pre-filter on retrieval; no cache of PII/private run or portfolio answers.
4. Provide a bounded LangGraph **help_graph** (retrieve → grade → tools → clarify → answer → escalate) with max 3 self-correction loops.
5. Seed **eval-ready** golden set (P0: 20 cases; target: ~100) and CI red-line hooks.

### 1.2 Non-Goals (P0–P1)

- External B2C customer-support / ticketing / CRM.
- Full GraphRAG production topology (P2 only).
- MCP server rewrite of all tools (P2 optional wrapper).
- Vector DB multi-tenant SaaS RBAC.
- Replacing research recommendation / approval with the help agent.
- Agent-initiated portfolio writes or approval actions.
- Browser-direct calls to LLM, vector DB, or MCP.

### 1.3 Implementation status (2026-10-21)

| Area | Status | Evidence |
|------|--------|----------|
| P0 principal/thread/run ownership | Implemented | `ChatServiceSecurityTest`, `WorkflowServiceAuthzTest`; client-supplied roles ignored; run reads/list/control owner-scoped; legacy unowned runs admin-only |
| P0 golden20 CI | Implemented | `Golden20RoutingTest`: 23 tests, route accuracy 1.000 (15/15), safety 1.000 (5/5) |
| P1 observability | Implemented | `HelpTraceService` + `GET /_backend/help/metrics`; route/provider/cache/escalate counters + latency histogram |
| P1 load baseline | Implemented | `scripts/loadtest_help.py`; local `/help/execute` 200 req / 20 concurrency: 400.32 req/s, p50 47.05ms, p95 61.13ms, p99 71.88ms, 0 errors |
| P1 retrieval | Implemented as hybrid **lexical** retrieval | TF-IDF vector + keyword + RRF rerank; no neural embeddings/synonyms; `KNOWLEDGE_RAG` kept as the wire enum but documented as legacy naming |
| P1 bounded executor | Implemented | `AsyncConfig.workflowExecutor` (4/16/200, CallerRuns); ChatService, WorkflowController and layout write-back use it |
| P2 goal state/checkpoint | Implemented | `help_thread_state` + `HelpSessionStateService`; multi-turn runId inheritance and pending-clarification checkpoint |
| P2 answer-level eval | Implemented | `HelpAnswerEvaluator`; 26-case fixture: passRate 0.7692, faithfulness 0.7970, relevance 0.8638, escalation accuracy 0.9231 |
| P2 tool gateway | Implemented | `HelpToolCatalog` + `HelpToolGateway` (allowlist/timeout/retry/circuit/audit) wired into diagnosis; `GET /_backend/help/tools` |
| Neural vector DB / GraphRAG / MCP server | Not implemented (P3) | Deliberately out of scope; do not claim in interviews |

### 1.4 Product framing

| Marketing label | System name | Role |
|-----------------|-------------|------|
| 「客服助手」 | **Help / Support Agent** | Product how-to + run diagnosis + safe escalation |
| 「研究助手」 | **Research Copilot / Workflow** | Existing intent → published DAG / copilot research chat |

UI copy should prefer **「Aegis 助手」** with mode chips: 研究 / 帮助 / 诊断.

---

## 2. Personas and Journeys

| Persona | Help need |
|---------|-----------|
| Researcher | How to run deep_dive, interpret degraded, open run center |
| Approver | What PENDING_REVIEW requires; where evidence is |
| Desk lead | Why run failed; success/degraded rates language |

### Journey H1 — Product FAQ (L0)

```text
Chat message
  → Intent: PRODUCT_HELP, complexity=L0
  → Playbook exact / keyword hit
  → Answer + deep link (no LLM or tiny prompt)
```

### Journey H2 — Doc Retrieval (L1) [P1]

```text
Chat message
  → Intent: PRODUCT_HELP, complexity=L1
  → Semantic cache? (public only)
  → Java KnowledgeSearch (ACL pre-filter)
  → Single-shot answer with source_refs
```

### Journey H3 — Help Agent (L2) [P1]

```text
Chat message
  → Intent: PRODUCT_HELP|RUN_DIAGNOSIS, complexity=L2
  → help_graph (LangGraph)
  → retrieve / grade (≤3) / tools / clarify interrupt
  → answer or escalate_human
```

### Journey R — Research (unchanged priority)

```text
Chat message
  → Intent: RESEARCH + workflowKey
  → existing ChatService.dispatchWorkflow
```

**Priority rule:** if both research keywords and help keywords match, **RESEARCH wins** when `confidence ≥ 0.6` and a published `workflowKey` exists.

---

## 3. Logical Architecture

```text
Browser AICopilot
  --user token--> Java ChatController/ChatService
                    │
                    ├─ CacheGate (L1 exact/normalized; semantic planned, public only)
                    ├─ IntentRouterService.classifyExtended()
                    │     domain + complexity + entities + cacheable
                    │
                    ├─ RESEARCH      → WorkflowService (existing)
                    ├─ PRODUCT_HELP  → HelpService (playbook | knowledge | help_graph)
                    ├─ RUN_DIAGNOSIS → HelpService.diagnose(runId)
                    └─ CHITCHAT      → existing copilotReply (research-oriented)
                    │
                    --service token / delegated token-->
                  Orchestrator
                    ├─ /classify-intent (extended tools)
                    ├─ /help/execute  (help_graph)
                    └─ tools → Java APIs only
```

### Trust boundaries (unchanged + extended)

```text
Browser --user token--> Java --node/delegation token--> Orchestrator
                              --ACL filter--> Knowledge search
                              --user scope--> Run / Portfolio read APIs
```

- Orchestrator never builds ACL filters from untrusted client fields alone; Java attaches principal scope.
- Help tools: **read-only**; portfolio read requires existing delegation pattern.
- Private answers: never enter semantic cache.

---

## 4. Intent Model (core design code)

See contracts:

- `help-agent-contracts/python/intent_models.py`
- `help-agent-contracts/java/IntentModels.java`

### 4.1 Enums

| Field | Values |
|-------|--------|
| `domain` | `RESEARCH`, `PRODUCT_HELP`, `RUN_DIAGNOSIS`, `CHITCHAT` |
| `complexity` | `L0` (playbook), `L1` (hybrid lexical retrieval), `L2` (help graph) |
| `routeAction` | `DISPATCH_WORKFLOW`, `PLAYBOOK`, `KNOWLEDGE_RAG`, `HELP_GRAPH`, `COPILOT`, `CLARIFY` |

### 4.2 Extended IntentResult (backward compatible)

Existing fields kept: `workflowKey`, `ticker`, `confidence`, `source`.

New optional fields (Java Map / orchestrator `data` + first-class later):

```text
domain, complexity, routeAction, needsClarification,
clarificationPrompt, entities{runId,ticker,path,portfolioId},
cacheable, reason
```

**Compat:** old clients ignore unknown keys; if only `workflowKey` set, treat as `RESEARCH` + `DISPATCH_WORKFLOW`.

### 4.3 Adaptive routing algorithm (normative)

```text
function route(message, ctx):
  1. entities = extract_entities(message, ctx.pageContext)
  2. if looks_like_research(message) and published_workflow_match:
       return RESEARCH / DISPATCH_WORKFLOW   # highest priority
  3. if entities.runId or diagnosis_keywords:
       if missing runId and no pageContext.runId:
         return RUN_DIAGNOSIS / CLARIFY
       return RUN_DIAGNOSIS / HELP_GRAPH (or L1 template if simple status ask)
  4. if product_help_keywords or how_to_pattern:
       complexity = estimate_complexity(message)
       if complexity == L0 and playbook_hit: PLAYBOOK
       if complexity == L1: KNOWLEDGE_RAG   # P1; P0 fallback playbook/copilot
       if complexity == L2: HELP_GRAPH
  5. return CHITCHAT / COPILOT
```

Complexity heuristics (P0, no LLM):

| Signal | Complexity |
|--------|------------|
| Single concept FAQ, “是什么/在哪/如何打开” | L0 |
| Multi-step how-to without private data | L1 |
| Cross run + portfolio + policy, or multi-constraint | L2 |
| Ambiguous pronoun without entity (“它坏了”) | CLARIFY |

---

## 5. Chat API Contract

### 5.1 Request `POST /_backend/chat/messages`

Existing body extended (all optional):

```json
{
  "message": "这个 run 为什么 degraded？",
  "threadId": "optional-reuse",
  "workflowKey": "optional-explicit-override",
  "modelName": "",
  "state": {},
  "pageContext": {
    "path": "/runs/abc",
    "runId": "abc",
    "workflowKey": "deep_dive",
    "ticker": "AAPL",
    "recommendationId": null
  }
}
```

### 5.2 Response (unified envelope)

```json
{
  "threadId": "…",
  "content": "…",
  "message": "…",
  "ok": true,
  "domain": "RUN_DIAGNOSIS",
  "complexity": "L2",
  "routeAction": "HELP_GRAPH",
  "provider": "help-graph",
  "routedToWorkflow": false,
  "runId": null,
  "workflowKey": null,
  "needsClarification": false,
  "clarificationPrompt": null,
  "escalateHuman": false,
  "sourceRefs": [
    { "type": "playbook", "id": "degraded-meaning", "title": "什么是 DEGRADED" }
  ],
  "cacheHit": false,
  "disclaimer": null
}
```

When research workflow path: keep existing `routedToWorkflow=true` + `runId` fields.

Contract file: `help-agent-contracts/json/chat_message_response.schema.json`

---

## 6. Knowledge & Security

### 6.1 Dual pipelines

| Pipeline | Source of truth | Retrieval | Cache |
|----------|-----------------|-----------|-------|
| Public KB | Playbooks (P0) + TF-IDF lexical index (P1) | keyword + TF-IDF vector + RRF + **pre-filter** `visibility` | yes (public answer cache) |
| Private facts | MySQL runs/portfolio/recommendations | **Tools only** (Java APIs) | **never** answer cache |

### 6.2 Pre-filtering (P1 vector)

Java builds filter from `AuthenticatedPrincipal`:

```text
visibility IN ('public') OR allowed_roles CONTAINS principal.roles
```

Orchestrator only consumes search results from Java; does not re-query raw vectors with user id from the prompt.

### 6.3 Parent–child chunking (P1)

- Child: ~256–384 tokens for vector indexing
- Parent: section-level for LLM context  
- Metadata: `doc_id, parent_id, path, version, visibility, updated_at`

### 6.4 GraphRAG (P2)

Use for workflow/tool/permission topology and error→mitigation graphs only; never for L0 FAQ.

---

## 7. Help Graph (Orchestrator)

Contract: `help-agent-contracts/python/help_graph_spec.py`

### 7.1 State

```text
HelpState:
  message, thread_id, user_scope_digest,
  domain, complexity, entities,
  page_context,
  retrieval_rounds (≤3),
  chunks[], tool_results{},
  needs_clarification, clarification_prompt,
  draft, source_refs[],
  escalate_human, disclaimer,
  ok, error
```

### 7.2 Nodes (workflow-first, not free ReAct)

```text
normalize_entities
  → cache_lookup          # public only; skip if !cacheable
  → route_complexity      # may short-circuit L0
  → clarify_gate          # interrupt if missing required entity
  → retrieve
  → grade_evidence        # if fail && rounds<3 → rewrite_query → retrieve
  → tool_gather           # allowlisted tools
  → draft_answer
  → self_check
  → escalate_or_end
```

### 7.3 Self-correction

- Max **3** retrieve loops.  
- Grade signals: entity coverage, min chunk score, has actionable steps for how-to.  
- After 3 failures: `escalate_human=true`, fixed disclaimer, optional deep link to docs/support contact.

### 7.4 Human-in-the-loop

- Clarification: return to Chat with `needsClarification=true` (P0: synchronous clarify response; P1: LangGraph interrupt + resume with thread_id).  
- Never invent runId or portfolio numbers.

---

## 8. Tool Allowlist (Help role)

Extend `tool_registry.TOOL_ROLES` with `"help"`.

| Tool | Side effect | Notes |
|------|-------------|-------|
| `search_product_docs` | read | Java knowledge/playbook search |
| `get_workflow_run` | read | by runId, authz in Java |
| `get_workflow_run_nodes` | read | |
| `get_workflow_run_events` | read | |
| `get_portfolio_positions` | read | delegated token only |
| `get_stock_quote` | read | optional context |

**Forbidden:** approve/reject recommendation, portfolio write, arbitrary SQL, admin user APIs.

MCP (P2): wrap the same tools behind an internal MCP server; no new side effects.

---

## 9. Caching

### 9.1 Layer 1 — before LLM (Java)

- Key: `help:l1:{hash(normalize(message))}`; vector KNN is planned, not implemented.
- Threshold: ≥0.93 similarity for product help; **never** for investment advice.  
- Gate: `cacheable==true` and no private entities.

### 9.2 Layer 2 — before tools

- Cache public doc search results by query hash + KB version.  
- TTL short (e.g. 15–60 min).  
- Invalidate on playbook/KB publish.

### 9.3 Entity bypass (mandatory)

If message/pageContext contains: runId, portfolioId, recommendationId, email-like, long numeric account ids → `cacheable=false`.

---

## 10. Persistence

| Concern | Store |
|---------|-------|
| Chat threads/messages | MySQL via `ChatMapper` (extend to reuse `threadId`) |
| Help graph checkpoint | Orchestrator checkpointer; `thread_id = chatThreadId` |
| Cross-turn memory slots | PersistentStore (existing), namespace `help:{userId}` carefully — no secrets |

P0: generate threadId as today but accept client `threadId` if owned by user (authz check).  
P2: optional Postgres checkpointer for multi-instance.

---

## 11. Eval Stack

| Phase | Tooling | Gate |
|-------|---------|------|
| Dev | Manual + pytest fixtures | Playbook hit rate, route accuracy |
| CI (P0) | `golden20.json` | Route domain accuracy ≥ 0.9; safety 100% |
| CI (P1) | Deterministic answer-level eval on Help only | Faithfulness ≥ 0.75; Relevance ≥ 0.80; task completion ≥ 0.85 |
| Online (P2) | Langfuse / OTel + existing spans | Trace route, cache_hit, chunk_ids, escalate |

Golden set layout: `help-agent-contracts/fixtures/golden20.json`  
Target growth: 100 cases (help 40 / diagnosis 30 / safety 30).

---

## 12. Frontend (AICopilot)

Minimal P0 UX:

1. Send `pageContext` from App route (path, runId, ticker).  
2. Render `needsClarification` as assistant question.  
3. Render `sourceRefs` as chips.  
4. Render `escalateHuman` + disclaimer banner.  
5. Keep existing workflow poll path when `routedToWorkflow`.

Mode chip (optional P0): user can force `domain` hint via body `preferredDomain`.

---

## 13. Module ownership

| Component | Owner module |
|-----------|--------------|
| Intent extended + CacheGate + HelpService + Playbook | `aegis-alpha-api` |
| help_graph + intent tools extension | `aegis-alpha-orchestrator` |
| pageContext + message envelope UX | `aegis-alpha-web` |
| Playbook markdown + golden fixtures | `docs/.../help-agent-contracts` → later `resources/help/` |

---

## 14. Phased delivery

### P0 — Design contracts → thin vertical slice ✅ (landed)

- Extended intent enums + routing rules (Java + tests).  
- Static playbooks (markdown) + keyword matcher.  
- Chat response envelope fields.  
- RUN_DIAGNOSIS clarify if no runId; template diagnosis if run fetch available.  
- golden20 in CI (route-only tests acceptable).  
- **No** vector DB, GraphRAG, MCP, semantic cache.

### P1a — Run diagnosis + Chat integration ✅ (landed 2026-07-21)

- Enhanced `HelpService.diagnoseRun`: problem nodes first, recent events, suggested actions, structured `diagnosis` payload.  
- `ChatServiceHelpIntegrationTest` covers playbook / clarify / pageContext diagnosis / safety / research / copilot.  
- Safety refuse tightened (avoid blocking research「买入」phrasing).

### P1b — Hybrid lexical retrieval + help loop + cache ✅ (landed 2026-07-21)

- `KnowledgeService` classpath keyword index + ACL **pre-filter** (`visibility` / roles).  
- `HelpLoopService` (API) + orchestrator `help_loop` `/help/execute` (retrieve→grade→≤3→escalate).  
- `HelpAnswerCacheService` public-only answer cache (entity / diagnosis bypass).  
- ChatService wires: playbook → knowledge → help-loop; research/diagnosis/safety preserved.

### P2 — GraphRAG + MCP + full eval online

- Topology GraphRAG for L2.  
- MCP wrapper.  
- Langfuse; Postgres checkpointer if needed.

---

## 15. Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| D1 Entry point | Extend ChatService, no second chat product | Reuse auth, audit, AICopilot |
| D2 Research priority | RESEARCH > HELP when both match | Protects core product value & governance |
| D3 ACL | Pre-filter in Java only | Matches token model; avoids post-filter empty context |
| D4 Default runtime | Workflow/playbook first; Agent only L2 | Anthropic guidance; cost/hallucination control |
| D5 Private data | Tools not vectors | Runs/portfolio stay MySQL-scoped |
| D6 Cache | Public only + entity bypass | Privacy red line |
| D7 Critique culture | Help escalate mirrors research degraded | Consistent trust UX |
| D8 MCP | Optional P2 facade | Current HTTP tools already controlled Skills |
| D9 GraphRAG | P2, topology only | Avoid over-engineering FAQ |
| D10 Dify | Not primary for Help | Avoid dual runtime; Orch LangGraph owns help_graph |

---

## 16. Open Questions

1. **Clarify UX P0:** always synchronous follow-up message vs true interrupt/resume?  
   - **Recommendation:** P0 synchronous `needsClarification`; P1 interrupt.  
2. **Playbook location:** classpath `resources/help/playbooks` vs DB table?  
   - **Recommendation:** P0 classpath markdown; P1 DB for ops edit.  
3. **Who is “human” on escalate?** Desk Slack vs in-app ticket?  
   - **Recommendation:** P0 deep link + copy; no ticket system.  
4. **Semantic cache store:** Redis only vs Qdrant?  
   - **Recommendation:** Redis exact first; Redis vector or Qdrant in P1 if embeddings available.

---

## 17. PR Plan

### PR1 — Intent model + routing tests (API)

- Files: `IntentRouterService`, `ChatService` route branch stubs, unit tests.  
- Deps: none.  
- Ship: domain/complexity classification without full help answers.

### PR2 — Playbook engine + Chat envelope (API + contracts copy)

- Files: `HelpService`, playbook resources, `ChatController` response fields.  
- Deps: PR1.  
- Ship: L0 PRODUCT_HELP answers.

### PR3 — AICopilot pageContext + envelope UI (Web)

- Files: `AICopilot.jsx`, `App.jsx` context wiring.  
- Deps: PR2 response fields.  

### PR4 — Run diagnosis tools path (API + Orch tools)

- Files: run fetch helpers, help tool allowlist, diagnosis templates.  
- Deps: PR1–2.  

### PR5 — Orchestrator help_graph skeleton + `/help/execute` (Orch)

- Files: `help_graph.py`, router, tests with mock LLM.  
- Deps: PR2–4.  

### PR6 — Golden20 CI + docs freeze

- Files: fixtures, pytest/junit route tests, this design marked Approved.  
- Deps: PR1–2.  

### PR7+ (P1) — Knowledge RAG, semantic cache, full grade loop

- Separate design delta if needed.

---

## 18. Contract code index

```text
docs/superpowers/specs/help-agent-contracts/
  README.md
  python/intent_models.py
  python/routing.py
  python/help_graph_spec.py
  java/IntentModels.java
  java/HelpServiceContract.java
  java/ChatMessageDtos.java
  json/chat_message_response.schema.json
  fixtures/golden20.json
  playbooks/*.md
```

These files are **design-normative contracts**. Implementation PRs should align field names and enums with them; code may move into `aegis-alpha-api` / `aegis-alpha-orchestrator` packages when implementing.

---

## 19. Success metrics (P0)

| Metric | Target |
|--------|--------|
| golden20 route accuracy | ≥ 90% |
| Safety cases correct refuse/redirect | 100% |
| L0 p95 latency (playbook) | < 200ms server-side excluding network |
| No private answer in cache keys | enforced by unit tests |
| Research regression | existing intent routing tests still pass |
