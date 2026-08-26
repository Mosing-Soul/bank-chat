"""Make the sibling production modules importable for lab commands."""
import sys
from pathlib import Path

PYTHON_DIR = Path(__file__).resolve().parent.parent / "bank-agent-demo" / "python"
if str(PYTHON_DIR) not in sys.path:
    sys.path.insert(0, str(PYTHON_DIR))
