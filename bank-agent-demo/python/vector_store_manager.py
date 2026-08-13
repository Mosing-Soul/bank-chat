import json
import logging
import os
import threading
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Optional


logger = logging.getLogger(__name__)
POINTER_FILE = "current-index.json"
VERSIONS_DIR = "versions"


@dataclass(frozen=True)
class IndexRefreshResult:
    index_id: str
    path: str


class VectorStoreManager:
    """Owns the active vector-store reference and swaps only complete indexes."""

    def __init__(
        self,
        vector_db_dir: str,
        embedding_model: Any,
        builder: Optional[Callable[..., Any]] = None,
        loader: Optional[Callable[[str, Any], Any]] = None,
    ):
        self._root = Path(vector_db_dir)
        self._embedding_model = embedding_model
        self._builder = builder
        self._loader = loader
        self._lock = threading.RLock()
        self._store = None
        self._active_path: Optional[Path] = None

    @property
    def ready(self) -> bool:
        return self.get() is not None

    @property
    def active_path(self) -> Optional[str]:
        with self._lock:
            return str(self._active_path) if self._active_path else None

    def get(self):
        """Return a stable snapshot; callers may query it without holding the lock."""
        with self._lock:
            return self._store

    def load(self):
        path = self._resolve_active_path()
        if path is None:
            logger.warning("vector store is not initialized; run the full index builder")
            return None
        loader = self._loader or self._default_loader
        store = loader(str(path), self._embedding_model)
        with self._lock:
            self._store = store
            self._active_path = path
        logger.info("loaded vector store from %s", path)
        return store

    def refresh(self, documents_dir: str) -> IndexRefreshResult:
        index_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "-" + uuid.uuid4().hex[:8]
        version_path = self._root / VERSIONS_DIR / index_id
        version_path.mkdir(parents=True, exist_ok=False)
        builder = self._builder or self._default_builder
        store = builder(
            documents_dir=documents_dir,
            vector_db_dir=str(version_path),
            embedding=self._embedding_model,
        )
        if store is None:
            raise RuntimeError("vector-store builder returned no index")

        self._write_pointer(index_id)
        with self._lock:
            self._store = store
            self._active_path = version_path
        logger.info("activated vector index %s", index_id)
        return IndexRefreshResult(index_id=index_id, path=str(version_path))

    def _resolve_active_path(self) -> Optional[Path]:
        pointer_path = self._root / POINTER_FILE
        if pointer_path.is_file():
            try:
                payload = json.loads(pointer_path.read_text(encoding="utf-8"))
                version_path = self._root / VERSIONS_DIR / payload["indexId"]
                if version_path.is_dir() and any(version_path.iterdir()):
                    return version_path
                logger.warning("active vector-index pointer targets a missing directory: %s", version_path)
            except (OSError, ValueError, KeyError, TypeError) as exc:
                logger.warning("ignoring invalid vector-index pointer: %s", type(exc).__name__)

        # Backward compatibility for the pre-versioned Chroma directory.
        if self._root.is_dir() and any(
            item.name not in {POINTER_FILE, VERSIONS_DIR} for item in self._root.iterdir()
        ):
            return self._root
        return None

    def _write_pointer(self, index_id: str) -> None:
        self._root.mkdir(parents=True, exist_ok=True)
        target = self._root / POINTER_FILE
        temporary = self._root / f".{POINTER_FILE}.{uuid.uuid4().hex}.tmp"
        payload = {"indexId": index_id, "updatedAt": datetime.now(timezone.utc).isoformat()}
        temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        os.replace(temporary, target)

    @staticmethod
    def _default_loader(path: str, embedding_model):
        from langchain_community.vectorstores import Chroma

        return Chroma(persist_directory=path, embedding_function=embedding_model)

    @staticmethod
    def _default_builder(**kwargs):
        from build_vector_store import build_vector_store

        return build_vector_store(**kwargs)
