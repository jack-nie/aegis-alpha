"""Local runner for Phase 0.5 smoke tests.

  .venv\\Scripts\\python.exe tests/_run_phase05_smoke.py
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
os.environ.setdefault("AEGIS_ALPHA_NODE_EXECUTION_TOKEN", "local-workflow-node-token")

cmd = [
    sys.executable,
    "-m",
    "pytest",
    "tests/test_service_auth.py",
    "tests/test_contract_intent_response.py",
    "-q",
    "--tb=short",
]
print("Running:", " ".join(cmd))
raise SystemExit(subprocess.call(cmd))
