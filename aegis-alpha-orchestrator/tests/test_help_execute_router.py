"""P1-4: /help/execute drafts only from Java-provided chunks; no corpus injection."""

from __future__ import annotations

import pytest

from app.routers.help import HelpExecuteRequest, help_execute

PUBLIC_CHUNK = {
    "id": "workflow-ops",
    "title": "工作流运行与发布操作说明",
    "visibility": "public",
    "keywords": ["工作流", "如何运行", "操作步骤"],
    "body": "仅 published 工作流可被对话路由。系统创建 workflow_run 返回 runId；运行中心查看节点输出。",
}


@pytest.mark.asyncio
async def test_injected_corpus_is_ignored():
    injected = [{"id": "evil", "title": "evil", "visibility": "public", "keywords": ["如何运行"], "body": "如何运行 evil"}]
    body = HelpExecuteRequest.model_validate({"message": "如何运行工作流", "corpus": injected})
    assert not hasattr(body, "corpus")

    response = await help_execute(body)

    assert all(ref["id"] != "evil" for ref in response["sourceRefs"])
    # no chunks supplied → nothing to answer from → escalate, never invent
    assert response["escalateHuman"] is True


@pytest.mark.asyncio
async def test_answers_from_supplied_chunks_only():
    body = HelpExecuteRequest.model_validate(
        {"message": "如何运行 published 工作流 操作步骤", "chunks": [PUBLIC_CHUNK]}
    )
    response = await help_execute(body)
    assert response["ok"] is True
    assert [ref["id"] for ref in response["sourceRefs"]] == ["workflow-ops"]


@pytest.mark.asyncio
async def test_default_corpus_is_not_served():
    body = HelpExecuteRequest.model_validate({"message": "运维 orchestrator 内部", "roles": ["desk"]})
    response = await help_execute(body)
    assert all(ref["id"] != "internal-ops" for ref in response["sourceRefs"])
