"""P3-5: user subjects and model summaries never reach INFO+ logs."""

import logging
import re
from pathlib import Path

import pytest

from app.models.workflow import Node, NodeData

APP = Path(__file__).resolve().parents[1] / "app"
LOG_CALL = re.compile(r"logger\.(info|warning|error|exception|critical)\((?P<args>.*)\)")


def test_no_info_log_formats_subject_or_summary():
    offenders = []
    for path in APP.rglob("*.py"):
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            match = LOG_CALL.search(line)
            if match and re.search(r"\{(subject|summary|response\.summary)[^}]*\}", match.group("args")):
                offenders.append(f"{path.name}:{lineno}")
    assert offenders == []


@pytest.mark.asyncio
async def test_node_execute_info_logs_only_lengths(caplog, mock_node_executor):
    executor = mock_node_executor
    node = Node(id="n1", type="agent", data=NodeData(handler="general.agent", label="Agent"))
    secret = "ACME secret merger target 2026"
    with caplog.at_level(logging.INFO, logger="app.core.node_executor"):
        await executor.execute(node=node, state={}, subject=secret)
    info_text = "\n".join(r.getMessage() for r in caplog.records if r.levelno >= logging.INFO)
    assert "subject_len=" in info_text
    assert secret not in info_text
