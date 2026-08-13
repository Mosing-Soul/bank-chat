import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient
from langchain_core.documents import Document

from rag_models import QueryRequest, QueryResponse
from rag_query_service import RagService
from rag_service import (
    RuntimeSettings,
    app,
    build_rag_answer_prompt,
    initialize_runtime,
    perform_rag_query,
    unique_sources,
)


class FakeLlm:
    def __init__(self, answer="模型回答", error=None):
        self.answer = answer
        self.error = error
        self.prompts = []

    def invoke(self, prompt):
        self.prompts.append(prompt)
        if self.error:
            raise self.error
        return SimpleNamespace(content=self.answer)


class FakeVectorStore:
    def __init__(self, results):
        self.results = results
        self.calls = []

    def similarity_search_with_score(self, question, k):
        self.calls.append((question, k))
        return self.results


class FakeVectorStoreManager:
    def __init__(self, store=None):
        self.store = store

    @property
    def ready(self):
        return self.store is not None

    def get(self):
        return self.store


def runtime(llm=None, vector_store=None, threshold=0.5, top_k=5):
    settings = RuntimeSettings(
        docs_folder="unused",
        vector_db_dir="unused",
        rag_score_threshold=threshold,
        rag_top_k=top_k,
        host="127.0.0.1",
        port=8000,
    )
    manager = FakeVectorStoreManager(vector_store)
    rag_service = RagService(manager, llm or FakeLlm(), threshold, top_k)
    return SimpleNamespace(
        settings=settings,
        vector_store_manager=manager,
        rag_service=rag_service,
    )


class RagServiceCharacterizationTest(unittest.TestCase):
    @patch("rag_service.AiChatOrchestrator")
    @patch("rag_service.build_skill_router")
    @patch("rag_service.DialogueCommandInterpreter")
    @patch("rag_service.IntentRecognitionService")
    @patch("rag_service.VectorStoreManager")
    @patch("rag_service.create_external_search_client")
    @patch("rag_service.create_chat_model")
    @patch("rag_service.create_embedding_model")
    def test_initialize_runtime_builds_one_dependency_graph(
        self,
        embedding_factory,
        chat_factory,
        search_factory,
        manager_type,
        intent_type,
        dialogue_type,
        router_builder,
        orchestrator_type,
    ):
        embedding_factory.return_value = "embedding"
        chat_factory.return_value = "llm"
        search_factory.return_value = "search"
        manager = manager_type.return_value
        manager.get.return_value = "store"
        router_builder.return_value = "router"

        resources = initialize_runtime()

        manager_type.assert_called_once_with(resources.settings.vector_db_dir, "embedding")
        manager.load.assert_called_once_with()
        router_builder.assert_called_once_with(resources.rag_service, "search", "llm")
        orchestrator_type.assert_called_once_with(intent_type.return_value, "router")

    def test_query_without_vector_store_uses_model_and_has_no_sources(self):
        fake_llm = FakeLlm("【行内文档结论】暂无相关文档。\n\n【来源】无")
        resources = runtime(llm=fake_llm)

        response = perform_rag_query("问题", "session", runtime=resources)

        self.assertEqual([], response.sources)
        self.assertIn("暂无相关文档", response.answer)
        self.assertIn("用户问题：问题", fake_llm.prompts[0])

    def test_query_filters_by_threshold_and_deduplicates_sources(self):
        docs = [
            (Document(page_content="相关一", metadata={"source": "a.pdf", "page": 1}), 0.2),
            (Document(page_content="相关二", metadata={"source": "a.pdf", "page": 2}), 0.3),
            (Document(page_content="低相关", metadata={"source": "b.pdf"}), 0.8),
        ]
        store = FakeVectorStore(docs)

        response = perform_rag_query("问题", "session", runtime=runtime(vector_store=store))

        self.assertEqual(["a.pdf"], response.sources)
        self.assertEqual([("问题", 5)], store.calls)

    def test_query_returns_grounded_fallback_when_model_fails(self):
        docs = [(Document(page_content="相关", metadata={"source": "rule.pdf"}), 0.2)]
        response = perform_rag_query(
            "问题",
            "session",
            runtime=runtime(llm=FakeLlm(error=RuntimeError("boom")), vector_store=FakeVectorStore(docs)),
        )
        self.assertIn("大模型整理暂时不可用", response.answer)
        self.assertEqual(["rule.pdf"], response.sources)

    def test_prompt_marks_low_score_candidates_as_non_authoritative(self):
        candidate = [(Document(page_content="候选", metadata={"source": "candidate.pdf"}), 0.9)]
        prompt = build_rag_answer_prompt("问题", [], [], candidate)
        self.assertIn("暂未命中高相关行内文档", prompt)
        self.assertIn("不作为行内依据", prompt)

    def test_models_do_not_share_mutable_defaults(self):
        first_request = QueryRequest(question="q1", session_id="s1")
        second_request = QueryRequest(question="q2", session_id="s2")
        first_response = QueryResponse(answer="a1")
        second_response = QueryResponse(answer="a2")
        first_request.history.append(SimpleNamespace(role="user", content="x"))
        first_response.sources.append("source")
        self.assertEqual([], second_request.history)
        self.assertEqual([], second_response.sources)

    def test_api_contract_has_one_health_route_and_stable_paths(self):
        paths = app.openapi()["paths"]
        self.assertEqual(1, sum(route.path == "/health" for route in app.routes))
        self.assertTrue({
            "/health", "/rag/query", "/rag/eval_query", "/ai/chat/invoke",
            "/ai/dialogue/commands", "/refresh",
        }.issubset(paths))

    @patch("rag_service.initialize_runtime")
    def test_lifespan_exposes_health_and_rag_contract(self, runtime_factory):
        runtime_factory.return_value = runtime(llm=FakeLlm("回答"))
        with TestClient(app) as client:
            health_response = client.get("/health")
            rag_response = client.post(
                "/rag/query",
                json={"question": "问题", "session_id": "session", "history": []},
            )
        self.assertEqual({"status": "ok", "vectorStoreReady": False}, health_response.json())
        self.assertEqual({"answer": "回答", "sources": []}, rag_response.json())

    def test_unique_sources_preserves_retrieval_order(self):
        docs = [
            (Document(page_content="a", metadata={"source": "a.pdf"}), 0.1),
            (Document(page_content="b", metadata={"source": "b.pdf"}), 0.2),
            (Document(page_content="a2", metadata={"source": "a.pdf"}), 0.3),
        ]
        self.assertEqual(["a.pdf", "b.pdf"], unique_sources(docs))


if __name__ == "__main__":
    unittest.main()
