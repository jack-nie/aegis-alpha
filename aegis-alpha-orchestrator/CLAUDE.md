# Orchestrator (LangGraph Engine)

## Safety Rules

- Python FastAPI 应用，核心逻辑在 app/core/ 目录
- workflow_engine.py 负责 LangGraph 状态图构建和执行
- node_executor.py 负责节点业务逻辑和 LLM 调用
- tools.py 封装后端 API 为 LangChain @tool，供 Agent 节点主动调用
- memory_store.py 提供跨线程记忆（PersistentStore + SQLite + TTL）
- persistent_store.py 是 SQLite 持久化存储的底层实现
- 支持 OpenAI 和 DeepSeek 两个 provider（通过 OpenAI-compatible API）
- Mock 模式 (`AEGIS_ALPHA_LANGCHAIN_MOCK=true`) 用于离线测试
- 环境变量使用 AEGIS*ALPHA*\* 前缀

## Architecture

```
app/
├── core/
│   ├── workflow_engine.py    ← LangGraph StateGraph 构建 + 执行
│   ├── node_executor.py      ← 节点业务逻辑 + LLM 重试
│   ├── llm_client.py         ← ChatOpenAI 封装 + 流式 + 客户端缓存
│   ├── intent_classifier.py  ← LLM function-calling 意图路由
│   ├── market_data.py         ← 行情数据灌入
│   ├── tools.py              ← 6 个 @tool 封装后端 API
│   ├── memory_store.py       ← 跨线程记忆管理器
│   └── persistent_store.py   ← SQLite 持久化存储 + TTL
├── models/                    ← Pydantic DTOs
├── prompts/                   ← 18 个金融分析 prompt
├── routers/                   ← FastAPI 路由
└── config.py                  ← Pydantic Settings
```

## Features

- **Conditional edges**: workflow 支持条件分支
- **Checkpointing**: SQLite-backed (SqliteSaver)，断点恢复
- **Cross-thread memory**: SQLite Store，namespace: user/ticker/workflow
- **TTL**: ticker insights 默认 30 分钟过期，用户偏好和 workflow pattern 永久
- **Tool calling**: general.agent 节点可绑 6 个工具主动查数据
- **Token streaming**: SSE /stream-workflow-tokens 端点
- **Node retry**: LLM 调用失败自动重试 2 次（指数退避）
- **Deep merge**: 并行节点不再互相覆盖 state
- **Human-in-the-Loop**: approval_gate 节点，聚合前可暂停等审批

## Known Traps

- Graph 编译结果会缓存（LRU 100），相同拓扑不会重复编译
- LLM client 实例会复用（缓存 10 个）
- `AEGIS_ALPHA_DATA_DIR` 控制 SQLite 存储目录，默认 `data/`
- 前端通过 `/_backend/*` 代理到此服务（Next.js rewrite），端口 8787
