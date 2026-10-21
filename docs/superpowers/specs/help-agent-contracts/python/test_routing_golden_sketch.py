"""Design sketch: how golden20 route tests should look when ported.

Run only after copying routing.py + fixtures into a real test package.
This file is documentation — not part of orchestrator pytest discovery.
"""

from __future__ import annotations

import json
from pathlib import Path

# from routing import route, match_playbook_id
# from intent_models import PageContext

PLAYBOOK_CATALOG = {
    "degraded-meaning": ["degraded", "什么是 degraded", "降级"],
    "open-copilot": ["打开 copilot", "如何打开 ai", "打开助手"],
    "approval-inbox": ["如何审批", "审批在哪", "批准推荐", "pending_review"],
    "run-workflow": ["怎么运行", "如何运行工作流", "运行工作流"],
    "run-center": ["运行中心", "节点输出", "查看 run"],
    "published-workflow": ["published", "已发布工作流", "什么是 published"],
}


def _load_golden():
    path = Path(__file__).resolve().parents[1] / "fixtures" / "golden20.json"
    return json.loads(path.read_text(encoding="utf-8"))


def assert_route_case(case: dict) -> None:
    """Illustrative assertions — wire to real route() in P0 tests."""
    message = case["message"]
    # page = PageContext(**case.get("pageContext") or {})
    # playbook_id = match_playbook_id(message, PLAYBOOK_CATALOG)
    # # research_workflow_key would come from existing IntentRouterService in integration tests
    # result = route(message, page=page, playbook_id=playbook_id)
    expect = case["expect"]
    assert "domain" in expect or "domainAnyOf" in expect or "safety" in expect
    _ = message


def main() -> None:
    data = _load_golden()
    for case in data["cases"]:
        assert_route_case(case)
    print(f"loaded {len(data['cases'])} golden cases (sketch only)")


if __name__ == "__main__":
    main()
