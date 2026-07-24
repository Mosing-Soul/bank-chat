import json
import unittest

import httpx

from ai_chat_models import Citation, IntentEntities, IntentType, SkillRequest, SkillResult
from intent.structured_intent import IntentRecognitionService, fallback_intent
from java_skill_client import JavaSkillClient, JavaSkillClientError
from skill_handlers import CustomerAumSkill, ExternalModelApiSkill, KnowledgeRagSkill, MessagePreviewSkill
from skill_router import SkillRouter


def skill_request(intent=IntentType.UNKNOWN, entities=None, message="test"):
    return SkillRequest(
        trace_id="trace-1",
        session_id="session-1",
        user_message=message,
        intent=intent,
        entities=entities or IntentEntities(),
    )


class FakeJavaClient:
    def __init__(self):
        self.send_called = False

    def search_customers(self, trace_id, name):
        if name == "多人":
            return [{"customerId": "C1", "customerName": "多人"}, {"customerId": "C2", "customerName": "多人"}]
        return [{"customerId": "CUST001", "customerName": name, "mock": True}]

    def get_aum(self, trace_id, customer_id):
        return {
            "customerId": customer_id,
            "customerName": "张伟",
            "totalAum": "8260000.00",
            "currency": "CNY",
            "statisticsDate": "2026-06-23",
            "mock": True,
        }

    def get_gold_price(self, trace_id):
        return {
            "instrumentName": "模拟黄金 Au9999",
            "price": "548.36",
            "currency": "CNY",
            "unit": "gram",
            "change": "1.82",
            "changePercent": "0.33",
            "mock": True,
        }

    def preview_message(self, trace_id, customer_id, template_code, variables):
        return {
            "operationId": "op-1",
            "customerName": "张伟",
            "content": "张伟先生/女士，您的产品将于近期到期。",
            "status": "PENDING_CONFIRMATION",
            "mock": True,
        }


    def send_message(self, trace_id, operation_id, confirmed=True):
        self.send_called = True
        return {
            "operationId": operation_id,
            "customerName": "张伟",
            "status": "SENT",
            "channel": "MOCK_ENTERPRISE_WECHAT",
            "mock": True,
        }

class BrokenJavaClient:
    def search_customers(self, trace_id, name):
        raise JavaSkillClientError("JAVA_SKILL_NETWORK_ERROR", "Java skill service unavailable")

    def get_aum(self, trace_id, customer_id):
        raise JavaSkillClientError("JAVA_SKILL_NETWORK_ERROR", "Java skill service unavailable")


class AiChatTests(unittest.TestCase):
    def test_six_intents_fallback(self):
        cases = [
            ("理财产品能提前赎回吗", IntentType.KNOWLEDGE_QA),
            ("反洗钱法中，临时冻结的最长时限是48小时吗？", IntentType.KNOWLEDGE_QA),
            ("银行保险机构资产管理产品信息披露有什么要求？", IntentType.KNOWLEDGE_QA),
            ("商业银行主要监管指标里资本充足率是多少？", IntentType.KNOWLEDGE_QA),
            ("普惠金融走进千企万户活动讲了什么？", IntentType.KNOWLEDGE_QA),
            ("查询客户张伟当前AUM", IntentType.CUSTOMER_AUM_QUERY),
            ("黄金现在多少钱", IntentType.EXTERNAL_API_QUERY),
            ("今天上海天气怎么样", IntentType.EXTERNAL_API_QUERY),
            ("给张伟发送产品到期提醒", IntentType.MESSAGE_SEND),
            ("你好，介绍一下你自己", IntentType.GENERAL_CHAT),
            ("帮我查一下", IntentType.GENERAL_CHAT),
            ("查一下资料", IntentType.GENERAL_CHAT),
        ]
        for text, expected in cases:
            with self.subTest(text=text):
                self.assertEqual(fallback_intent(text).intent, expected)

    def test_semantic_entity_extraction_does_not_treat_customer_level_as_name(self):
        result = fallback_intent("帮我查一下客户等级")

        self.assertIsNone(result.entities.customerName)
        self.assertNotEqual(result.intent, IntentType.CUSTOMER_AUM_QUERY)

    def test_semantic_entity_extraction_extracts_explicit_customer_name(self):
        result = fallback_intent("查询客户张伟当前AUM")

        self.assertEqual(result.intent, IntentType.CUSTOMER_AUM_QUERY)
        self.assertEqual(result.entities.customerName, "张伟")
        self.assertEqual(result.missingSlots, [])

    def test_low_confidence_becomes_unknown(self):
        result = IntentRecognitionService(llm=None, threshold=0.9).recognize("黄金现在多少钱")
        self.assertEqual(result.intent, IntentType.UNKNOWN)

    def test_structured_output_failure_falls_back(self):
        class BrokenLlm:
            def with_structured_output(self, schema):
                raise RuntimeError("broken")

        result = IntentRecognitionService(llm=BrokenLlm()).recognize("查询客户张伟当前AUM")
        self.assertEqual(result.intent, IntentType.CUSTOMER_AUM_QUERY)

    def test_model_unknown_uses_local_semantic_fallback(self):
        class UnknownLlm:
            def with_structured_output(self, schema):
                return self

            def invoke(self, payload):
                return {
                    "intent": "UNKNOWN",
                    "confidence": 0.88,
                    "entities": {},
                    "reason": "model did not classify",
                }

        result = IntentRecognitionService(llm=UnknownLlm()).recognize("反洗钱法中，临时冻结的最长时限是48小时吗？")
        self.assertEqual(result.intent, IntentType.KNOWLEDGE_QA)

    def test_router_context_prior_overrides_confusing_customer_level_question(self):
        result = IntentRecognitionService(llm=None).recognize(
            "招行的客户等级是怎么样的",
            router_intent="RAG_QUERY",
            router_confidence=0.92,
            entities={"bankNames": ["招行"], "businessTerms": ["客户等级"]},
            dialog_act="ROUTER_SWITCH_INTENT",
        )

        self.assertEqual(result.intent, IntentType.KNOWLEDGE_QA)
        self.assertGreaterEqual(result.confidence, 0.92)

    def test_router_entities_can_fill_customer_name_for_specific_customer_query(self):
        result = IntentRecognitionService(llm=None).recognize(
            "查一下客户张伟的AUM",
            router_intent="CUSTOMER_AUM",
            router_confidence=0.88,
            entities={"customerNames": ["张伟"]},
            dialog_act="ROUTER_SWITCH_INTENT",
        )

        self.assertEqual(result.intent, IntentType.CUSTOMER_AUM_QUERY)
        self.assertEqual(result.entities.customerName, "张伟")

    def test_configured_examples_can_drive_intent(self):
        result = IntentRecognitionService(llm=None).recognize(
            "反洗钱法中，临时冻结的最长时限是48小时吗？",
            skill_examples={
                "examples": [
                    {
                        "skillCode": "RAG_QUERY",
                        "text": "反洗钱法中，临时冻结的最长时限是48小时吗？",
                        "displayText": "反洗钱临时冻结时限",
                        "confidence": 0.92,
                    }
                ]
            },
        )

        self.assertEqual(result.intent, IntentType.KNOWLEDGE_QA)
        self.assertGreaterEqual(result.confidence, 0.9)

    def test_router_selects_handler_and_unknown_fallback(self):
        class OkHandler:
            skill_name = "ok"

            def handle(self, request):
                return SkillResult(success=True, answer="ok")

        router = SkillRouter({IntentType.GENERAL_CHAT: OkHandler()})
        result, call = router.route(skill_request(IntentType.GENERAL_CHAT))
        self.assertEqual(result.answer, "ok")
        self.assertEqual(call.skill, "ok")
        result, call = router.route(skill_request(IntentType.UNKNOWN))
        self.assertIn("请说明", result.answer)
        self.assertEqual(call.skill, "clarification")

    def test_rag_handler_keeps_citations(self):
        class RagResponse:
            answer = "answer"
            sources = ["doc.md"]

        result = KnowledgeRagSkill(lambda question, session_id, history: RagResponse()).handle(skill_request())
        self.assertEqual(result.citations, [Citation(source="doc.md", title="doc.md")])

    def test_aum_success_multi_and_missing_customer(self):
        handler = CustomerAumSkill(FakeJavaClient())
        result = handler.handle(skill_request(IntentType.CUSTOMER_AUM_QUERY, IntentEntities(customerName="张伟")))
        self.assertIn("8260000.00", result.answer)
        result = handler.handle(skill_request(IntentType.CUSTOMER_AUM_QUERY, IntentEntities(customerName="多人")))
        self.assertIn("candidates", result.data)
        result = handler.handle(skill_request(IntentType.CUSTOMER_AUM_QUERY, IntentEntities()))
        self.assertIn("请提供", result.answer)

    def test_aum_uses_local_mock_when_java_unavailable(self):
        handler = CustomerAumSkill(BrokenJavaClient())
        result = handler.handle(skill_request(IntentType.CUSTOMER_AUM_QUERY, IntentEntities(customerName="张伟")))
        self.assertTrue(result.success)
        self.assertIn("8260000.00", result.answer)
        self.assertEqual(result.data["aum"]["customerId"], "CUST001")

    def test_external_model_api_skill(self):
        class FakeSearchClient:
            def search_text(self, question):
                return "external answer"

        result = ExternalModelApiSkill(FakeSearchClient()).handle(
            skill_request(IntentType.EXTERNAL_API_QUERY, message="黄金现在多少钱")
        )
        self.assertTrue(result.success)
        self.assertEqual(result.answer, "external answer")
        self.assertEqual(result.data["externalApi"]["provider"], "google-serper")

    def test_external_model_api_requires_search_client(self):
        result = ExternalModelApiSkill().handle(skill_request(IntentType.EXTERNAL_API_QUERY, message="今天上海天气"))
        self.assertFalse(result.success)
        self.assertEqual(result.error_code, "EXTERNAL_MODEL_UNAVAILABLE")

    def test_message_preview_only(self):
        client = FakeJavaClient()
        result = MessagePreviewSkill(client).handle(
            skill_request(IntentType.MESSAGE_SEND, IntentEntities(customerName="张伟", templateCode="PRODUCT_MATURITY_REMINDER", messagePurpose="产品到期提醒"))
        )
        self.assertTrue(result.requires_confirmation)
        self.assertEqual(result.confirmation["operationId"], "op-1")
        self.assertFalse(client.send_called)

    def test_message_confirm_send_from_history(self):
        client = FakeJavaClient()
        request = skill_request(IntentType.MESSAGE_SEND, message="确认发送")
        request.history = [
            type("History", (), {"role": "assistant", "content": "已生成消息预览，请确认后再发送。\n- 客户：张伟\n- 消息内容：测试\n- operationId：op-1"})()
        ]
        result = MessagePreviewSkill(client).handle(request)
        self.assertTrue(result.success)
        self.assertTrue(client.send_called)
        self.assertIn("已确认并发送", result.answer)

    def test_message_slot_filling_from_history(self):
        client = FakeJavaClient()
        request = skill_request(IntentType.MESSAGE_SEND, message="张伟")
        request.history = [
            type("History", (), {"role": "user", "content": "给客户发消息"})(),
            type("History", (), {"role": "assistant", "content": "请补充要发送消息的客户姓名和消息用途。用途可以是产品到期提醒、资产配置提醒，或直接给出自定义内容。"})(),
            type("History", (), {"role": "user", "content": "产品到期提醒"})(),
            type("History", (), {"role": "assistant", "content": "已识别消息用途：产品到期提醒。请提供要发送消息的客户姓名，例如：张伟。"})(),
        ]
        result = MessagePreviewSkill(client).handle(request)
        self.assertTrue(result.requires_confirmation)
        self.assertEqual(result.confirmation["operationId"], "op-1")
    def test_java_skill_client_error(self):
        def handler(request):
            return httpx.Response(500, json={"success": False, "error": {"code": "ERR", "message": "bad"}})

        client = JavaSkillClient(
            base_url="http://java-skill",
            api_key="test-key",
            api_key_header="X-Test-Key",
            timeout=1,
            transport=httpx.MockTransport(handler),
        )
        with self.assertRaises(JavaSkillClientError):
            client.search_customers("trace-1", "张伟")


if __name__ == "__main__":
    unittest.main()
