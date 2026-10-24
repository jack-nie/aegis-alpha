"""P4-14: system prompts built from named sections, with a version recorded in the trace.

pi builds system prompts from sections (``buildSystemPromptSections``). Here every system prompt
is assembled from ``security``, ``output_contract``, ``rules``, ``tools`` and ``role``, rendered in
that fixed order: the sections that never vary come first and the per-node ``role`` (agent name)
last, so providers with prefix caching (DeepSeek, OpenAI) can reuse the shared prefix across
nodes. Subject and context stay in the user message (``build_user_content``).

``prompt_version`` = ``<template>@<PROMPT_VERSION>+<hash of the static sections>``: editing any
rule or contract changes the hash even if ``PROMPT_VERSION`` is not bumped, so traces show which
prompt produced an answer. The role text (agent name) is excluded from the hash.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass, field

from .prompt_safety import UNTRUSTED_NOTICE

PROMPT_VERSION = "p4"
SECTION_ORDER = ("security", "output_contract", "rules", "tools", "role")

ANALYST_JSON_CONTRACT = (
    "Return only JSON. Do not use markdown. "
    'The JSON shape is {"summary":"string","signals":[...],"sources":[...],"confidence":0.5,"data":{}}.'
)
AGENT_JSON_CONTRACT = (
    "When you have enough evidence, return only JSON (no markdown) with shape "
    '{"summary":"string","signals":[...],"sources":[...],"confidence":0.5,"data":{}}.'
)
AGENT_TOOLS_GUIDE = "You may call tools when you need live market, financial, news, or portfolio data."
AGENT_FORCE_FINAL_RULE = (
    "You have reached the tool-call limit. Do not call tools. "
    "Answer now using the tool results already in the conversation."
)
AGGREGATE_RULES = (
    "Use markdown formatting with clear sections.",
    "Use only numbers present in marketDataContext / tool outputs.",
    "If critical market or financial data is missing, recommend INSUFFICIENT_DATA and list missing fields.",
    "Never invent prices, multiples, or target prices.",
)
AGGREGATE_CONTRACT = (
    "End the report with exactly one fenced ```json block of the shape "
    '{"recommendation":"BUY|HOLD|SELL|WATCH|INSUFFICIENT_DATA","confidence":0.0,'
    '"claims":[{"text":"...","evidenceId":"..."}],"missingData":["..."]} '
    "where every evidenceId is copied from the context."
)
DIGEST_RULES = (
    "Use only facts present in the context and state missing data explicitly.",
    "Do not output a BUY/SELL/HOLD rating or target price.",
)
DIGEST_CONTRACT = (
    "Return only JSON. Do not use markdown. "
    'The JSON shape is {"summary":"string","signals":[...],"sources":[...],"confidence":0.5,"data":{}}.'
)


@dataclass(frozen=True)
class BuiltPrompt:
    text: str
    version: str
    template: str
    sections: tuple[str, ...] = field(default_factory=tuple)


@dataclass
class PromptBuilder:
    template: str
    role: str = ""
    rules: tuple[str, ...] = ()
    tools: str = ""
    output_contract: str = ""
    security: str = UNTRUSTED_NOTICE

    def _section_text(self, name: str) -> str:
        if name == "rules":
            return "\n".join(f"- {r}" for r in self.rules if r)
        return str(getattr(self, name) or "").strip()

    def build(self) -> BuiltPrompt:
        parts: list[str] = []
        used: list[str] = []
        for name in SECTION_ORDER:
            text = self._section_text(name)
            if text:
                parts.append(f"<{name}>\n{text}\n</{name}>")
                used.append(name)
        static = "\x1f".join(
            [self.template] + [f"{n}={self._section_text(n)}" for n in SECTION_ORDER if n != "role"]
        )
        digest = hashlib.sha256(static.encode("utf-8")).hexdigest()[:8]
        return BuiltPrompt(
            text="\n\n".join(parts),
            version=f"{self.template}@{PROMPT_VERSION}+{digest}",
            template=self.template,
            sections=tuple(used),
        )


def analyst_prompt(agent_name: str) -> BuiltPrompt:
    return PromptBuilder("analyst", role=f"You are {agent_name}.", output_contract=ANALYST_JSON_CONTRACT).build()


def agent_tools_prompt(agent_name: str, *, force_final: bool = False) -> BuiltPrompt:
    return PromptBuilder(
        "agent_tools.final" if force_final else "agent_tools",
        role=f"You are {agent_name}.",
        tools=AGENT_TOOLS_GUIDE,
        rules=(AGENT_FORCE_FINAL_RULE,) if force_final else (),
        output_contract=AGENT_JSON_CONTRACT,
    ).build()


def aggregate_prompt(agent_name: str) -> BuiltPrompt:
    return PromptBuilder(
        "aggregate",
        role=f"You are {agent_name}. Generate an evidence-based investment research report.",
        rules=AGGREGATE_RULES,
        output_contract=AGGREGATE_CONTRACT,
    ).build()


def digest_prompt(agent_name: str) -> BuiltPrompt:
    return PromptBuilder(
        "digest",
        role=f"You are {agent_name}. Summarize the upstream workflow outputs into a digest.",
        rules=DIGEST_RULES,
        output_contract=DIGEST_CONTRACT,
    ).build()
