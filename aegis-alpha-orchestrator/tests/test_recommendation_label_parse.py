"""P3-4: recommendation label parsing uses word boundaries and structured fields first."""

import pytest

from app.core.node_executor import NodeExecutor

parse = NodeExecutor._extract_recommendation_label


@pytest.mark.parametrize(
    "text",
    [
        "Focus on SHAREHOLDER value and capital returns.",
        "The BUYBACK program continues; WATCHLIST names unchanged.",
        "Management will hold the guidance; investors should buy time.",
        "",
    ],
)
def test_substrings_and_prose_are_not_labels(text):
    assert parse(text) is None


@pytest.mark.parametrize(
    "text,expected",
    [
        ('```json\n{"recommendation": "sell", "confidence": 0.6}\n```', "SELL"),
        ("Recommendation: hold — valuation is full.", "HOLD"),
        ("评级：BUY，理由如下", "BUY"),
        ("Overall STRONG BUY on margin expansion.", "BUY"),
        ("Signals point to BUY but data is INSUFFICIENT_DATA for a call.", "INSUFFICIENT_DATA"),
        ("SHAREHOLDER returns are strong. Final view: WATCH.", "WATCH"),
    ],
)
def test_labels_are_parsed(text, expected):
    assert parse(text) == expected


def test_json_field_wins_over_prose():
    text = 'We like the SELL-side coverage. {"recommendation": "HOLD"}'
    assert parse(text) == "HOLD"
