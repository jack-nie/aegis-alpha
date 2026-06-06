# Aegis Alpha Orchestrator (Python)

LangGraph-based workflow orchestration engine for Aegis Alpha investment research platform.

## Architecture

```
app/
├── main.py                    # FastAPI application entry point
├── config.py                  # Configuration management (Settings singleton)
├── models/                    # Pydantic data models
│   ├── requests.py            # Request DTOs
│   ├── responses.py           # Response DTOs
│   └── workflow.py            # Workflow domain models
├── core/                      # Core business logic
│   ├── workflow_engine.py     # LangGraph StateGraph engine
│   ├── node_executor.py       # Node execution with LLM
│   ├── llm_client.py          # OpenAI-compatible LLM client
│   ├── intent_classifier.py   # Intent classification via function calling
│   └── market_data.py         # Market data hydration service
├── prompts/                   # Prompt templates
│   └── finance_prompts.py     # 18 financial analysis prompts
├── routers/                   # API routes
│   ├── health.py              # GET /health
│   ├── workflow.py            # POST /stream-workflow, /execute-*
│   └── intent.py              # POST /classify-intent
└── utils/                     # Utility functions
```

## Quick Start

### 1. Setup Virtual Environment

```powershell
cd aegis-alpha-orchestrator
.\setup-venv.ps1
```

### 2. Start Services

```powershell
# From project root
.\restart-local.ps1
```

### 3. Verify

```bash
curl http://127.0.0.1:8787/health
```

## API Endpoints

| Method | Path                | Description                      |
| ------ | ------------------- | -------------------------------- |
| GET    | `/health`           | Health check                     |
| POST   | `/stream-workflow`  | SSE streaming workflow execution |
| POST   | `/execute-workflow` | Non-streaming workflow execution |
| POST   | `/execute-node`     | Execute single workflow node     |
| POST   | `/execute-agent`    | Execute single agent node        |
| POST   | `/classify-intent`  | Intent classification via LLM    |

## OOP Design Principles

- **Single Responsibility**: Each class has one clear purpose
- **Dependency Injection**: Services receive dependencies via constructor
- **Strategy Pattern**: Different handlers use different prompt templates
- **Singleton Pattern**: Configuration is loaded once and shared
- **Facade Pattern**: WorkflowEngine simplifies LangGraph complexity

## Key Classes

### `Settings` (config.py)

Application configuration loaded from environment variables. Uses `pydantic-settings` for validation.

### `WorkflowEngine` (core/workflow_engine.py)

Orchestrates LangGraph StateGraph execution. Builds graph from nodes/edges, handles streaming.

### `NodeExecutor` (core/node_executor.py)

Executes individual workflow nodes. Handles mock mode, market data hydration, LLM invocation.

### `LLMClient` (core/llm_client.py)

OpenAI-compatible LLM client. Supports DeepSeek and other providers.

### `PromptManager` (prompts/finance_prompts.py)

Manages 18 financial analysis prompt templates.

## Environment Variables

See `.env` in project root. Key variables:

- `AEGIS_ALPHA_LANGGRAPH_PORT` - Server port (default: 8787)
- `AEGIS_ALPHA_LANGCHAIN_PROVIDER` - LLM provider (default: openai)
- `AEGIS_ALPHA_LANGCHAIN_MODEL` - LLM model (default: deepseek-v4-flash)
- `AEGIS_ALPHA_LANGCHAIN_API_KEY` - API key
- `AEGIS_ALPHA_LANGCHAIN_MOCK` - Enable mock mode

## Migration from Node.js

This Python service is a 1:1 replacement for the Node.js `server.mjs`. All API contracts are preserved for backward compatibility with the Java backend.
