# Orchestrator (LangGraph Engine)

## Safety Rules

- server.mjs 是单文件 ~35K，修改前先用搜索定位到正确的路由/section
- 支持 OpenAI 和 DeepSeek 两个 provider — 新增 provider 前检查 provider 选择逻辑
- Mock 模式 (`MOCK_MODE=true`) 用于离线测试 — 新增功能需同时支持 mock 和真实模式
- LangGraph 状态图定义在文件顶部 — 修改 workflow 前先理解 StateGraph 定义

## Known Traps

- Express 路由和 LangGraph 节点定义混在同一个文件 — 不要误删
- 环境变量 `OPENAI_API_KEY` 和 `DEEPSEEK_API_KEY` 按 provider 选择，不是同时使用
- 前端通过 `/_backend/*` 代理到此服务（Next.js rewrite），端口 5179
