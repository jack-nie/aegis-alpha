---
id: workflow-ops
title: 工作流运行与发布操作说明
visibility: public
keywords:
  - 工作流
  - workflow
  - 发布
  - published
  - 运行工作流
  - 如何运行
  - 操作步骤
---

# 工作流运行与发布

1. 仅 **published** 工作流可作为对话意图路由目标。
2. 在 Copilot 中用自然语言触发（例如「盘前日报」「深度分析 NVDA」）。
3. 系统创建 `workflow_run`，返回 runId；在运行中心 `/runs/{runId}` 查看节点。
4. 画布编辑中的草稿不会被生产对话直接触发，需先 Publish。
