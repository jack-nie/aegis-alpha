# Aegis Alpha 规划蓝图：可治理股权研究 Agent 平台

**Date:** 2026-07-09  
**Status:** Draft for product approval  
**Related:**

- Discussion: `docs/superpowers/specs/2026-07-09-expert-council-discussion.md`
- Detailed design: `docs/superpowers/specs/2026-07-09-equity-research-agent-platform-design.md`
- Prior enterprise arc: `docs/superpowers/specs/2026-05-04-enterprise-agent-orchestration-architecture.md`

---

## 1. 愿景与一句话战略

**愿景：** 让 3 人投研台每天能完成：提问 → 跑受控研究 Agent → 审证据 → 批/驳建议 → 回看组合语境。

**战略一句话：**

> 不做自治交易员；做 **在已发布工作流闸门内的、工具增强的、证据化、可审批的研究 Agent 平台**。

**北极星指标：**

> **没有无来源数字的 BUY/SELL 能进入 `PENDING_REVIEW`。**

---

## 2. 现状快照（2026-07）

| 维度 | 现状 | 判断 |
|------|------|------|
| 形态 | Workflow-first + 局部 `general.agent` + ToolNode | L1.5–L2 Agent 成熟度 |
| 栈 | Java SoR + Python LangGraph + Next.js canvas/chat | 边界清晰，可演进 |
| 最近进展 | Intent 契约、tool 路径、SSE、行情 race、token fail-closed 已修 | 主链可再硬化 |
| 完备性 | ~2.6/5 日用线 | 可演示 / 内测，非无护栏日产 |
| 已有设计资产 | evidence/recommendation/approval 表与 enterprise 文档 | 设计超前于实现，需收敛到闭环 |

---

## 3. 目标架构（逻辑）

```text
┌─────────────────────────────────────────────────────────────┐
│ Web (thin client)                                           │
│ Chat · Canvas · Run Center · Evidence Viewer · Approval Inbox│
└───────────────────────────┬─────────────────────────────────┘
                            │ Auth + commands + SSE consume
┌───────────────────────────▼─────────────────────────────────┐
│ Java Spring Boot — System of Record & Policy                │
│ Identity · Workflow lifecycle · Recommendation/Evidence     │
│ Governance approve/reject · Audit · Portfolio write · Model │
└───────────────────────────┬─────────────────────────────────┘
                            │ service auth + run context
┌───────────────────────────▼─────────────────────────────────┐
│ Python Orchestrator — Bounded Executor                      │
│ Intent (publish-only) · LangGraph DAG · Supervisor/Workers  │
│ Tools (read) · Critique · Checkpoint · SSE events           │
└───────────────────────────┬─────────────────────────────────┘
                            │ market / portfolio_read tools
                      ┌─────▼─────┐
                      │ Providers │
                      └───────────┘
```

**所有权铁律：**

| 关心点 | 权威 |
|--------|------|
| 用户身份、审批、推荐终态、审计 | Java |
| LLM/tool 子图执行、checkpoint | Orchestrator |
| 呈现与命令，无业务真相 | Web |

---

## 4. 能力地图（研究 × Agent × 平台）

### 4.1 研究环（按 ROI）

| Priority | Loop | Phase |
|----------|------|-------|
| P0 | Single-name deep dive（现有 recommendation 工作流硬化） | 0 |
| P1 | Earnings reaction | 1 |
| P1 | Watchlist morning digest | 1 |
| P1 | Peer relative value | 1 |
| P2 | Portfolio risk contribution | 2 |

### 4.2 Agent 能力阶梯

| Level | 含义 | 目标到达 |
|-------|------|----------|
| L1 | 发布 DAG + LLM 节点 + 落库 | 已基本具备 |
| L2 | 节点内 tool loop + interrupt + 错误分类 | **Phase 0 完成** |
| L3 | Supervisor + specialists + critique + tool 归因 | **Phase 1** |
| L4 | 在线评测驱动 policy / 自动运营 | 非本季度 |

### 4.3 平台日用闭环（Must-have）

1. 稳定 E2E：Chat/模板 → run → stream → recommendation + evidence  
2. 证据可审（asOf / source / trustTier / claim 绑定）  
3. 审批 Inbox 一屏完成  
4. 行情可信元数据（provider, asOf, degraded banner）  
5. 组合数据诚实合同  
6. 服务间鉴权全路径  
7. Deep link：`/runs/:id`、`/recommendations/:id`  
8. Run Center 只暴露 `availableActions`

---

## 5. 分阶段蓝图

### Phase 0 — 可信最小环（0–2 周）

**主题：** 止血 + 证据门禁 + 真 L2

| 工作包 | 交付物 | 成功标准 |
|--------|--------|----------|
| P0-R1 输出契约 | 统一 `RecommendationDraft` JSON；废除「禁止 insufficient」类 prompt | 缺数 → `INSUFFICIENT_DATA` |
| P0-R2 证据绑定 | 价格/关键财务/推荐数字 → claim↔evidence | BUY/SELL 无 claim 不可 PENDING |
| P0-R3 市场口径 | symbol/market 规范化硬失败 | A/US 混用被拒 |
| P0-A1 Tool 硬化 | multi-agent tool 回跳归因；portfolio delegated read token | 无串台 / 无 401 静默 |
| P0-A2 错误与 SSE | error taxonomy；`degraded` / `human_interrupt` 事件 | 前端可区分失败与降级 |
| P0-A3 Orchestrator auth | 入站 service token | 裸网不可调 execute |
| P0-P1 E2E smoke | Chat→run→materialize→approve 脚本进 CI | CI 绿 |
| P0-P2 审批 UX | 详情页批/驳 + 草稿水印 | 未 APPROVED 不冒充可交易 |
| P0-E1 Golden-10 | 10 条 fixed ticker fixture | numerical fidelity 门禁 |

**非目标：** 新 specialist 大图、自动交易、OIDC、全文 RAG。

### Phase 1 — 可用研究台（2–6 周）

**主题：** 小团队习惯 + L3 雏形

| 工作包 | 交付物 |
|--------|--------|
| P1-R1 | Earnings reaction 工作流 |
| P1-R2 | Peer set + relative value 表 |
| P1-R3 | Watchlist morning digest（变更驱动） |
| P1-R4 | confidence 规则引擎（覆盖/stale/冲突） |
| P1-A1 | Tool registry + per-role allowlist |
| P1-A2 | Supervisor + 3–5 specialists（DAG 内） |
| P1-A3 | Critique 节点强制 `evidence_ids` / `missing_data` |
| P1-A4 | Memory 注入 observe + span `memory_hits` |
| P1-P1 | Portfolio 三件套合同 + IA |
| P1-P2 | Deep link + Run Center 本地化动作 |
| P1-P3 | 模型 fallback 语义：degraded ≠ success；$/run |
| P1-E1 | 30 条 golden + offline scorecard CI gate |

**非目标：** 多租户 SaaS 完备、write tools、自由组网多智能体。

### Phase 2 — 组合语境与产品化 L3（6–12 周）

| 工作包 | 交付物 |
|--------|--------|
| P2-R1 | Portfolio risk contribution |
| P2-R2 | Filings 元数据证据（链接+日期） |
| P2-R3 | 推荐 post-mortem 与 rubric 仪表 |
| P2-A1 | 发布版 multi-agent research SKU + 版本钉扎 |
| P2-A2 | Online metrics；model budget 闭环 |
| P2-A3 | Episodic 复盘 API |
| P2-P1 | 版本化 DB migration 渐进 |
| P2-P2 | 长跑 cancel/resume 跨重启一致 |
| P2-P3 | 最小 OTel/metrics；密钥分环境 |
| P2-E1 | 人工标注回流；prompt/workflow 版本绑定 |

**非目标：** L4 自治下单、无人工对外推荐触达、Spring Security 大挪移、换栈。

---

## 6. 里程碑与依赖

```text
Week 0-2   Phase 0  ──E2E+证据门禁──►  内测日用最小环
                │
Week 2-6   Phase 1  ──研究环扩展+L3雏形──►  3 人台习惯养成
                │
Week 6-12  Phase 2  ──组合语境+在线质量──►  可对外演示的治理研究台
```

**关键依赖链：**

```text
Tool auth & contracts → Evidence materialize → Approve gate → Trust UX
        ↘ Golden eval ↗
```

---

## 7. 组织与节奏建议

| 角色 | 负责 |
|------|------|
| Research owner | Schema、rubric、金标样本、是否可批 |
| Agent owner | LangGraph 拓扑、tool registry、critique |
| Platform owner | Java lifecycle、auth、CI smoke、UX deep link |
| Shared weekly | 1 次「废弃 run 复盘」：失败码 + 是否 silent success |

---

## 8. 风险与缓解（蓝图层）

| 风险 | 缓解 |
|------|------|
| 继续堆节点导致 workflow theater | Phase 0 门禁：无 golden / 无 evidence 不扩图 |
| 双运行时状态分叉 | Java runId 权威；reconcile job |
| Prompt 静默劣化 | golden scorecard CI |
| 安全裸奔 orchestrator | Phase 0 入站 auth + 网络隔离 |
| 文档 enterprise 幻觉 | 以本蓝图 must-have 为排期唯一真相源 |

---

## 9. 明确不做（全年默认）

- 自动下单 / Agent 直接 trade write  
- 无证据的「主力资金」叙事  
- 全市场另类数据湖  
- Free-form ReAct 作为默认生产路径  
- 为画架构图而引入的重型中间件（无闭环价值则不做）

---

## 10. 批准检查清单

在开始写实现计划 / 编码前，产品方确认：

- [ ] 接受「Governed Research Agent」定位（非交易自治）  
- [ ] 接受北极星：无来源数字不可 PENDING  
- [ ] 接受 Phase 0 范围与非目标  
- [ ] 接受 80% 模板 / 20% 探索，探索不进批准池  
- [ ] 接受双运行时所有权铁律  

**下一步：** 批准后按 detailed design 拆 `writing-plans` 实现计划（Phase 0 优先）。
