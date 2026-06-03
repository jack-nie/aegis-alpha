# Aegis Alpha

Investment research & AI agent workflow orchestration platform.
Core: portfolio management, market data (Yahoo/Stooq/SEC/GDELT), LangGraph agent execution, backtest, recommendation governance with approval/rejection.

## Tech Stack

- **Backend**: Spring Boot 2.7.18 / Java 8 / MyBatis / MySQL 8.0 / Redis 7
- **Orchestrator**: Node.js / Express 4.19 / @langchain/langgraph 0.4.x / @langchain/openai 0.6.x
- **Frontend**: Next.js 15 App Router / React 19 / Tailwind CSS / @xyflow/react / JSX (not TypeScript)
- **Infra**: Docker Compose / GitHub Actions CI

Do NOT introduce unless explicitly requested:
- Spring Security (项目用自定义 HmacSHA256 Token，不用 Spring Security)
- TypeScript（前端是 JSX，不接受 TS）
- Redux / MobX（前端状态用 React 原生 state + props）
- ORM 替换（MyBatis 已锁定，不接受 JPA/Hibernate）
- MongoDB / PostgreSQL（数据层已锁定 MySQL）
- Material UI / Ant Design（全站 Tailwind + lucide-react）
- 新的 CSS-in-JS 方案

## Project Structure

```
aegis-alpha-api/          → Spring Boot 后端 (com.marketmind.alpha)
aegis-alpha-orchestrator/ → LangGraph 执行引擎 (server.mjs 单文件)
aegis-alpha-web/          → Next.js 前端 (app/ 目录)
docs/                     → 架构文档和实施计划
scripts/                  → 冒烟测试脚本
```

## Coding Rules

- Java: 使用 Lombok @Data/@Builder，避免手写 getter/setter
- Java: Controller 只做参数校验和转发，业务逻辑放 Service
- Java: MyBatis XML mapper 放 resources/mapper/，接口放 mapper/ 包
- JS: 使用 ESM (import/export)，不用 CommonJS (require)
- JS: async/await 替代 Promise 链
- JSX: 组件不超过 300 行，超过则拆分
- 变量名全拼，不缩写（除 id/url/ctx/req/res）
- 不留注释掉的代码块或 console.log（调试完必须清理）
- 命名：Java camelCase，JS/JSX camelCase，SQL snake_case

## Hooks & Quality Gates

以下规则由 `.claude/settings.json` Hook 强制执行，不是提醒：
- 每次编辑 JS/JSX/MJS 文件后自动格式化（PreToolUse hook → prettier）
- 每次编辑后自动 lint 检查（PostToolUse hook → eslint）
- 格式化失败时警告但不阻断（on_failure: warn）

相关命令：
- `npm run format` — 手动格式化全部 JS/JSX 文件
- `npm run format:check` — 检查格式是否合规
- `npm run lint` — 手动 lint 检查
- `npm run lint:fix` — 自动修复 lint 问题

## Context Tiers

Tier 1（每次加载）：本文件 — 项目是什么 + 怎么工作
Tier 2（按需加载）：
  - `docs/superpowers/specs/` — 架构规格
  - `docs/superpowers/plans/` — 实施计划
  - `docs/dify-integration-design.md` — Dify 集成设计
Tier 3（忽略）：`logs/`、根目录的 fix_*.py / patch_*.py — 临时调试脚本

## Memory

`MEMORY.md` 记录了之前任务中发现的关键洞察、最佳实践和已知陷阱。
每次新任务开始前，先读取 MEMORY.md。
每次任务结束后，如果有新的发现，更新 MEMORY.md。

## My Working Style

- 先给方案，不要直接写代码
- 不确定时列出选项，不要猜测
- 重大变更前先问，小优化可以直接执行
- 不要用「Great question!」「I'd be happy to help!」这类废话
- 回复用中文，代码注释用英文
- 文件路径用绝对路径，不要相对路径

## Known Pitfalls

- 项目从 MarketMind 重命名为 Aegis Alpha，但 Java 包名仍是 `com.marketmind.alpha`，环境变量仍是 `MARKETMIND_*`
- orchestrator/server.mjs 是单文件 ~35K 行，修改前先定位到正确的 section
- 前端是 catch-all routing (`[...path]/page.jsx`)，所有页面在 App.jsx 内切换
- CI workflow 文件名仍是 `marketmind-ci.yml`，目录引用也是旧名
- 自定义 Token 认证（HmacSHA256），不是标准 JWT — 修改 auth 前必须理解 TokenService
