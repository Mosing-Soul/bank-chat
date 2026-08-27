import re

from ai_chat_models import IntentEntities, IntentResult, IntentType
from env_config import env_float


SYSTEM_PROMPT = """
你是银行客户经理助手的轻量意图分类器。结合最近对话，判断当前问题需要哪些信息来源。
只允许使用以下意图：
- KNOWLEDGE_QA：银行内部制度、产品、客户分层标准、业务流程、SOP、合规要求，需要查内部知识库。
- EXTERNAL_API_QUERY：当前行情、新闻、天气、汇率或其他公开网络资料，需要联网搜索。
- GENERAL_CHAT：无需检索即可回答的通用对话，包括对助手身份、能力和使用方式的询问，
  以及问候、解释、写作和无害的日常交流；无法可靠判断时也走此兜底。
- UNKNOWN：只有多个目标互斥且必须由用户选择时才使用。

分类示例：
- “华辰银行白金级客户的资产门槛是多少？” → KNOWLEDGE_QA
- “办消费信用贷要交哪些收入和用途证明？” → KNOWLEDGE_QA
- “今天黄金价格是多少？” → EXTERNAL_API_QUERY
- “结合行内黄金产品规定和今天金价分析风险” → 同时选择 KNOWLEDGE_QA、EXTERNAL_API_QUERY
- “你好” → GENERAL_CHAT

可以同时选择 KNOWLEDGE_QA 和 EXTERNAL_API_QUERY。selectedIntents 写入全部需要执行的意图；
intent 写主意图；rewrittenQuery 结合最近对话消解指代，改写成独立可理解的问题。
改写可以补充上下文，但应保留用户原有的目标、条件和子问题。
当当前输入包含“这个、它、那、如果……呢”等省略表达时，rewrittenQuery 必须补全上一轮的业务对象和用户真正要问的内容，不能原样保留省略句。
普通模糊问题使用 GENERAL_CHAT，不要返回 UNKNOWN。reason 只写一句简短依据。
必须以 JSON 对象返回符合 IntentResult 字段定义的结果，不要输出 JSON 之外的文字。
"""

try:
    from langchain_core.prompts import ChatPromptTemplate
    PROMPT = ChatPromptTemplate.from_messages([
        ("system", SYSTEM_PROMPT),
        ("human", "最近对话：\n{history}\n\n当前用户输入：{user_input}"),
    ])
except Exception:
    PROMPT = None


class IntentRecognitionService:
    def __init__(self, llm=None, threshold=None):
        self.llm = llm
        self.threshold = float(threshold) if threshold is not None else env_float("INTENT_CONFIDENCE_THRESHOLD")

    def recognize(self, user_input: str, history=None, **_ignored) -> IntentResult:
        if not user_input or not user_input.strip():
            return IntentResult(intent=IntentType.GENERAL_CHAT, selectedIntents=[IntentType.GENERAL_CHAT],
                                confidence=0.0, reason="empty input")
        history_text = "\n".join(
            f"{getattr(item, 'role', '')}: {getattr(item, 'content', '')}" for item in (history or [])[-8:]
        ) or "（无）"
        try:
            if self.llm is None or PROMPT is None:
                result = fallback_intent(user_input)
            else:
                result = (PROMPT | self.llm.with_structured_output(
                    IntentResult,
                    method="json_schema",
                )).invoke({
                    "user_input": user_input,
                    "history": history_text,
                })
                if isinstance(result, dict):
                    result = IntentResult(**result)
        except Exception as exc:
            result = fallback_intent(user_input, f"model unavailable: {type(exc).__name__}")

        allowed = {IntentType.KNOWLEDGE_QA, IntentType.EXTERNAL_API_QUERY,
                   IntentType.GENERAL_CHAT, IntentType.UNKNOWN}
        selected = [item for item in result.selectedIntents if item in allowed]
        if result.intent not in allowed or result.confidence < self.threshold:
            result.intent = IntentType.GENERAL_CHAT
            selected = [IntentType.GENERAL_CHAT]
            result.reason = "low confidence conversational fallback"
        result.selectedIntents = selected or [result.intent]
        result.rewrittenQuery = (result.rewrittenQuery or user_input).strip()
        return result


def fallback_intent(user_input: str, reason: str = "offline semantic fallback", **_ignored) -> IntentResult:
    text = user_input.strip()
    knowledge = bool(re.search(
        r"(制度|规定|规则|标准|流程|SOP|办理|材料|客户等级|客户分层|反洗钱|合规|监管|产品说明)", text,
        re.IGNORECASE,
    ))
    external = bool(re.search(
        r"(今天|现在|当前|实时|最新|新闻|天气|汇率|行情|黄金|金价|股价|外部|网上)", text,
        re.IGNORECASE,
    ))
    if knowledge and external:
        return IntentResult(intent=IntentType.KNOWLEDGE_QA,
                            selectedIntents=[IntentType.KNOWLEDGE_QA, IntentType.EXTERNAL_API_QUERY],
                            confidence=0.82, rewrittenQuery=text, reason=reason)
    if knowledge:
        return IntentResult(intent=IntentType.KNOWLEDGE_QA, selectedIntents=[IntentType.KNOWLEDGE_QA],
                            confidence=0.82, rewrittenQuery=text, reason=reason)
    if external:
        return IntentResult(intent=IntentType.EXTERNAL_API_QUERY,
                            selectedIntents=[IntentType.EXTERNAL_API_QUERY], confidence=0.82,
                            rewrittenQuery=text, reason=reason)
    return IntentResult(intent=IntentType.GENERAL_CHAT, selectedIntents=[IntentType.GENERAL_CHAT],
                        confidence=0.7, rewrittenQuery=text, entities=IntentEntities(), reason=reason)
