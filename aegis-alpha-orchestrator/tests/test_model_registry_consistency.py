"""Decision D4: the Java allow-list default equals the orchestrator model registry (single source)."""
import re
from pathlib import Path

import pytest

from app.core.model_registry import get_model_registry

APP_YML = Path(__file__).resolve().parents[2] / "aegis-alpha-api" / "src" / "main" / "resources" / "application.yml"


def test_java_allowed_names_default_matches_registry():
    if not APP_YML.exists():
        pytest.skip("api checkout not present")
    match = re.search(r"\$\{AEGIS_ALPHA_ALLOWED_MODEL_NAMES:([^}]*)\}", APP_YML.read_text(encoding="utf-8"))
    assert match, "aegis.models.allowed-names default not found"
    java_names = {part.strip() for part in match.group(1).split(",") if part.strip()}
    assert java_names == set(get_model_registry().names())
