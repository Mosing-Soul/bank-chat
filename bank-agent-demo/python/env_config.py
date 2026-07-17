import os
from pathlib import Path

from dotenv import load_dotenv


PROJECT_DIR = Path(__file__).resolve().parent.parent
ENV_FILE = PROJECT_DIR / ".env"

# Always resolve the project-level .env, regardless of the process working directory.
load_dotenv(ENV_FILE)


def require_env(name: str) -> str:
    value = os.getenv(name)
    if value is None or not value.strip():
        raise RuntimeError(f"Required environment variable {name} is not configured in {ENV_FILE}")
    return value.strip()


def optional_env(name: str):
    value = os.getenv(name)
    return value.strip() if value and value.strip() else None


def env_path(name: str) -> Path:
    path = Path(require_env(name))
    return path if path.is_absolute() else PROJECT_DIR / path


def env_int(name: str) -> int:
    return int(require_env(name))


def env_float(name: str) -> float:
    return float(require_env(name))


def env_bool(name: str) -> bool:
    value = require_env(name).lower()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off"}:
        return False
    raise RuntimeError(f"Environment variable {name} must be a boolean value")
