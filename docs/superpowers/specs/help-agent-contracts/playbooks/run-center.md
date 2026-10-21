---
id: run-center
title: 运行中心如何查看节点输出
keywords:
  - 运行中心
  - 节点输出
  - 查看 run
  - /runs
  - 节点详情
deepLink: /runs
---

# 运行中心如何查看节点输出

1. 打开 **运行中心**（Runs）。
2. 按时间或状态筛选，点击目标 `runId`。
3. 在详情中查看：
   - 总状态：`QUEUED` / `RUNNING` / `COMPLETED` / `FAILED` / `DEGRADED`
   - 各节点状态与输出摘要
   - 错误信息（如有）
4. Deep link 格式：`/runs/{runId}`。

## 与助手联动

在 run 详情页打开 Copilot，直接问「这个 run 为什么失败 / degraded」，助手应带上当前 `runId` 做诊断（不会缓存该私有查询）。
