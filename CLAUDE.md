# CLAUDE.md

> Aegis Alpha — 投资研究 & AI Agent 工作流编排平台

## 📚 项目概述

**核心功能**：投资组合管理、市场数据采集（Yahoo/Stooq/SEC/GDELT）、LangGraph Agent 执行、回测、推荐治理（审批/驳回）。

**技术栈**：
- **Backend**: Spring Boot 2.7.18 / Java 17 / MyBatis / MySQL 8.0 / Redis 7
- **Orchestrator**: Python 3.11 / FastAPI / LangGraph 0.2.x / langchain-openai 0.2.x
- **Frontend**: Next.js 15 App Router / React 19 / Tailwind CSS / @xyflow/react / JSX（非 TypeScript）
- **Infra**: Docker Compose / GitHub Actions CI

**目录结构**：
```
aegis-alpha-api/          → Spring Boot 后端 (com.aegis.alpha)
aegis-alpha-orchestrator/ → LangGraph 执行引擎 (Python FastAPI)
aegis-alpha-web/          → Next.js 前端 (app/ 目录)
docs/                     → 架构文档和实施计划
scripts/                  → 冒烟测试脚本
```

---

## 🛠️ 开发规范

### 后端（Java）

- 使用 Lombok `@Data`/`@Builder`，避免手写 getter/setter
- Controller 只做参数校验和转发，业务逻辑放 Service
- MyBatis XML mapper 放 `resources/mapper/`，接口放 `mapper/` 包
- ID 用 `Long`，状态用 `Integer`，金额用 `BigDecimal`
- 命名：camelCase

### 前端（JS/JSX）

- 使用 ESM (`import/export`)，不用 CommonJS (`require`)
- `async/await` 替代 Promise 链
- 组件不超过 300 行，超过则拆分
- 命名：camelCase
- 样式：Tailwind CSS + lucide-react，不引入其他 UI 框架

### 通用

- 变量名全拼，不缩写（除 id/url/ctx/req/res）
- 不留注释掉的代码块或 `console.log`（调试完必须清理）
- SQL 命名：snake_case

---

## 🚫 禁止引入

除非用户明确要求，否则不得引入：

| 类别 | 禁止项 | 原因 |
|------|--------|------|
| 安全框架 | Spring Security | 项目用自定义 HmacSHA256 Token |
| 类型系统 | TypeScript | 前端锁定 JSX |
| 状态管理 | Redux / MobX | 使用 React 原生 state + props |
| ORM | JPA / Hibernate | MyBatis 已锁定 |
| 数据库 | MongoDB / PostgreSQL | 数据层锁定 MySQL |
| UI 框架 | Material UI / Ant Design | 全站 Tailwind + lucide-react |
| CSS 方案 | CSS-in-JS | 使用 Tailwind |

---

## 🗄️ 数据库操作

- **连接目标**：MySQL 8.0，本地端口 3306，数据库 `aegis_alpha`
- **用户**：`aegis`，密码通过 `.env` 配置（`AEGIS_ALPHA_DB_PASSWORD`）
- **Schema 来源**：`aegis-alpha-api/src/main/resources/db/mysql/schema.sql`
- **MyBatis Mapper**：`aegis-alpha-api/src/main/resources/mapper/*.xml`
- **安全约束**：DDL 操作需用户确认，不可直接执行
- **SQL 注释版本**：修改 SQL 文件后必须在注释中加版本号

---

## 🔧 Hooks & 质量门禁

以下规则由 `.claude/settings.json` Hook 强制执行：

- 每次编辑 JS/JSX/MJS 文件后自动格式化（PreToolUse hook → prettier）
- 每次编辑后自动 lint 检查（PostToolUse hook → eslint）
- 格式化失败时警告但不阻断（on_failure: warn）

相关命令：
```bash
npm run format        # 手动格式化
npm run format:check  # 检查格式
npm run lint          # lint 检查
npm run lint:fix      # 自动修复
```

---

## 📐 文档与记忆

### 设计文档

- `docs/` 下的架构文档是业务逻辑与架构的参考依据
- 涉及核心逻辑变更时，应先检查是否有相关设计文档

### Memory

- `MEMORY.md` 记录关键洞察、最佳实践和已知陷阱
- 每次新任务开始前先读取，任务结束后有新发现则更新

### Context Tiers

| 层级 | 内容 | 加载时机 |
|------|------|----------|
| Tier 1 | 本文件 | 每次加载 |
| Tier 2 | `docs/` 下的架构文档 | 按需加载 |
| Tier 3 | `logs/`、临时脚本 | 忽略 |

---

## 🎯 工作方式

- 先给方案，不要直接写代码
- 不确定时列出选项，不要猜测
- 重大变更前先问，小优化可以直接执行
- 不要用「Great question!」「I'd be happy to help!」这类废话
- 回复用中文，代码注释用英文
- 文件路径用绝对路径，不要相对路径

---

## ⚠️ 已知陷阱

- Orchestrator 已从 Node.js 重写为 Python FastAPI，核心逻辑在 `app/core/` 目录
- 前端是 catch-all routing (`[...path]/page.jsx`)，所有页面在 App.jsx 内切换
- 自定义 Token 认证（HmacSHA256），不是标准 JWT — 修改 auth 前必须理解 TokenService

---

## 📁 文件管理

- **少量删除原则**：仅允许删除由本对话生成的单个文件，禁止批量删除
- **禁止危险命令**：禁用 `rm -rf`、`Remove-Item -Recurse` 等递归删除
- **执行流**：删除前必须明确文件路径、说明必要性、获得用户确认
- **优先移动**：建议移动到备份目录而非物理删除
