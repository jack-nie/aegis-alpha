"""Application configuration management."""

import os
from pathlib import Path

from pydantic_settings import BaseSettings
from pydantic import Field
from dotenv import load_dotenv

# Load .env from project root
_env_path = Path(__file__).parent.parent.parent / ".env"
load_dotenv(_env_path, override=True)


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

    # Backend
    backend_url: str = Field(default="http://127.0.0.1:5178", alias="AEGIS_ALPHA_BACKEND_URL")
    node_callback_base_url: str = Field(default="", alias="AEGIS_ALPHA_NODE_CALLBACK_BASE_URL")
    node_execution_token: str = Field(
        default="local-workflow-node-token", alias="AEGIS_ALPHA_NODE_EXECUTION_TOKEN"
    )

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
    def is_mock_mode(self) -> bool:
        """Check if mock mode is enabled."""
        return self.mock_mode or not self.effective_api_key


# Singleton instance
settings = Settings()
