import unittest
import sys
from pathlib import Path
from datetime import datetime
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "bank-agent-demo" / "python"))

from ai_chat_models import AiChatRequest, HistoryMessage, IntentResult, IntentType
from ai_chat_orchestrator import AiChatOrchestrator


class StaticIntentService:
    def __init__(self, result):
        self.result = result
        self.history = None

    def recognize(self, message, history=None):
        self.history = history
        return self.result


class FakeRag:
    def __init__(self):
        self.queries = []

    def retrieve(self, query):
        self.queries.append(query)
        return SimpleNamespace(context="内部SOP证据", sources=["消费贷款SOP.pdf"], hit_count=1)


class FakeSearch:
    def __init__(self):
        self.queries = []

    def search_text(self, query):
        self.queries.append(query)
        return "外部公开信息"


class FakeStructuredSearch:
    def search_with_sources(self, query):
        return SimpleNamespace(
            context="[WEB-1] 标题：监管公告\nURL：https://example.com/policy",
            sources=[SimpleNamespace(
                title="监管公告", url="https://example.com/policy", snippet="", date="2026-08-20"
            )],
        )


class FakeLlm:
    def __init__(self):
        self.prompts = []

    def invoke(self, prompt):
        self.prompts.append(prompt)
        return SimpleNamespace(content="统一生成的答案")


class ConversationPipelineTest(unittest.TestCase):
    @staticmethod
    def fixed_now():
        return datetime(2026, 8, 25, 12, 0, 0)

    def payload(self):
        return AiChatRequest(
            traceId="t1", sessionId="s1", message="那现在外部有什么变化？",
            history=[HistoryMessage(role="user", content="消费贷款办理规则是什么？")],
        )

    def test_rag_and_external_are_composed_before_one_answer_call(self):
        intent_service = StaticIntentService(IntentResult(
            intent=IntentType.KNOWLEDGE_QA,
            selectedIntents=[IntentType.KNOWLEDGE_QA, IntentType.EXTERNAL_API_QUERY],
            rewrittenQuery="消费贷款办理规则及当前外部变化",
            confidence=0.93,
        ))
        rag, search, llm = FakeRag(), FakeSearch(), FakeLlm()
        response = AiChatOrchestrator(
            intent_service, rag, search, llm, now_provider=self.fixed_now
        ).invoke(self.payload())

        self.assertEqual(["消费贷款办理规则及当前外部变化"], rag.queries)
        self.assertEqual(
            ["消费贷款办理规则及当前外部变化\n当前日期（北京时间）：2026-08-25"],
            search.queries,
        )
        self.assertEqual(1, len(llm.prompts))
        self.assertIn("内部SOP证据", llm.prompts[0])
        self.assertIn("外部公开信息", llm.prompts[0])
        self.assertIn("当前日期（北京时间）：2026-08-25", llm.prompts[0])
        self.assertTrue(response.answer.startswith("统一生成的答案"))
        self.assertNotIn("行内来源", response.answer)
        self.assertEqual("消费贷款SOP.pdf", response.citations[0].source)
        self.assertEqual("INTERNAL", response.citations[0].type)
        self.assertEqual(["消费贷款SOP.pdf"], response.sources)
        self.assertEqual("RAG + WEB", response.data["executionTrace"]["route"])
        self.assertEqual(1, response.data["executionTrace"]["metrics"]["citationCount"])
        self.assertEqual(3, len(response.skillCalls))
        self.assertEqual(self.payload().history, intent_service.history)

    def test_general_chat_skips_all_retrieval(self):
        service = StaticIntentService(IntentResult(
            intent=IntentType.GENERAL_CHAT, selectedIntents=[IntentType.GENERAL_CHAT], confidence=0.7,
        ))
        rag, search, llm = FakeRag(), FakeSearch(), FakeLlm()
        response = AiChatOrchestrator(service, rag, search, llm).invoke(self.payload())

        self.assertEqual([], rag.queries)
        self.assertEqual([], search.queries)
        self.assertEqual("统一生成的答案", response.answer)

    def test_external_sources_are_kept_as_web_citations_for_inline_links(self):
        service = StaticIntentService(IntentResult(
            intent=IntentType.EXTERNAL_API_QUERY,
            selectedIntents=[IntentType.EXTERNAL_API_QUERY],
            rewrittenQuery="最近消费贷监管新闻",
            confidence=0.9,
        ))
        llm = FakeLlm()
        response = AiChatOrchestrator(
            service, FakeRag(), FakeStructuredSearch(), llm, now_provider=self.fixed_now
        ).invoke(self.payload())

        self.assertEqual("WEB", response.citations[0].type)
        self.assertEqual("https://example.com/policy", response.citations[0].url)
        self.assertIn("[↗](对应URL)", llm.prompts[0])
        self.assertIn("URL：https://example.com/policy", llm.prompts[0])

    def test_true_ambiguity_returns_selection_without_answer_model(self):
        service = StaticIntentService(IntentResult(
            intent=IntentType.UNKNOWN,
            candidateIntents=[IntentType.KNOWLEDGE_QA, IntentType.EXTERNAL_API_QUERY],
            confidence=0.55,
        ))
        llm = FakeLlm()
        response = AiChatOrchestrator(service, FakeRag(), FakeSearch(), llm).invoke(self.payload())

        self.assertTrue(response.requiresConfirmation)
        self.assertEqual("INTENT_SELECTION", response.confirmation["type"])
        self.assertEqual([], llm.prompts)


if __name__ == "__main__":
    unittest.main()
