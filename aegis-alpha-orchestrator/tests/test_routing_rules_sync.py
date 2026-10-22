"""P2-3: orchestrator keyword fallback uses the same rule data as Java RoutingRules."""
import json
from pathlib import Path

RULES = (
    Path(__file__).resolve().parents[2]
    / "aegis-alpha-api/src/main/resources/routing/routing-rules.json"
)


def test_stopwords_and_regex_rules_match_java_resource():
    from app.core.intent_classifier import REGEX_RULES, TICKER_STOPWORDS

    data = json.loads(RULES.read_text(encoding="utf-8"))
    assert set(TICKER_STOPWORDS) == {w.upper() for w in data["tickerStopwords"]}
    assert [(k, p, t) for k, p, t in REGEX_RULES] == [
        (r["workflowKey"], r["pattern"], r.get("extractTicker", True)) for r in data["regexRules"]
    ]


def test_fallback_skips_stopword_tickers_and_applies_regex_rules():
    from unittest.mock import MagicMock

    from app.core.intent_classifier import IntentClassifier, extract_ticker

    assert extract_ticker("AI ETF 和 NVDA 的财报") == "NVDA"
    classifier = IntentClassifier(MagicMock())
    workflows = [{"workflowKey": "earnings_reaction"}, {"workflowKey": "sector-analyst-workflow"}]
    result = classifier._keyword_fallback("AI 带动 NVDA 财报解读", workflows)
    assert result.workflow_key == "earnings_reaction"
    assert result.ticker == "NVDA"
    sector = classifier._keyword_fallback("半导体 SOXX 行业分析", workflows)
    assert sector.workflow_key == "sector-analyst-workflow"
    assert sector.ticker is None
