"""P4-14: sectioned system prompts, stable prefix, prompt_version in the trace."""
from pathlib import Path

import pytest

from app.core.prompt_builder import (
    SECTION_ORDER,
    PromptBuilder,
    agent_tools_prompt,
    aggregate_prompt,
    analyst_prompt,
    digest_prompt,
)
from app.core.prompt_safety import UNTRUSTED_NOTICE, with_untrusted_notice
from trajectory_harness import run_case_sync

SNAPSHOTS = Path(__file__).parent / "snapshots" / "prompts"
BUILDERS = {
    "analyst": lambda n: analyst_prompt(n),
    "agent_tools": lambda n: agent_tools_prompt(n),
    "agent_tools.final": lambda n: agent_tools_prompt(n, force_final=True),
    "aggregate": lambda n: aggregate_prompt(n),
    "digest": lambda n: digest_prompt(n),
}


@pytest.mark.parametrize("name", sorted(BUILDERS))
def test_prompt_snapshot(name):
    """Changing a prompt is deliberate: update tests/snapshots/prompts/<name>.txt in the same commit."""
    built = BUILDERS[name]("{agent_name}")
    expected = (SNAPSHOTS / f"{name}.txt").read_text(encoding="utf-8")
    assert f"# version: {built.version}\n{built.text}\n" == expected


@pytest.mark.parametrize("name", sorted(BUILDERS))
def test_sections_in_fixed_order_and_role_last(name):
    built = BUILDERS[name]("Agent X")
    positions = [built.text.index(f"<{s}>") for s in built.sections]
    assert positions == sorted(positions)
    assert list(built.sections) == [s for s in SECTION_ORDER if s in built.sections]
    assert built.sections[0] == "security" and built.sections[-1] == "role"


def test_stable_prefix_and_version_independent_of_agent_name():
    a, b = analyst_prompt("Market Analyst"), analyst_prompt("News Analyst")
    assert a.version == b.version
    prefix = a.text[: a.text.index("<role>")]
    assert b.text.startswith(prefix) and len(prefix) > 200


def test_version_changes_when_a_rule_changes():
    base = PromptBuilder("t", role="r", rules=("one",)).build()
    assert PromptBuilder("t", role="other", rules=("one",)).build().version == base.version
    assert PromptBuilder("t", role="r", rules=("two",)).build().version != base.version


def test_security_notice_not_duplicated_by_llm_client():
    text = aggregate_prompt("A").text
    assert with_untrusted_notice(text).count(UNTRUSTED_NOTICE) == 1


def test_contracts_unchanged_for_existing_callers():
    from app.core.node_executor import aggregate_system_prompt, digest_system_prompt

    assert "```json" in aggregate_system_prompt("A")
    assert "Do not output a BUY/SELL/HOLD rating" in digest_system_prompt("A")
    assert "tool-call limit" in agent_tools_prompt("A", force_final=True).text
    assert "tool-call limit" not in agent_tools_prompt("A").text


@pytest.mark.asyncio
async def test_prompt_version_and_cache_reads_in_trace():
    case = {
        "name": "prompt_version_trace",
        "tools": {"get_quote": {"args": {"symbol": "str"}, "returns": {"price": 1}}},
        "script": [
            {"tool_calls": [{"name": "get_quote", "args": {"symbol": "AAPL"}}],
             "usage": {"input": 100, "output": 10, "cache_read": 80}},
            {"content": '{"summary": "done", "confidence": 0.5}',
             "usage": {"input": 120, "output": 20, "cache_read": 100}},
        ],
    }
    result, requests = await run_case_sync(case)
    entries = [t.model_dump(by_alias=True) for t in result.trace]
    versions = {e.get("promptVersion") for e in entries if e.get("nodeId") == "agent"}
    assert agent_tools_prompt("x").version in versions
    # both turns share the cached system prefix; the provider-reported cache reads are surfaced
    assert requests[0][0].content == requests[1][0].content
    totals = result.final_state.get("usageTotals") or {}
    assert totals.get("cacheReadTokens") == 180, totals
