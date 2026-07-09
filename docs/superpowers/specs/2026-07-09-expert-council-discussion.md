# Expert Council Discussion Notes

**Date:** 2026-07-09  
**Product:** Aegis Alpha — Equity Research Agent Platform  
**Format:** Three independent position papers + chair synthesis  
**Participants:**

| Role | Focus |
|------|--------|
| Equity Research Director | Research-grade DoD, evidence, output schema, anti-patterns, eval rubric |
| Principal Agent Architect | Maturity L0–L4, hybrid architecture, tool/memory/eval systems |
| Platform Completeness Architect | Daily-use bar for 3-person desk, ownership, security, UX, risk |

---

## 1. Consensus (三位一致)

1. **产品定位**不是「自治交易 Agent」，而是 **Governed Research Agent**：在已发布工作流闸门内，用工具做可中断、可审批、可审计的研究。
2. **当前成熟度**约 **L1.5–L2**（workflow 主体 + 局部 tool-using agent），日用完备性约 **2.6/5**；可演示，不可无护栏日产研。
3. **差异化 KPI**不是「更长的分析段落」，而是：**没有任何无来源数字的 BUY/SELL 能进入 PENDING_REVIEW**。
4. **架构边界**：Java = durable truth + policy；Orchestrator = smart disposable executor；Web = thin client。
5. **Agent 形态**：Hybrid = Workflow-gated Supervisor + Specialist Workers；否决 pure free-form ReAct 作为生产默认。
6. **研究主环 ROI**：Single-name deep dive → Earnings reaction → Watchlist digest → Peer RV → Portfolio context。
7. **路线图原则**：2 周打通可信闭环；6 周养成小团队习惯；季度做 L3 与治理纵深——不为 enterprise 愿景层牺牲闭环。
8. **失败语义**：允许 degraded complete；禁止 silent success（关键 tool 全失败仍 `ok=true`）。

---

## 2. Resolved Disagreements（主席裁定）

| 议题 | Research | Agent | Platform | **裁定** |
|------|----------|-------|----------|----------|
| 数字权威路径 | Tool facts → 模板 → LLM 只写定性 | Structured tools + critique | 合同字段 + asOf | **采用 structured facts 优先**：LLM 不得发明 PE/价格；聚合层校验 claim↔evidence |
| confidence 所有者 | 规则/证据完整度 | min(model, rules) | 绑定 degraded | **Java/规则层计算，与 LLM 自评取 min**；无 evidence cap ≤0.3 |
| INSUFFICIENT vs HOLD | 缺关键数据 → INSUFFICIENT | critique 标 missing | 产品可见 | **关键包缺失 → INSUFFICIENT_DATA**；有数据但中性观点 → HOLD |
| Evidence 粒度 | claim-level | evidence_ids 强制 | materialize + viewer | **Phase 0 node-level + 关键 claim 绑定 JSON；Phase 1 扩 claim 表字段** |
| Mock/degraded 落库 | 可写但不可批 | degraded 事件 | 黄条禁批 | **可落库，强制 `degraded=true` + 不可 APPROVE** |
| 审批点 | 最终推荐 | aggregate 前/后 interrupt | Inbox 日用 | **默认最终 recommendation 前 human gate**；高风险工作流可额外 pre-aggregate |
| 自由 Agent vs 锁定模板 | 研究深度 | DAG 内动态 | 80/20 | **80% 发布模板 + 20% 探索模式；探索产物默认不进批准池** |
| 写工具 | 禁自动交易 | research-only 默许 | portfolio 只读 tool | **Agent 永不 place order**；portfolio 仅 delegated read |
| 市场范围 Phase 1 | 锁单一市场更安全 | 工具已支持多市场 | 编码/asOf 优先 | **双市场可跑，但口径规范化硬失败；评测集分 US / A-share** |
| 目标价 | 区间+方法，禁神价 | 结构化 draft | 展示诚实 | **fair_range + method；单点 target 仅作 optional 且必须绑 evidence** |
| SoR 写入时机 | — | decide 后写 PENDING | run 结束 materialize | **run 成功/degraded 结束即写草稿 recommendation；Publish/Approve 改状态** |
| Checkpoint vs Java | — | Java resume 权威 | runId 权威 | **Java `workflow_run` 为生命周期权威；checkpoint 仅执行恢复** |

---

## 3. Non-Consensus / Parked

- 双人复核审批：内网 Phase 1 单 approver 即可；SaaS 前再引入。
- Filings 全文 RAG：Phase 2 仅元数据链接，不做全库向量幻想。
- Ownership/flow 叙事：无可靠源永久 missing，禁止编造「主力」。
- Spring Security / OIDC：SaaS 门槛，不挡内网日用闭环。
- Dify：仅导入/协作通道，不成为第二运行时真相源。

---

## 4. Shared Success Metrics（30 天 / 3 人台）

| KPI | Target (illustrative) |
|-----|------------------------|
| 可完成 E2E 研究 run 成功率（含 degraded 分类正确） | ≥ 90% |
| 进入 PENDING 的 BUY/SELL 中 claim 数字可追溯率 | **100%** |
| 提问 → 待审推荐 中位时长 | ≤ 10 min（single-name 模板） |
| 因数据/契约问题废弃的 run 占比 | 下降趋势，周报可见 |
| 审批操作可在 Inbox 一屏完成 | 是 |

---

## 5. Source Artifacts

Full position papers were produced in-session by specialist subagents (2026-07-09). Key excerpts absorbed into:

- `docs/superpowers/plans/2026-07-09-equity-research-agent-blueprint.md`
- `docs/superpowers/specs/2026-07-09-equity-research-agent-platform-design.md`
