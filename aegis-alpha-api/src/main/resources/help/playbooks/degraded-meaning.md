---
id: degraded-meaning
title: 什么是 DEGRADED
keywords:
  - degraded
  - DEGRADED
  - 降级
  - 什么是 degraded
  - 为什么 degraded
deepLink: /runs
---

# 什么是 DEGRADED

当研究工作流跑完但**证据不足、部分节点失败或策略门禁未完全满足**时，运行状态可能为 `DEGRADED` / `COMPLETED_DEGRADED`。

## 含义

- 系统**仍然产出了结果**，但标记为降级，表示结论可信度受限。
- 带有 actionable 推荐（如 BUY/SELL）时，**审批侧通常应阻断或谨慎处理**（以治理策略为准）。
- 常见原因：行情/财务数据缺失、新闻源失败、critique 发现 claims 无 evidence。

## 你该怎么做

1. 打开 **运行中心**，查看失败或 degraded 节点输出。
2. 检查 subject/ticker 是否正确、数据源是否可用。
3. 必要时修正输入后重跑同一 published workflow。
4. 不要在证据不足时强行审批。

## 相关

- 运行中心路径示例：`/runs/{runId}`
- 若需助手诊断：在 run 详情页打开 Copilot 并提问「为什么 degraded」。
