import sys
from pathlib import Path

PYTHON_DIR = Path(__file__).resolve().parents[2] / "bank-agent-demo" / "python"
if str(PYTHON_DIR) not in sys.path:
    sys.path.insert(0, str(PYTHON_DIR))

