import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "bank-agent-demo" / "python"))

from vector_store_manager import POINTER_FILE, VERSIONS_DIR, VectorStoreManager


class VectorStoreManagerTest(unittest.TestCase):
    def test_refresh_builds_new_version_then_atomically_activates_it(self):
        with tempfile.TemporaryDirectory() as directory:
            built_store = object()

            def builder(documents_dir, vector_db_dir, embedding):
                self.assertEqual("docs", documents_dir)
                self.assertEqual("embedding", embedding)
                Path(vector_db_dir, "chroma.sqlite3").write_text("ready", encoding="utf-8")
                return built_store

            manager = VectorStoreManager(directory, "embedding", builder=builder)
            result = manager.refresh("docs")

            pointer = json.loads(Path(directory, POINTER_FILE).read_text(encoding="utf-8"))
            self.assertIs(built_store, manager.get())
            self.assertEqual(result.index_id, pointer["indexId"])
            self.assertTrue(Path(directory, VERSIONS_DIR, result.index_id).is_dir())

    def test_failed_refresh_keeps_current_store_and_pointer(self):
        with tempfile.TemporaryDirectory() as directory:
            stores = [object(), object()]

            def successful_builder(**kwargs):
                Path(kwargs["vector_db_dir"], "chroma.sqlite3").write_text("ready", encoding="utf-8")
                return stores[0]

            manager = VectorStoreManager(directory, "embedding", builder=successful_builder)
            first = manager.refresh("docs")

            def failed_builder(**kwargs):
                raise RuntimeError("build failed")

            manager._builder = failed_builder
            with self.assertRaises(RuntimeError):
                manager.refresh("docs")

            pointer = json.loads(Path(directory, POINTER_FILE).read_text(encoding="utf-8"))
            self.assertIs(stores[0], manager.get())
            self.assertEqual(first.index_id, pointer["indexId"])

    def test_load_resolves_version_pointer(self):
        with tempfile.TemporaryDirectory() as directory:
            version = Path(directory, VERSIONS_DIR, "v1")
            version.mkdir(parents=True)
            Path(version, "chroma.sqlite3").write_text("ready", encoding="utf-8")
            Path(directory, POINTER_FILE).write_text('{"indexId":"v1"}', encoding="utf-8")
            loaded_store = object()
            loader_calls = []

            def loader(path, embedding):
                loader_calls.append((path, embedding))
                return loaded_store

            manager = VectorStoreManager(directory, "embedding", loader=loader)
            manager.load()

            self.assertIs(loaded_store, manager.get())
            self.assertEqual([(str(version), "embedding")], loader_calls)


if __name__ == "__main__":
    unittest.main()
