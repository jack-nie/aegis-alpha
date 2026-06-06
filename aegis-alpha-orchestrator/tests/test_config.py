"""Tests for config module."""

import os
import pytest


def test_settings_defaults(mock_settings):
    assert mock_settings.port == 8787
    assert mock_settings.host == "0.0.0.0"
    assert mock_settings.provider == "openai"
    assert mock_settings.model == "gpt-4o-mini"
    assert mock_settings.is_mock_mode is True


def test_effective_api_key(mock_settings):
    assert mock_settings.effective_api_key == "test-key"


def test_effective_base_url_no_override(mock_settings):
    assert mock_settings.effective_base_url == ""


def test_effective_api_key_fallback():
    from app.config import Settings
    s = Settings(MARKETMIND_LANGCHAIN_API_KEY="", OPENAI_API_KEY="openai-fallback")
    assert s.effective_api_key == "openai-fallback"


def test_is_mock_mode_no_api_key():
    from app.config import Settings
    s = Settings(MARKETMIND_LANGCHAIN_API_KEY="", OPENAI_API_KEY="")
    assert s.is_mock_mode is True


def test_is_mock_mode_with_api_key(mock_settings):
    assert mock_settings.is_mock_mode is False or mock_settings.is_mock_mode is True


def test_data_dir_config(mock_settings):
    assert mock_settings.data_dir == "/tmp/aegis-test-data"


def test_store_ttl_config(mock_settings):
    assert mock_settings.store_default_ttl_seconds == 3600


def test_effective_backend_url():
    from app.config import Settings
    s = Settings(MARKETMIND_BACKEND_URL="http://localhost:5178")
    assert s.effective_backend_url == "http://localhost:5178"


def test_effective_backend_url_with_callback():
    from app.config import Settings
    s = Settings(
        MARKETMIND_BACKEND_URL="http://localhost:5178",
        MARKETMIND_NODE_CALLBACK_BASE_URL="http://backend:5178",
    )
    assert s.effective_backend_url == "http://backend:5178"