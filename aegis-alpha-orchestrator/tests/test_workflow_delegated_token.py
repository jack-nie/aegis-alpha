"""Delegated portfolio token injection for workflow runs."""

from __future__ import annotations

from app.config import Settings
from app.core.tools import ToolBackendClient, use_authorization
from app.models.requests import NodeRequest, WorkflowRequest
from app.routers.workflow import _authorization_context


def test_node_request_accepts_delegated_token_camel_case():
    req = NodeRequest.model_validate(
        {
            "node": {"id": "n1", "data": {"handler": "start"}},
            "delegatedToken": "node-run-token",
        }
    )
    assert req.delegated_token == "node-run-token"


def test_workflow_request_accepts_delegated_token_camel_case():
    req = WorkflowRequest.model_validate(
        {
            "subject": "AAPL research",
            "delegatedToken": "run-scoped-token",
            "nodes": [],
            "edges": [],
        }
    )
    assert req.delegated_token == "run-scoped-token"


def test_workflow_request_accepts_delegated_token_snake_case():
    req = WorkflowRequest.model_validate(
        {
            "subject": "AAPL research",
            "delegated_token": "snake-token",
        }
    )
    assert req.delegated_token == "snake-token"


def test_workflow_request_delegated_token_optional():
    req = WorkflowRequest.model_validate({"subject": "no token"})
    assert req.delegated_token is None


def test_authorization_context_sets_tool_header_and_clears():
    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_NODE_EXECUTION_TOKEN="node-tok",
        AEGIS_ALPHA_DELEGATED_TOKEN="",
    )
    client = ToolBackendClient(settings)
    assert client._authorization_header() == "Bearer node-tok"

    with _authorization_context("deleg-from-java"):
        assert client._authorization_header() == "Bearer deleg-from-java"

    assert client._authorization_header() == "Bearer node-tok"


def test_authorization_context_none_is_noop():
    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_NODE_EXECUTION_TOKEN="node-tok",
        AEGIS_ALPHA_DELEGATED_TOKEN="",
    )
    client = ToolBackendClient(settings)
    with _authorization_context(None):
        assert client._authorization_header() == "Bearer node-tok"
    with _authorization_context("  "):
        assert client._authorization_header() == "Bearer node-tok"


def test_use_authorization_matches_context_helper():
    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="test",
        AEGIS_ALPHA_NODE_EXECUTION_TOKEN="node-tok",
        AEGIS_ALPHA_DELEGATED_TOKEN="",
    )
    client = ToolBackendClient(settings)
    with use_authorization("Bearer sticky"):
        assert client._authorization_header() == "Bearer sticky"
