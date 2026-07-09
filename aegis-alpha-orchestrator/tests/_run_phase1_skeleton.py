"""Local runner for Phase 1 skeleton tests.

  .venv\\Scripts\\python.exe tests/_run_phase1_skeleton.py
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)
os.environ.setdefault("AEGIS_ALPHA_LANGCHAIN_MOCK", "true")
os.environ.setdefault("AEGIS_ALPHA_LANGCHAIN_API_KEY", "test-key")

cmd = [
    sys.executable,
    "-m",
    "pytest",
    "tests/test_critique.py",
    "tests/test_recommendation_policy.py",
    "tests/test_symbol_normalize.py",
    "tests/test_market_data.py",
    "-q",
    "--tb=short",
]
print("Running:", " ".join(cmd))
raise SystemExit(subprocess.call(cmd))
