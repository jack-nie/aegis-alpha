---
id: run-workflow
title: 怎么运行一个工作流
keywords:
  - 怎么运行
  - 如何运行工作流
  - 启动 workflow
  - 运行工作流
  - 如何跑
deepLink: /workflows
---

# 怎么运行一个工作流

## 方式 A：对话路由（推荐）

1. 打开 **AI Copilot**。
2. 用自然语言描述任务，例如：
   - 「给我盘前日报」
   - 「深度分析 NVDA」
3. 若命中 published workflow，助手会返回 `runId` 并开始执行。
4. 在 **运行中心** 查看节点进度与结果。

## 方式 B：工作流画布 / 模板

1. 打开工作流列表，选择已 **published** 的定义。
2. 填写 subject（如 ticker）与输入。
3. 点击运行，记录 `runId`。

## 注意

- 仅 **已发布** 的工作流可作为意图路由目标。
- 未发布或布局无效时，系统可能回退到普通 Copilot 对话。
