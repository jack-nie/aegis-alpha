"""Tests for bounded help loop (retrieve/grade/max 3/escalate + ACL pre-filter)."""

from __future__ import annotations

from app.core.help_loop import (
    DEFAULT_CORPUS,
    MAX_RETRIEVAL_ROUNDS,
    grade_chunks,
    retrieve,
    run_help_loop,
)


def test_grade_no_chunks():
    g = grade_chunks("如何审批", [])
    assert g.ok is False
    assert g.reason == "no_chunks"


def test_grade_pass_with_overlap():
    chunks = [{"title": "审批", "excerpt": "如何审批 PENDING_REVIEW 证据 人工"}]
    g = grade_chunks("如何审批 证据", chunks)
    assert g.ok is True


def test_acl_prefilter_excludes_internal_for_anon():
    hits, applied = retrieve("运维 orchestrator 内部", DEFAULT_CORPUS, roles=[], limit=10)
    assert applied.get("preFiltered") is True
    assert all(h.get("id") != "internal-ops" for h in hits)
    assert "principalRoles" in applied


def test_acl_prefilter_includes_internal_for_desk():
    hits, _ = retrieve("运维 orchestrator 内部", DEFAULT_CORPUS, roles=["desk"], limit=10)
    assert any(h.get("id") == "internal-ops" for h in hits)


def test_loop_success_on_public_query():
    result = run_help_loop("如何运行 published 工作流 操作步骤", DEFAULT_CORPUS, roles=[])
    assert result.ok is True
    assert result.escalate is False
    assert 1 <= result.retrieval_rounds <= MAX_RETRIEVAL_ROUNDS
    resp = result.to_response()
    assert "content" in resp
    assert resp["helpLoop"]["preFiltered"] is True
    assert resp["helpLoop"]["maxRounds"] == MAX_RETRIEVAL_ROUNDS


def test_loop_escalates_after_max_rounds_on_empty_corpus():
    result = run_help_loop("xyzzy-no-document-match-999", corpus=[], roles=[])
    assert result.ok is False
    assert result.escalate is True
    assert result.retrieval_rounds == MAX_RETRIEVAL_ROUNDS
    assert result.disclaimer is not None
    resp = result.to_response()
    assert resp["escalateHuman"] is True


def test_loop_never_exceeds_three_rounds():
    # Sparse corpus that fails grade → must stop at 3
    sparse = [
        {
            "id": "noise",
            "title": "zz",
            "visibility": "public",
            "keywords": ["zz"],
            "body": "zz",
        }
    ]
    result = run_help_loop("完全不相关的问题 abcdefghijk", sparse, roles=[], max_rounds=99)
    assert result.retrieval_rounds <= MAX_RETRIEVAL_ROUNDS
