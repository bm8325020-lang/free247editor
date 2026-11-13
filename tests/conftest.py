import os
import sys
from pathlib import Path

os.environ.setdefault("OPENCV_LOG_LEVEL", "ERROR")
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
# Shared with the Kotlin parity tests: both engines must agree on the same inputs.
FIXTURES = ROOT / "android" / "core" / "src" / "test" / "resources"
