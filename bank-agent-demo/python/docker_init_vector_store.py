"""Initialize the Docker vector-store volume only when source documents change."""

import hashlib
import json
from pathlib import Path

from build_vector_store import create_embedding_model
from env_config import env_path
from vector_store_manager import VectorStoreManager


SUPPORTED_EXTENSIONS = {".pdf", ".xls", ".xlsx", ".md"}


def document_fingerprint(documents_dir: Path) -> str:
    digest = hashlib.sha256()
    files = sorted(
        path for path in documents_dir.iterdir()
        if path.is_file() and path.suffix.lower() in SUPPORTED_EXTENSIONS
    )
    if not files:
        raise RuntimeError(f"文档目录中没有可索引文件: {documents_dir}")
    for path in files:
        digest.update(path.name.encode("utf-8"))
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
    return digest.hexdigest()


def current_index_is_valid(vector_dir: Path) -> bool:
    pointer = vector_dir / "current-index.json"
    if not pointer.is_file():
        return False
    try:
        index_id = json.loads(pointer.read_text(encoding="utf-8"))["indexId"]
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
        return False
    index_dir = vector_dir / "versions" / index_id
    return index_dir.is_dir() and any(index_dir.iterdir())


def main() -> None:
    documents_dir = env_path("BUILD_DOCUMENTS_DIR")
    vector_dir = env_path("BUILD_VECTOR_DB_DIR")
    vector_dir.mkdir(parents=True, exist_ok=True)
    fingerprint = document_fingerprint(documents_dir)
    manifest = vector_dir / "documents.sha256"

    if (
        current_index_is_valid(vector_dir)
        and manifest.is_file()
        and manifest.read_text(encoding="utf-8").strip() == fingerprint
    ):
        print("文档未变化，复用现有向量索引。")
        return

    print("检测到首次部署或文档变化，开始构建向量索引。")
    manager = VectorStoreManager(str(vector_dir), create_embedding_model())
    result = manager.refresh(str(documents_dir))
    temporary_manifest = manifest.with_suffix(".tmp")
    temporary_manifest.write_text(fingerprint, encoding="utf-8")
    temporary_manifest.replace(manifest)
    print(f"向量索引已就绪: {result.index_id}")


if __name__ == "__main__":
    main()
