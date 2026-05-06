# Aegis Alpha Orchestrator

Optional LangChain/LangGraph execution engine for `aegis-alpha-api`.

```powershell
Set-Location D:\workspace\ai\aegis-alpha-orchestrator
npm install
$env:OPENAI_API_KEY = "..."
$env:MARKETMIND_LANGCHAIN_BASE_URL = "https://api.deepseek.com"
$env:MARKETMIND_LANGCHAIN_MODEL = "deepseek-v4-flash"
npm run dev
```

Enable Java delegation:

```powershell
$env:MARKETMIND_LANGCHAIN_ENABLED = "true"
$env:MARKETMIND_LANGCHAIN_ENGINE_URL = "http://127.0.0.1:8787"
```

If no API key is configured, the engine still returns deterministic local outputs so workflow orchestration can be tested offline.
