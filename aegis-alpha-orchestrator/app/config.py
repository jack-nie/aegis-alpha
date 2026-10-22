"""Application configuration management."""

import os
from pathlib import Path

from pydantic_settings import BaseSettings
from pydantic import Field
from dotenv import load_dotenv

# Load .env from project root
_env_path = Path(__file__).parent.parent.parent / ".env"
load_dotenv(_env_path, override=True)


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
    # Comma-separated CORS allow-list (no wildcard). Defaults to local web/API origins only.
    cors_allowed_origins: str = Field(
        default="http://127.0.0.1:5174,http://localhost:5174,http://127.0.0.1:5178,http://localhost:5178",
        alias="AEGIS_ALPHA_CORS_ALLOWED_ORIGINS",
    )
    # Optional run-scoped portfolio:read delegation token (tests / explicit inject).
    # Prefer contextvar authorization_override on ToolBackendClient for per-run use.
    delegated_token: str = Field(default="", alias="AEGIS_ALPHA_DELEGATED_TOKEN")

    # Market Data
    market_data_timeout_ms: int = Field(default=8000, alias="AEGIS_ALPHA_MARKET_DATA_TIMEOUT_MS")

    # Store / Memory
    data_dir: str = Field(default="data", alias="AEGIS_ALPHA_DATA_DIR")
    store_default_ttl_seconds: int = Field(default=3600, alias="AEGIS_ALPHA_STORE_TTL_SECONDS")

    # OpenAI fallback
    openai_api_key: str = Field(default="", alias="OPENAI_API_KEY")
    openai_base_url: str = Field(default="", alias="OPENAI_BASE_URL")

    class Config:
        populate_by_name = True
        extra = "ignore"

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
    def allows_example_service_token(self) -> bool:
        """The example token is only tolerated when dev or mock mode is set explicitly."""
        return bool(self.dev_mode or self.mock_mode)

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
