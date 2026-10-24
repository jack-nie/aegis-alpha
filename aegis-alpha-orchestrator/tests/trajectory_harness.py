"""P4-13: agent trajectory evaluation harness.

A case (``tests/trajectories/*.yaml``) describes:

- ``nodes``: agent nodes run in sequence (``id``, ``handler``, ``prompt``, ``maxToolRounds``).
- ``tools``: stubs ``name -> {args: {field: type}, returns: str|dict, raises: str, size: int,
  sleep: float}``.
- ``script``: one entry per chat-model call, in order: ``content``, ``tool_calls`` (``name``,
  ``args``, optional ``id``), ``finish_reason``, ``usage`` (``input``/``output``), and runtime
  ``actions`` performed while that call is in flight (``steer``/``follow_up``: text, ``cancel``).
- ``expect``: ``status`` (last event), ``tool_sequence``, ``tool_errors``, ``truncated``,
  ``llm_calls`` / ``max_llm_calls``, ``tools_not_executed``, ``request_contains`` /
  ``request_excludes`` (``{call: n, text}``; 1-based model call), ``request_fence_balanced``, ``summary_contains``,
  ``usage_total_max`` / ``usage_total``, ``queue_delivered``, ``events_include`` / ``events_exclude``.

The real ``LLMClient`` / ``NodeExecutor`` / ``WorkflowEngine`` / tool pipeline run unchanged; only
``LLMClient._build_client`` is replaced by a scripted fake chat model (no network, no key use).
"""
from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock

import yaml
from langchain_core.messages import AIMessage
from langchain_core.tools import StructuredTool
from pydantic import create_model

from app.config import Settings
from app.core.llm_client import LLMClient
from app.core.market_data import MarketDataService
from app.core.message_queue import MessageQueueRegistry, MessageQueueSettings, QueueOwner
from app.core.node_executor import NodeExecutor
from app.core.run_registry import RunRegistry
from app.core.workflow_engine import WorkflowEngine
from app.models.workflow import Edge, Node, NodeData

CASES_DIR = Path(__file__).parent / "trajectories"
OWNER = {"userId": "traj-user", "tenantId": "traj-tenant"}
_TYPES = {"str": str, "int": int, "float": float, "bool": bool}


def load_cases() -> list[dict[str, Any]]:
    cases = []
    for path in sorted(CASES_DIR.glob("*.yaml")):
        case = yaml.safe_load(path.read_text(encoding="utf-8"))
        case.setdefault("name", path.stem)
        cases.append(case)
    return cases


@dataclass
class TrajectoryResult:
    events: list[Any]
    requests: list[list[Any]] = field(default_factory=list)
    executed: list[str] = field(default_factory=list)

    @property
    def names(self) -> list[str]:
        return [e.event for e in self.events]

    def of(self, name: str) -> list[dict]:
        return [e.data for e in self.events if e.event == name]


def _make_tool(name: str, spec: dict[str, Any], executed: list[str]) -> StructuredTool:
    fields = {k: (_TYPES.get(str(v), str), ...) for k, v in (spec.get("args") or {}).items()}
    schema = create_model(f"{name}_args", **fields)

    async def _run(**kwargs: Any) -> str:
        executed.append(name)
        if spec.get("sleep"):
            await asyncio.sleep(float(spec["sleep"]))
        if spec.get("raises"):
            raise RuntimeError(str(spec["raises"]))
        if spec.get("size"):
            return "x" * int(spec["size"])
        value = spec.get("returns", "ok")
        return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)

    return StructuredTool.from_function(coroutine=_run, name=name, description=f"stub {name}",
                                        args_schema=schema)


class ScriptedChatModel:
    def __init__(self, script: list[dict], on_actions):
        self._script = list(script)
        self._on_actions = on_actions
        self.requests: list[list[Any]] = []

    def bind_tools(self, tools, **_):
        return self

    async def ainvoke(self, chat):
        self.requests.append(list(chat))
        index = len(self.requests)
        if not self._script:
            raise AssertionError(f"model called {index} times; script has fewer turns")
        turn = self._script.pop(0)
        if turn.get("actions"):
            self._on_actions(turn["actions"])
        calls = [
            {"name": c["name"], "args": c.get("args") or {}, "id": c.get("id") or f"call_{index}_{i}"}
            for i, c in enumerate(turn.get("tool_calls") or [])
        ]
        kwargs: dict[str, Any] = {"content": turn.get("content", ""), "tool_calls": calls}
        if turn.get("finish_reason"):
            kwargs["response_metadata"] = {"finish_reason": turn["finish_reason"]}
        usage = turn.get("usage")
        if usage:
            i, o = int(usage.get("input", 0)), int(usage.get("output", 0))
            kwargs["usage_metadata"] = {"input_tokens": i, "output_tokens": o, "total_tokens": i + o}
        return AIMessage(**kwargs)


async def run_case(case: dict[str, Any]) -> TrajectoryResult:
    thread_id = f"traj-{case['name']}"
    settings = Settings(
        AEGIS_ALPHA_LANGCHAIN_API_KEY="trajectory-fake-key",
        AEGIS_ALPHA_LANGCHAIN_MOCK=True,
        AEGIS_ALPHA_DATA_DIR="/tmp/aegis-test-data",
    )
    queues = MessageQueueRegistry(MessageQueueSettings(**(case.get("queue") or {})))
    runs = RunRegistry()

    def on_actions(actions: dict[str, Any]) -> None:
        for kind in ("steer", "follow_up"):
            if actions.get(kind):
                queues.enqueue(thread_id, kind, str(actions[kind]), QueueOwner.from_any(OWNER))
        if actions.get("cancel"):
            runs.cancel(thread_id, OWNER)

    fake = ScriptedChatModel(case.get("script") or [], on_actions)
    llm = LLMClient(settings)
    llm._build_client = lambda *a, **k: fake  # type: ignore[method-assign]
    market = MarketDataService(settings)
    market.hydrate = AsyncMock(return_value=None)
    executor = NodeExecutor(settings, llm, market)
    executor._sleep = AsyncMock()  # no real retry backoff

    executed: list[str] = []
    tools = [_make_tool(n, s or {}, executed) for n, s in (case.get("tools") or {}).items()]
    nodes = []
    for spec in case.get("nodes") or [{"id": "agent"}]:
        data = {"handler": spec.get("handler", "general.agent"), "prompt": spec.get("prompt", "analyse {subject}")}
        if spec.get("maxToolRounds") is not None:
            data["maxToolRounds"] = spec["maxToolRounds"]
        nodes.append(Node(id=spec["id"], data=NodeData(**data)))
    edges = [Edge(source=a.id, target=b.id) for a, b in zip(nodes, nodes[1:])]

    WorkflowEngine._graph_cache.clear()
    engine = WorkflowEngine(executor, tools=tools, message_queues=queues, run_registry=runs)
    events = [
        e async for e in engine.stream_workflow(
            nodes, edges, dict(case.get("state") or {}), str(case.get("subject", "AAPL")),
            thread_id=thread_id, owner=OWNER,
        )
    ]
    return TrajectoryResult(events=events, requests=fake.requests, executed=executed)


def _text(message: Any) -> str:
    content = getattr(message, "content", "")
    return content if isinstance(content, str) else json.dumps(content, ensure_ascii=False)


def _final_state(result: TrajectoryResult) -> dict:
    complete = result.of("workflow_complete")
    return (complete[-1].get("final_state") or {}) if complete else {}


def check_expectations(case: dict[str, Any], result: TrajectoryResult) -> list[str]:
    """Return human-readable failures (empty list = pass)."""
    exp = case.get("expect") or {}
    failures: list[str] = []
    ends = result.of("tool_execution_end")

    def fail(msg: str) -> None:
        failures.append(msg)

    if "status" in exp and result.names[-1:] != [exp["status"]]:
        fail(f"status: expected {exp['status']}, got {result.names[-1:]}")
    if "tool_sequence" in exp:
        got = [e["toolName"] for e in ends]
        want = list(exp["tool_sequence"])
        mismatch = sorted(got) != sorted(want) if exp.get("tool_order_free") else got != want
        if mismatch:
            fail(f"tool_sequence: expected {exp['tool_sequence']}, got {got}")
    if "tool_errors" in exp:
        got = [e["toolName"] for e in ends if e.get("isError")]
        if got != exp["tool_errors"]:
            fail(f"tool_errors: expected {exp['tool_errors']}, got {got}")
    if "truncated" in exp:
        got = [e["toolName"] for e in ends if e.get("truncated")]
        if got != exp["truncated"]:
            fail(f"truncated: expected {exp['truncated']}, got {got}")
    if "tools_not_executed" in exp:
        ran = set(result.executed) & set(exp["tools_not_executed"])
        if ran:
            fail(f"tools_not_executed: {sorted(ran)} ran")
    if "llm_calls" in exp and len(result.requests) != exp["llm_calls"]:
        fail(f"llm_calls: expected {exp['llm_calls']}, got {len(result.requests)}")
    if "max_llm_calls" in exp and len(result.requests) > exp["max_llm_calls"]:
        fail(f"max_llm_calls: {len(result.requests)} > {exp['max_llm_calls']}")
    for item in exp.get("request_contains") or []:
        idx = int(item["call"]) - 1
        if idx >= len(result.requests):
            fail(f"request_contains: no model call #{item['call']}")
            continue
        blob = "\n".join(_text(m) for m in result.requests[idx])
        if item["text"] not in blob:
            fail(f"request_contains: call #{item['call']} lacks {item['text']!r}")
    for item in exp.get("request_excludes") or []:
        idx = int(item["call"]) - 1
        if idx < len(result.requests):
            blob = "\n".join(_text(m) for m in result.requests[idx])
            if item["text"] in blob:
                fail(f"request_excludes: call #{item['call']} contains {item['text']!r}")
    for tag in exp.get("request_fence_balanced") or []:
        for n, request in enumerate(result.requests, 1):
            # the system prompt's security notice names the tags, so only non-system turns count
            blob = "\n".join(_text(m) for m in request if getattr(m, "type", "") != "system")
            if blob.count(f"<{tag}>") != blob.count(f"</{tag}>"):
                fail(f"fence <{tag}> unbalanced in call #{n}")
    nodes = _final_state(result).get("nodes") or {}
    for node_id, text in (exp.get("summary_contains") or {}).items():
        summary = str((nodes.get(node_id) or {}).get("summary") or "")
        if text not in summary:
            fail(f"summary_contains: {node_id} summary {summary[:120]!r} lacks {text!r}")
    totals = _final_state(result).get("usageTotals") or {}
    total = int(totals.get("totalTokens") or 0)
    if "usage_total" in exp and total != exp["usage_total"]:
        fail(f"usage_total: expected {exp['usage_total']}, got {total} ({totals})")
    if "usage_total_max" in exp and total > exp["usage_total_max"]:
        fail(f"usage_total_max: {total} > {exp['usage_total_max']}")
    if "queue_delivered" in exp:
        closed = result.of("message_queue_closed")
        got = closed[-1].get("delivered") if closed else None
        if got != exp["queue_delivered"]:
            fail(f"queue_delivered: expected {exp['queue_delivered']}, got {got}")
    for name in exp.get("events_include") or []:
        if name not in result.names:
            fail(f"events_include: {name} missing from {result.names}")
    for name in exp.get("events_exclude") or []:
        if name in result.names:
            fail(f"events_exclude: {name} present")
    return failures
