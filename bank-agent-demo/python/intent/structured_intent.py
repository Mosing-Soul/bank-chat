import os
import re

from ai_chat_models import IntentEntities, IntentResult, IntentType


SYSTEM_PROMPT = """
你是银行客户经理智能助手的意图识别模块。
只允许从固定枚举中选择 intent：
KNOWLEDGE_QA, CUSTOMER_AUM_QUERY, EXTERNAL_API_QUERY, MESSAGE_SEND, GENERAL_CHAT, UNKNOWN。

区分规则：
- KNOWLEDGE_QA：用户在问银行内部文档、制度材料、产品说明、业务规则、操作口径、术语定义、准入条件、监管法规、合规要求、客户分层标准等知识性问题，需要 RAG 回答。即使用户没有明确说“查文档”，只要是一个可从知识库材料中回答的业务问题，也归为 KNOWLEDGE_QA。
- CUSTOMER_AUM_QUERY：查询某个具体客户的资产、AUM、持仓汇总等业务数据，需要客户姓名或编号。
- EXTERNAL_API_QUERY：查询外部实时信息或通用模型可回答的问题，例如黄金价格、天气、汇率、股票、新闻等。
- MESSAGE_SEND：要求给客户生成、预览或发送消息、提醒、通知。本阶段只做预览。
- GENERAL_CHAT：问候、介绍助手能力等普通对话。
- UNKNOWN：信息不足或无法判断，例如“帮我查一下”“这个呢”“查一下资料”。

示例：
- “反洗钱法中，临时冻结的最长时限是48小时吗？” -> KNOWLEDGE_QA
- “银行保险机构资产管理产品信息披露有什么要求？” -> KNOWLEDGE_QA
- “金葵花客户达标标准是什么？” -> KNOWLEDGE_QA
- “普惠金融走进千企万户活动讲了什么？” -> KNOWLEDGE_QA
- “商业银行主要监管指标里资本充足率是多少？” -> KNOWLEDGE_QA
- “查询客户张伟当前AUM” -> CUSTOMER_AUM_QUERY
- “给张伟生成产品到期提醒” -> MESSAGE_SEND

要求：
1. 不要生成函数名、URL 或路由节点名称。
2. confidence 必须是 0 到 1 的数字。
3. entities 只填能从用户话语明确抽取到的字段。
4. reason 用一句简短分类依据，不要输出推理链。
"""

try:
    from langchain_core.prompts import ChatPromptTemplate

    PROMPT = ChatPromptTemplate.from_messages([
        ("system", SYSTEM_PROMPT),
        ("human", "{user_input}"),
    ])
except Exception:
    PROMPT = None


class IntentRecognitionService:
    def __init__(self, llm=None, threshold=None):
        self.llm = llm
        self.threshold = float(threshold or os.getenv("INTENT_CONFIDENCE_THRESHOLD", "0.6"))

    def recognize(self, user_input: str) -> IntentResult:
        if not user_input or not user_input.strip():
            return self._unknown("empty input")

        try:
            if self.llm is None or PROMPT is None:
                result = fallback_intent(user_input)
            else:
                chain = PROMPT | self.llm.with_structured_output(IntentResult)
                result = chain.invoke({"user_input": user_input})
                if isinstance(result, dict):
                    result = IntentResult(**result)
        except Exception as exc:
            return fallback_intent(user_input, reason=f"model unavailable: {type(exc).__name__}")

        local_result = fallback_intent(user_input, reason="local semantic fallback")
        if result.intent == IntentType.UNKNOWN and local_result.intent != IntentType.UNKNOWN:
            return local_result

        if result.confidence < self.threshold:
            if local_result.intent != IntentType.UNKNOWN and local_result.confidence >= self.threshold:
                return local_result
            return IntentResult(
                intent=IntentType.UNKNOWN,
                confidence=result.confidence,
                entities=result.entities,
                reason="confidence below threshold",
            )
        return result

    def _unknown(self, reason: str) -> IntentResult:
        return IntentResult(
            intent=IntentType.UNKNOWN,
            confidence=0.0,
            entities=IntentEntities(),
            reason=reason,
        )


def fallback_intent(user_input: str, reason: str = "fallback rules") -> IntentResult:
    text = user_input.strip()
    entities = IntentEntities(customerName=extract_customer_name(text))

    if is_customer_aum_query(text, entities):
        return IntentResult(
            intent=IntentType.CUSTOMER_AUM_QUERY,
            confidence=0.72 if entities.customerName else 0.55,
            entities=entities,
            reason=reason,
        )
    if is_message_send_request(text):
        entities.messagePurpose = extract_message_purpose(text)
        entities.templateCode = template_code_for_purpose(entities.messagePurpose)
        return IntentResult(
            intent=IntentType.MESSAGE_SEND,
            confidence=0.76 if entities.customerName and entities.messagePurpose else 0.58,
            entities=entities,
            reason=reason,
        )
    if is_external_api_query(text):
        return IntentResult(intent=IntentType.EXTERNAL_API_QUERY, confidence=0.82, entities=entities, reason=reason)
    if is_knowledge_qa_candidate(text):
        return IntentResult(intent=IntentType.KNOWLEDGE_QA, confidence=0.78, entities=entities, reason=reason)
    if re.search(r"(你好|您好|介绍|你是谁|你能做什么)", text):
        return IntentResult(intent=IntentType.GENERAL_CHAT, confidence=0.78, entities=entities, reason=reason)
    return IntentResult(intent=IntentType.UNKNOWN, confidence=0.4, entities=entities, reason=reason)


def is_knowledge_qa_candidate(text: str) -> bool:
    if len(text) < 4:
        return False

    underspecified_patterns = (
        r"^(帮我)?查(一下)?$",
        r"^(帮我)?看(一下)?$",
        r"^查(资料|文档|知识库)$",
        r"^(这个|那个|它|这个呢|那个呢)$",
    )
    if any(re.search(pattern, text) for pattern in underspecified_patterns):
        return False

    knowledge_terms = (
        "银行",
        "商业银行",
        "保险",
        "金融",
        "普惠",
        "客户",
        "规则",
        "赎回",
        "理财",
        "产品",
        "术语",
        "标准",
        "分级",
        "分层",
        "法规",
        "法律",
        "条款",
        "办法",
        "制度",
        "监管",
        "合规",
        "反洗钱",
        "临时冻结",
        "冻结",
        "信息披露",
        "总资产",
        "总负债",
        "资本充足率",
        "流动性",
        "经营情况",
        "达标",
        "门槛",
        "口径",
        "定义",
        "流程",
        "活动",
        "大赛",
        "材料",
        "文档",
        "知识库",
    )
    question_terms = (
        "吗",
        "是否",
        "什么",
        "怎么",
        "如何",
        "为什么",
        "哪些",
        "哪",
        "谁",
        "何时",
        "哪里",
        "多少",
        "几",
        "最长",
        "时限",
        "期限",
        "规定",
        "要求",
        "可以",
        "能否",
        "能不能",
        "有没有",
        "包括",
        "包含",
        "介绍",
        "说明",
        "讲了",
        "是多少",
        "是什么",
    )
    document_query_verbs = (
        "查",
        "查询",
        "找",
        "检索",
        "帮我看",
        "告诉我",
        "解释",
        "说明",
        "介绍",
        "总结",
        "概括",
    )
    if any(term in text for term in knowledge_terms) and (
        any(term in text for term in question_terms) or any(verb in text for verb in document_query_verbs)
    ):
        return True
    if any(term in text for term in ("制度", "办法", "规定", "规则", "标准", "条款", "要求")):
        return True
    return False


def is_external_api_query(text: str) -> bool:
    return bool(re.search(
        r"(黄金|金价|Au9999|AU9999|天气|气温|下雨|空气质量|汇率|美元|人民币|股票|股价|指数|行情|新闻|热搜|今天|现在|实时)",
        text,
        re.IGNORECASE,
    ))


def is_customer_aum_query(text: str, entities: IntentEntities) -> bool:
    if re.search(r"AUM", text, re.IGNORECASE):
        return True
    has_customer_anchor = bool(entities.customerName) or bool(re.search(r"(客户|客户号|客户编号|名下|他|她|该客户)", text))
    has_asset_metric = bool(re.search(r"(资产|持仓|余额|总AUM|总资产)", text, re.IGNORECASE))
    doc_context = bool(re.search(r"(资产管理|资管|监管指标|总资产.*总负债|信息披露|标准|规则|要求|办法|规定)", text))
    return has_customer_anchor and has_asset_metric and not doc_context


def is_message_send_request(text: str) -> bool:
    has_send_action = bool(re.search(r"(发送|发.{0,3}消息|生成|预览|起草|编辑|写.{0,3}消息)", text))
    has_message_object = bool(re.search(r"(消息|短信|微信|话术|提醒|通知)", text))
    has_customer_anchor = bool(re.search(r"(给|客户|先生|女士|经理|用户)", text))
    return has_send_action and has_message_object and has_customer_anchor


def extract_customer_name(text: str):
    match = re.search(r"(?:客户|给)([\u4e00-\u9fa5]{2,4})", text)
    if match:
        name = match.group(1)
        name = re.sub(r"(发送|发消息|查询|当前|的)$", "", name)
        return name or None
    match = re.search(r"([\u4e00-\u9fa5]{2,4})(?:当前)?(?:的)?AUM", text, re.IGNORECASE)
    return match.group(1) if match else None


def extract_message_purpose(text: str):
    if "到期" in text:
        return "产品到期提醒"
    if "调仓" in text or "再平衡" in text or "资产配置" in text:
        return "资产配置提醒"
    if "提醒" in text or "通知" in text:
        return "客户提醒"
    return None


def template_code_for_purpose(purpose):
    if purpose == "产品到期提醒":
        return "PRODUCT_MATURITY_REMINDER"
    if purpose == "资产配置提醒":
        return "ASSET_REBALANCE_NOTICE"
    if purpose:
        return "CUSTOM_CONTENT"
    return None
