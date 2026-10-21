---
id: published-workflow
title: 什么是 published workflow
keywords:
  - published
  - 已发布工作流
  - 什么是 published
  - 发布工作流
deepLink: /workflows
---

# 什么是 published workflow

**Published workflow** 是已通过校验并发布、可被意图路由与运行入口调度的工作流定义版本。

## 为什么重要

- 意图分类（Intent Router）**只应路由到已发布** 的 `workflowKey`。
- 画布编辑中的草稿不会被生产对话直接触发（除非显式调试入口）。
- 发布后变更应走版本与治理流程，保证可审计、可回放。

## 相关操作

1. 在工作流编辑器完成节点与边配置。
2. 通过校验（schema / 布局）。
3. 执行 **Publish**。
4. 在 Copilot 中用触发词或描述进行路由验证。
