"""P3-7: no class-based pydantic Config left (PydanticDeprecatedSince20)."""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_importing_app_emits_no_pydantic_v1_config_deprecation():
    code = (
        "import warnings\n"
        "from pydantic.warnings import PydanticDeprecatedSince20\n"
        "warnings.simplefilter('error', PydanticDeprecatedSince20)\n"
        "import app.main, app.config, app.models.workflow, app.models.requests, app.routers.help, app.routers.workflow\n"
    )
    result = subprocess.run([sys.executable, "-c", code], cwd=ROOT, capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, result.stderr[-2000:]


def test_no_class_config_in_app_sources():
    offenders = [
        str(path.relative_to(ROOT))
        for path in (ROOT / "app").rglob("*.py")
        if "    class Config:" in path.read_text(encoding="utf-8")
    ]
    assert offenders == []
