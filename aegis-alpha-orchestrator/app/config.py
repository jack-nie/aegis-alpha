"""Application configuration management."""

import os
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field
from dotenv import load_dotenv

# Load .env from project root
_env_path = Path(__file__).parent.parent.parent / ".env"


def _load_env_file(path: Path) -> bool:
    """P3-11: .env only fills gaps; real process env (deploy, CI, tests) always wins."""
    return load_dotenv(path, override=False)


_load_env_file(_env_path)


# Documented sample value; never accepted outside explicit dev/mock mode.
EXAMPLE_SERVICE_TOKEN = "local-workflow-node-token"


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""

    # Server
    port: int = Field(default=8787, alias="AEGIS_ALPHA_LANGGRAPH_PORT")
    host: str = Field(default="0.0.0.0", alias="AEGIS_ALPHA_LANGGRAPH_HOST")

    # LLM Provider
    provider: str = Field(default="openai", alias="AEGIS_ALPHA_LANGCHAIN_PROVIDER")
    model: str = Field(default="deepseek-v4-flash", alias="AEGIS_ALPHA_LANGCHAIN_MODEL")
    api_key: str = Field(default="", alias="AEGIS_ALPHA_LANGCHAIN_API_KEY")
    base_url: str = Field(default="", alias="AEGIS_ALPHA_LANGCHAIN_BASE_URL")
    timeout_ms: int = Field(default=25000, alias="AEGIS_ALPHA_LANGCHAIN_TIMEOUT_MS")
    # P4-6: model registry file (YAML). Empty = the packaged app/core/models.yaml.
    models_file: str = Field(default="", alias="AEGIS_ALPHA_MODELS_FILE")
    mock_mode: bool = Field(default=False, alias="AEGIS_ALPHA_LANGCHAIN_MOCK")

    # Intent router (classification). Empty model -> fall back to the main model.
    router_model: str = Field(default="", alias="AEGIS_ALPHA_ROUTER_MODEL")
    router_timeout_ms: int = Field(default=2500, alias="AEGIS_ALPHA_ROUTER_TIMEOUT_MS")

    # Structured output: "function_calling" (portable across OpenAI-compatible providers such as
    # DeepSeek) or "json_schema" / "json_mode" where the provider supports them.
    structured_output_method: str = Field(
        default="function_calling", alias="AEGIS_ALPHA_STRUCTURED_OUTPUT_METHOD"
    )
    # Node-level LLM retry budget: total wall time across attempts (including backoff).
    node_retry_deadline_ms: int = Field(default=120000, alias="AEGIS_ALPHA_NODE_RETRY_DEADLINE_MS")
    node_retry_max_attempts: int = Field(default=3, alias="AEGIS_ALPHA_NODE_RETRY_MAX_ATTEMPTS")

    # Backend
    backend_url: str = Field(default="http://127.0.0.1:5178", alias="AEGIS_ALPHA_BACKEND_URL")
    node_callback_base_url: str = Field(default="", alias="AEGIS_ALPHA_NODE_CALLBACK_BASE_URL")
    # Shared service token (Java <-> orchestrator). No default: set it explicitly. The documented
    # example value is only accepted in explicit dev/mock mode.
    node_execution_token: str = Field(default="", alias="AEGIS_ALPHA_NODE_EXECUTION_TOKEN")
    dev_mode: bool = Field(default=False, alias="AEGIS_ALPHA_DEV_MODE")
    # Decision D6: deployment marker; "prod"/"production" (or SPRING_PROFILES_ACTIVE containing
    # prod/production) makes startup fail with DEV_MODE or the sample service token.
    deployment_env: str = Field(default="", alias="AEGIS_ALPHA_ENV")
    # Comma-separated CORS allow-list (no wildcard). Defaults to local web/API origins only.
    cors_allowed_origins: str = Field(
        default="http://127.0.0.1:5174,http://localhost:5174,http://127.0.0.1:5178,http://localhost:5178",
        alias="AEGIS_ALPHA_CORS_ALLOWED_ORIGINS",
    )
    # Optional run-scoped portfolio:read delegation token (tests / explicit inject).
    # Prefer contextvar authorization_override on ToolBackendClient for per-run use.
    delegated_token: str = Field(default="", alias="AEGIS_ALPHA_DELEGATED_TOKEN")

    # P4-1 tool pipeline: per-call result caps (list items first, then characters).
    tool_result_max_chars: int = Field(default=6000, alias="AEGIS_ALPHA_TOOL_RESULT_MAX_CHARS")
    tool_result_max_items: int = Field(default=20, alias="AEGIS_ALPHA_TOOL_RESULT_MAX_ITEMS")

    # P4-8 agent-loop context transform (待确认): verbatim recent tool results and the per-call budget
    # (capped at 60% of the registry contextWindow when one is configured).
    agent_keep_recent_tool_results: int = Field(default=4, alias="AEGIS_ALPHA_AGENT_KEEP_RECENT_TOOL_RESULTS")
    agent_max_context_tokens: int = Field(default=12000, alias="AEGIS_ALPHA_AGENT_MAX_CONTEXT_TOKENS")

    # Market Data
    market_data_timeout_ms: int = Field(default=8000, alias="AEGIS_ALPHA_MARKET_DATA_TIMEOUT_MS")

    # Store / Memory
    data_dir: str = Field(default="data", alias="AEGIS_ALPHA_DATA_DIR")
    store_default_ttl_seconds: int = Field(default=3600, alias="AEGIS_ALPHA_STORE_TTL_SECONDS")

    # Graph execution guardrails (P2-12)
    workflow_recursion_limit: int = Field(default=100, alias="AEGIS_ALPHA_WORKFLOW_RECURSION_LIMIT")
    workflow_timeout_seconds: float = Field(default=900.0, alias="AEGIS_ALPHA_WORKFLOW_TIMEOUT_SECONDS")
    workflow_thread_meta_max: int = Field(default=1000, alias="AEGIS_ALPHA_WORKFLOW_THREAD_META_MAX")
    # Decision D5: delete checkpoints of runs in a terminal state (completed incl. approved/rejected,
    # cancelled, failed = error/timeout). Never for paused (HUMAN_INTERRUPT) or running runs, nor for a
    # failed/cancelled resume (Java returns it to HUMAN_INTERRUPT for a retry). Debugging: set false.
    workflow_cleanup_checkpoints: bool = Field(default=True, alias="AEGIS_ALPHA_WORKFLOW_CLEANUP_CHECKPOINTS")
    workflow_cleanup_outcomes: str = Field(
        default="completed,cancelled,failed", alias="AEGIS_ALPHA_WORKFLOW_CLEANUP_OUTCOMES"
    )
    # Agent-loop message queue (steering / follow-up, modeled on pi-mono's dual loop).
    # Defaults are 待确认 (pending product confirmation); see 消息排队实现说明.md.
    # P4-16: backend selection (docs/多副本设计.md). Only sqlite/memory and memory are installed;
    # postgres/redis are recognised but fail at startup: decision D9 adds no new infra dependencies.
    checkpointer_backend: str = Field(default="sqlite", alias="AEGIS_ALPHA_CHECKPOINTER_BACKEND")
    run_queue_backend: str = Field(default="memory", alias="AEGIS_ALPHA_RUN_QUEUE_BACKEND")
    # Declared replica count. D9: >1 with process-local backends refuses to start unless the explicit
    # override below is set (operator guarantees run-affine routing).
    replicas: int = Field(default=1, alias="AEGIS_ALPHA_REPLICAS")
    allow_local_backends_multi_replica: bool = Field(
        default=False, alias="AEGIS_ALPHA_ALLOW_LOCAL_BACKENDS_MULTI_REPLICA"
    )
    message_queue_enabled: bool = Field(default=True, alias="AEGIS_ALPHA_MESSAGE_QUEUE_ENABLED")
    message_queue_steering_mode: str = Field(
        default="one-at-a-time", alias="AEGIS_ALPHA_MESSAGE_QUEUE_STEERING_MODE"
    )
    message_queue_follow_up_mode: str = Field(
        default="one-at-a-time", alias="AEGIS_ALPHA_MESSAGE_QUEUE_FOLLOW_UP_MODE"
    )
    message_queue_max_pending: int = Field(default=8, alias="AEGIS_ALPHA_MESSAGE_QUEUE_MAX_PENDING")
    message_queue_max_chars: int = Field(default=4000, alias="AEGIS_ALPHA_MESSAGE_QUEUE_MAX_CHARS")
    message_queue_max_deliveries: int = Field(
        default=16, alias="AEGIS_ALPHA_MESSAGE_QUEUE_MAX_DELIVERIES"
    )
    # P4-11: default off (pi HEAD removed skipping; tool calls of one message run concurrently and
    # steering is delivered after the batch). 待确认 with product.
    message_queue_skip_remaining_tools: bool = Field(
        default=False, alias="AEGIS_ALPHA_MESSAGE_QUEUE_SKIP_REMAINING_TOOLS"
    )
    message_queue_max_threads: int = Field(default=1000, alias="AEGIS_ALPHA_MESSAGE_QUEUE_MAX_THREADS")

    # OpenAI fallback
    openai_api_key: str = Field(default="", alias="OPENAI_API_KEY")
    openai_base_url: str = Field(default="", alias="OPENAI_BASE_URL")

    model_config = SettingsConfigDict(populate_by_name=True, extra="ignore")

    @property
    def effective_api_key(self) -> str:
        """Get the effective API key with fallback."""
        return self.api_key or self.openai_api_key

    @property
    def effective_base_url(self) -> str:
        """Get the effective base URL with fallback."""
        return self.base_url or self.openai_base_url

    @property
    def effective_backend_url(self) -> str:
        """Get the effective backend URL."""
        return self.node_callback_base_url or self.backend_url

    @property
    def is_production(self) -> bool:
        """D6: production marker from AEGIS_ALPHA_ENV or SPRING_PROFILES_ACTIVE (shared deploy env)."""
        markers = {"prod", "production"}
        if (self.deployment_env or "").strip().lower() in markers:
            return True
        profiles = os.environ.get("SPRING_PROFILES_ACTIVE", "")
        return any(p.strip().lower() in markers for p in profiles.split(","))

    @property
    def production_safety_problems(self) -> list[str]:
        """D6: reasons that must stop a production start (empty outside production)."""
        if not self.is_production:
            return []
        problems = []
        if self.dev_mode:
            problems.append("dev_mode_in_production")
        if (self.node_execution_token or "").strip() == EXAMPLE_SERVICE_TOKEN:
            problems.append("example_service_token_in_production")
        return problems

    @property
    def allows_example_service_token(self) -> bool:
        """The example token is only tolerated when dev or mock mode is set explicitly, never in production."""
        return bool(self.dev_mode or self.mock_mode) and not self.is_production

    @property
    def service_token_problem(self) -> str | None:
        """``None`` when the service token is usable, else a short machine-readable reason."""
        token = (self.node_execution_token or "").strip()
        if not token:
            return "service_token_not_configured"
        if token == EXAMPLE_SERVICE_TOKEN and not self.allows_example_service_token:
            return "service_token_is_example_value"
        return None

    @property
    def cors_origins(self) -> list[str]:
        return [o.strip() for o in (self.cors_allowed_origins or "").split(",") if o.strip() and o.strip() != "*"]

    @property
    def is_mock_mode(self) -> bool:
        """Check if mock mode is enabled."""
        return self.mock_mode or not self.effective_api_key


# Singleton instance
settings = Settings()
