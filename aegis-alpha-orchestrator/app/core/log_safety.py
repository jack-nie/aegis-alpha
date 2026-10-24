"""P3-5: keep user-provided text (subject, model output) out of INFO+ logs."""

from __future__ import annotations

from typing import Any


def text_len(value: Any) -> int:
    """Length of a user/model text for INFO logs; full text belongs at DEBUG only."""
    if value is None:
        return 0
    return len(value) if isinstance(value, str) else len(str(value))
