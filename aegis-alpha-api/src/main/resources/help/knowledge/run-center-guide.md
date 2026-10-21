---
id: run-center-guide
title: 运行中心使用指南
visibility: public
keywords:
  - 运行中心
  - 节点输出
  - run 详情
  - 查看进度
  - SSE
---

# 运行中心

- 路径：`/runs` 与 `/runs/{runId}`
- 可查看状态：QUEUED / RUNNING / COMPLETED / FAILED / DEGRADED
- 节点列表含 status、errorMessage、output 摘要
- 助手在 run 详情页可带 pageContext.runId 做诊断（私有数据不走公开知识缓存）
