import json
import re

from ai_chat_models import IntentEntities, IntentResult, IntentType
from env_config import env_float


SYSTEM_PROMPT = """
你是银行客户经理智能助手的意图识别模块。
只允许从固定枚举中选择 intent：
KNOWLEDGE_QA, CUSTOMER_AUM_QUERY, EXTERNAL_API_QUERY, MESSAGE_SEND, GENERAL_CHAT, UNKNOWN。

区分规则：
- KNOWLEDGE_QA：用户在问银行内部文档、制度材料、产品说明、业务规则、操作口径、术语定义、准入条件、监管法规、合规要求、客户分层标准等知识性问题，需要 RAG 回答。即使用户没有明确说“查文档”，只要是一个可从知识库材料中回答的业务问题，也归为 KNOWLEDGE_QA。
- CUSTOMER_AUM_QUERY：查询某个具体客户的资产、AUM、持仓汇总等业务数据，需要客户姓名或编号。
- EXTERNAL_API_QUERY：查询外部实时信息，例如黄金价格、天气、汇率、股票、新闻等。
- MESSAGE_SEND：要求给客户生成、预览或发送消息、提醒、通知。本阶段只做预览。
- GENERAL_CHAT：问候、介绍助手能力、一般投资知识或风险说明等由兜底大模型直接回答的普通对话。
- UNKNOWN：信息不足或无法判断，例如“帮我查一下”“这个呢”“查一下资料”。

示例：
- “反洗钱法中，临时冻结的最长时限是48小时吗？” -> KNOWLEDGE_QA
- “银行保险机构资产管理产品信息披露有什么要求？” -> KNOWLEDGE_QA
- “金葵花客户达标标准是什么？” -> KNOWLEDGE_QA
- “招行的客户等级是怎么样的” -> KNOWLEDGE_QA（银行名 + 客户等级规则，不是查询某个客户）
- “客户等级规则是什么” -> KNOWLEDGE_QA（规则/制度类问题）
- “客户张伟等级是多少” -> CUSTOMER_AUM_QUERY（具体客户实体 + 客户数据）
- “帮我查一下客户等级” -> UNKNOWN（无法确定是查询某位客户的等级，还是查询客户等级规则；customerName必须为空，候选为CUSTOMER_AUM_QUERY和KNOWLEDGE_QA）
- “普惠金融走进千企万户活动讲了什么？” -> KNOWLEDGE_QA
- “商业银行主要监管指标里资本充足率是多少？” -> KNOWLEDGE_QA
- “查询客户张伟当前AUM” -> CUSTOMER_AUM_QUERY
- “黄金价格是多少” -> EXTERNAL_API_QUERY
- “黄金投资有什么风险” -> GENERAL_CHAT（走兜底大模型，不调用知识库或实时外部接口）
- “给张伟生成产品到期提醒” -> MESSAGE_SEND
- 当前正在发消息时，用户回复“产品到期提醒” -> MESSAGE_SEND（补充消息用途）

要求：
1. 不要生成函数名、URL 或路由节点名称。
2. confidence 必须是 0 到 1 的数字。
3. entities 只填能从用户话语明确抽取到的字段；禁止把“等级、资产、信息、分类、分层、余额、持仓、规则”等业务词当作客户姓名。
4. reason 用一句简短分类依据，不要输出推理链。
5. 如果辅助路由信息 routerIntent 置信度较高，优先参考它；除非用户原文和实体强烈矛盾。
6. 参数缺失时填写 missingSlots。只有信息不足、无法判断用户究竟要做什么时才选择 UNKNOWN。
7. 一句话包含多个明确且可独立执行的意图时，不要返回 UNKNOWN，也不要同时执行多个技能；比较各意图置信度，只返回置信度最高的一个，并在 ambiguities 简短记录被忽略的次要意图。
"""

try:
    from langchain_core.prompts import ChatPromptTemplate

    PROMPT = ChatPromptTemplate.from_messages([
        ("system", SYSTEM_PROMPT),
        ("human", "用户输入：{user_input}\n\n辅助路由信息：\n{router_context}"),
    ])
except Exception:
    PROMPT = None

# Phase-1 conversational routing deliberately has only three executable routes.
# AUM and message operations are not part of this pipeline; uncertain requests
# go to GENERAL_CHAT so the answer model can respond normally.
CONVERSATION_INTENT_PROMPT = """
你是银行客户经理助手的轻量意图分类器。结合最近对话，判断当前用户问题需要哪些信息来源。
只允许选择以下意图：
- KNOWLEDGE_QA：银行内部制度、产品、客户分层标准、业务办理流程、SOP、合规要求等，需要查内部知识库。
- EXTERNAL_API_QUERY：当前行情、新闻、天气、汇率、公开网络资料等，需要联网搜索。
- GENERAL_CHAT：无需检索即可回答，或无法可靠判断时的兜底。

可以同时选择 KNOWLEDGE_QA 和 EXTERNAL_API_QUERY；这表示答案需要同时综合内部文档和外部信息。
只有当用户明确表达了多个互斥目标、必须让用户二选一时，才把 intent 设为 UNKNOWN，
并把选项写入 candidateIntents。普通的模糊问题不要 UNKNOWN，使用 GENERAL_CHAT。
selectedIntents 写入所有要执行的意图；intent 写主意图；rewrittenQuery 把指代结合上下文改写成独立可检索问题。
不要因为出现“客户”“资产”“发送”等词识别成客户数据查询或消息操作，本阶段没有这些路由。
reason 只写一句简短依据，不输出推理过程。
"""

try:
    from langchain_core.prompts import ChatPromptTemplate

    PROMPT = ChatPromptTemplate.from_messages([
        ("system", CONVERSATION_INTENT_PROMPT),
        ("human", "最近对话：\n{history}\n\n当前用户输入：{user_input}"),
    ])
except Exception:
    PROMPT = None


class IntentRecognitionService:
    def __init__(self, llm=None, threshold=None):
        self.llm = llm
        self.threshold = float(threshold) if threshold is not None else env_float("INTENT_CONFIDENCE_THRESHOLD")

    def recognize(self, user_input: str, router_intent=None, router_confidence=None, entities=None,
                  dialog_act=None, skill_examples=None, history=None) -> IntentResult:
        if not user_input or not user_input.strip():
            return self._unknown("empty input")

        router_context = build_router_context(router_intent, router_confidence, entities, dialog_act, skill_examples)
        history_text = "\n".join(
            f"{getattr(message, 'role', '')}: {getattr(message, 'content', '')}"
            for message in (history or [])[-8:]
        ) or "（无）"
        try:
            if self.llm is None or PROMPT is None:
                result = fallback_intent(user_input, router_intent=router_intent,
                                         router_confidence=router_confidence, router_entities=entities,
                                         dialog_act=dialog_act, skill_examples=skill_examples)
            else:
                chain = PROMPT | self.llm.with_structured_output(IntentResult)
                result = chain.invoke({"user_input": user_input, "router_context": router_context,
                                       "history": history_text})
                if isinstance(result, dict):
                    result = IntentResult(**result)
        except Exception as exc:
            result = fallback_intent(user_input, reason=f"model unavailable: {type(exc).__name__}",
                                     router_intent=router_intent, router_confidence=router_confidence,
                                     router_entities=entities, dialog_act=dialog_act, skill_examples=skill_examples)

        if not result.selectedIntents:
            result.selectedIntents = [result.intent]
        allowed = {IntentType.KNOWLEDGE_QA, IntentType.EXTERNAL_API_QUERY, IntentType.GENERAL_CHAT, IntentType.UNKNOWN}
        result.selectedIntents = [item for item in result.selectedIntents if item in allowed]
        if result.intent not in allowed:
            result.intent = IntentType.GENERAL_CHAT
            result.selectedIntents = [IntentType.GENERAL_CHAT]
            result.reason = "unsupported operation routed to conversational fallback"
        result.rewrittenQuery = (result.rewrittenQuery or user_input).strip()

        # Router metadata is intentionally ignored for normal typed input. A
        # genuine page-button override is applied by AiChatOrchestrator only.
        local_result = fallback_intent(user_input, reason="local semantic fallback",
                                       router_intent=router_intent, router_confidence=router_confidence,
                                       router_entities=entities, dialog_act=dialog_act, skill_examples=skill_examples)
        if local_result.intent in (IntentType.CUSTOMER_AUM_QUERY, IntentType.MESSAGE_SEND, IntentType.UNKNOWN):
            local_result.intent = IntentType.GENERAL_CHAT
            local_result.selectedIntents = [IntentType.GENERAL_CHAT]
            local_result.rewrittenQuery = user_input
        if self.llm is None and result.intent in (IntentType.CUSTOMER_AUM_QUERY, IntentType.MESSAGE_SEND):
            result.intent = IntentType.GENERAL_CHAT
            result.selectedIntents = [IntentType.GENERAL_CHAT]
            result.reason = "operation modules disabled; use conversational fallback"

        if result.confidence < self.threshold:
            if self.llm is None and local_result.confidence >= self.threshold:
                return local_result
            return IntentResult(
                intent=IntentType.GENERAL_CHAT,
                confidence=result.confidence,
                entities=result.entities,
                selectedIntents=[IntentType.GENERAL_CHAT],
                rewrittenQuery=user_input,
                ambiguities=result.ambiguities,
                reason="low confidence routed to conversational fallback",
            )
        return result

    def _unknown(self, reason: str) -> IntentResult:
        return IntentResult(
            intent=IntentType.UNKNOWN,
            confidence=0.0,
            entities=IntentEntities(),
            reason=reason,
        )


def fallback_intent(user_input: str, reason: str = "fallback rules", router_intent=None,
                    router_confidence=None, router_entities=None, dialog_act=None, skill_examples=None) -> IntentResult:
    text = user_input.strip()
    entities = IntentEntities(customerName=extract_customer_name(text), customerId=extract_customer_id(text))
    entities = merge_router_entities(entities, router_entities)

    router_result = router_prior(router_intent, router_confidence, entities, dialog_act)
    if router_result is not None:
        return router_result
    example_result = example_prior(text, entities, skill_examples)
    if example_result is not None:
        return example_result

    ambiguity = ambiguous_business_query(text, entities)
    if ambiguity is not None:
        return IntentResult(
            intent=IntentType.UNKNOWN,
            confidence=0.45,
            entities=entities,
            candidateIntents=ambiguity,
            ambiguities=["需要确认是查询业务规则还是具体客户数据"],
            reason="ambiguous business query",
        )

    if is_underspecified_query(text):
        return IntentResult(
            intent=IntentType.UNKNOWN,
            confidence=0.35,
            entities=entities,
            ambiguities=["缺少要查询的主题或对象"],
            reason="underspecified query",
        )

    candidates = []
    if is_general_model_fallback_query(text):
        candidates.append(IntentResult(
            intent=IntentType.GENERAL_CHAT,
            confidence=0.90,
            entities=entities,
            reason="general model fallback policy",
        ))
    if is_message_send_request(text):
        entities.messagePurpose = extract_message_purpose(text)
        entities.templateCode = template_code_for_purpose(entities.messagePurpose)
        candidates.append(IntentResult(
            intent=IntentType.MESSAGE_SEND,
            confidence=0.92 if (entities.customerName or entities.customerId) and entities.messagePurpose else 0.82,
            entities=entities,
            missingSlots=[] if (entities.customerName or entities.customerId) else ["customerNameOrId"],
            reason=reason,
        ))
    if is_customer_aum_query(text, entities):
        candidates.append(IntentResult(
            intent=IntentType.CUSTOMER_AUM_QUERY,
            confidence=0.90 if (entities.customerName or entities.customerId) else 0.72,
            entities=entities,
            missingSlots=[] if (entities.customerName or entities.customerId) else ["customerNameOrId"],
            reason=reason,
        ))
    if is_external_api_query(text):
        candidates.append(IntentResult(intent=IntentType.EXTERNAL_API_QUERY, confidence=0.88, entities=entities, reason=reason))
    if is_knowledge_qa_candidate(text):
        candidates.append(IntentResult(intent=IntentType.KNOWLEDGE_QA, confidence=0.86, entities=entities, reason=reason))
    if re.search(r"(你好|您好|介绍|你是谁|你能做什么)", text):
        candidates.append(IntentResult(intent=IntentType.GENERAL_CHAT, confidence=0.82, entities=entities, reason=reason))
    if candidates:
        selected = max(candidates, key=lambda item: item.confidence)
        competing = [item.intent for item in candidates if item.intent != selected.intent]
        if competing:
            selected.ambiguities = [
                "compound request; selected highest-confidence intent and ignored: "
                + ", ".join(intent.value for intent in competing)
            ]
        return selected
    return IntentResult(intent=IntentType.GENERAL_CHAT, confidence=0.5, entities=entities, reason="general chat fallback")


def ambiguous_business_query(text: str, entities: IntentEntities):
    if entities.customerName or entities.customerId:
        return None
    if re.fullmatch(r"(?:帮我)?(?:查|查询|看)(?:一下)?(?:客户)?(?:等级|信息|分类|分层|资产|持仓|余额)", text):
        return [IntentType.KNOWLEDGE_QA, IntentType.CUSTOMER_AUM_QUERY]
    return None


def is_underspecified_query(text: str) -> bool:
    patterns = (
        r"^(?:帮我)?查(?:一下)?$",
        r"^(?:帮我)?看(?:一下)?$",
        r"^查(?:一下)?(?:资料|文档|知识库)$",
        r"^(?:这个|那个|它|这个呢|那个呢)$",
        r"^我想了解一下$",
    )
    return any(re.fullmatch(pattern, text) for pattern in patterns)


def build_router_context(router_intent=None, router_confidence=None, entities=None, dialog_act=None, skill_examples=None) -> str:
    payload = {
        "routerIntent": router_intent,
        "routerConfidence": router_confidence,
        "entities": entities or {},
        "dialogAct": dialog_act,
        "skillExamples": (skill_examples or {}).get("examples", [])[:12] if isinstance(skill_examples, dict) else [],
        "usage": "这些是 Java IntentRouter/NER/DST 给出的辅助特征，不是用户原文；高置信时优先参考。",
    }
    return json.dumps(payload, ensure_ascii=False)


def router_prior(router_intent=None, router_confidence=None, entities=None, dialog_act=None):
    intent = normalize_router_intent(router_intent)
    confidence = float(router_confidence or 0.0)
    if intent is None:
        return None
    if confidence >= 0.85 or dialog_act in ("FRONTEND_REQUESTED_SKILL", "ROUTER_SWITCH_INTENT"):
        return IntentResult(
            intent=intent,
            confidence=max(confidence, 0.85),
            entities=entities or IntentEntities(),
            reason="router prior",
        )
    return None


def apply_router_prior(result: IntentResult, router_intent=None, router_confidence=None,
                       router_entities=None, dialog_act=None) -> IntentResult:
    entities = merge_router_entities(result.entities, router_entities)
    result.entities = entities
    prior = router_prior(router_intent, router_confidence, entities, dialog_act)
    if prior is None:
        return result
    if result.intent == prior.intent:
        result.confidence = max(result.confidence, prior.confidence)
        result.reason = result.reason or prior.reason
        return result
    if prior.confidence >= 0.9 or result.intent == IntentType.UNKNOWN or result.confidence < 0.75:
        return prior
    return result


def apply_example_prior(result: IntentResult, user_input: str, router_entities=None, skill_examples=None) -> IntentResult:
    prior = example_prior(user_input, merge_router_entities(result.entities, router_entities), skill_examples)
    if prior is None:
        return result
    if result.intent == prior.intent:
        result.confidence = max(result.confidence, prior.confidence)
        return result
    if prior.confidence >= 0.86 and result.confidence < 0.82:
        return prior
    return result


def example_prior(user_input: str, entities: IntentEntities, skill_examples=None):
    examples = []
    if isinstance(skill_examples, dict):
        examples = skill_examples.get("examples") or []
    if not examples:
        return None
    normalized_input = normalize_text(user_input)
    best_example = None
    best_score = 0.0
    for example in examples:
        if not isinstance(example, dict):
            continue
        score = text_similarity(normalized_input, normalize_text(str(example.get("text") or "")))
        score = score * float(example.get("confidence") or 0.0)
        if score > best_score:
            best_score = score
            best_example = example
    if best_example is None or best_score < 0.72:
        return None
    intent = normalize_router_intent(best_example.get("skillCode"))
    if intent is None:
        return None
    return IntentResult(intent=intent, confidence=min(max(best_score, 0.72), 0.98), entities=entities, reason="configured example prior")


def normalize_router_intent(router_intent):
    if not router_intent:
        return None
    value = str(router_intent).strip().upper()
    mapping = {
        "RAG_QUERY": IntentType.KNOWLEDGE_QA,
        "RULE_QUERY": IntentType.KNOWLEDGE_QA,
        "KNOWLEDGE_QA": IntentType.KNOWLEDGE_QA,
        "CUSTOMER_AUM": IntentType.CUSTOMER_AUM_QUERY,
        "CUSTOMER_AUM_QUERY": IntentType.CUSTOMER_AUM_QUERY,
        "AUM": IntentType.CUSTOMER_AUM_QUERY,
        "GOLD_PRICE": IntentType.EXTERNAL_API_QUERY,
        "GOLD": IntentType.EXTERNAL_API_QUERY,
        "EXTERNAL_API_QUERY": IntentType.EXTERNAL_API_QUERY,
        "MESSAGE_SEND": IntentType.MESSAGE_SEND,
        "MESSAGE": IntentType.MESSAGE_SEND,
        "GENERAL_CHAT": IntentType.GENERAL_CHAT,
        "UNKNOWN": IntentType.UNKNOWN,
    }
    return mapping.get(value)


def merge_router_entities(entities: IntentEntities, router_entities=None) -> IntentEntities:
    if not router_entities:
        return entities
    if not entities.customerName:
        customer_names = router_entities.get("customerNames") if isinstance(router_entities, dict) else None
        if isinstance(customer_names, list) and customer_names:
            entities.customerName = str(customer_names[0])
    if not entities.customerId:
        customer_ids = router_entities.get("customerIds") if isinstance(router_entities, dict) else None
        if isinstance(customer_ids, list) and customer_ids:
            entities.customerId = str(customer_ids[0])
    if not entities.bankName:
        bank_names = router_entities.get("bankNames") if isinstance(router_entities, dict) else None
        if isinstance(bank_names, list) and bank_names:
            entities.bankName = str(bank_names[0])
    if not entities.productName:
        product_names = router_entities.get("productNames") if isinstance(router_entities, dict) else None
        if isinstance(product_names, list) and product_names:
            entities.productName = str(product_names[0])
    if not entities.businessTerm:
        business_terms = router_entities.get("businessTerms") if isinstance(router_entities, dict) else None
        if isinstance(business_terms, list) and business_terms:
            entities.businessTerm = str(business_terms[0])
    if not entities.marketSymbol:
        market_terms = router_entities.get("marketTerms") if isinstance(router_entities, dict) else None
        if isinstance(market_terms, list) and market_terms:
            entities.marketSymbol = str(market_terms[0])
    return entities


def normalize_text(value: str) -> str:
    return re.sub(r"[\s，。？！?！、,.]", "", value or "").lower()


def text_similarity(input_text: str, example_text: str) -> float:
    if not input_text or not example_text:
        return 0.0
    if input_text == example_text:
        return 1.0
    if input_text in example_text or example_text in input_text:
        return max(0.8, min(len(input_text), len(example_text)) / max(len(input_text), len(example_text)))
    overlap = sum(1 for char in input_text if char in example_text)
    return overlap / max(len(input_text), len(example_text))


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
    if re.search(r"(风险|规定|规则|制度|办法|要求|适合|建议|原理|知识)", text):
        return False
    return bool(re.search(
        r"(黄金|金价|Au9999|AU9999|天气|气温|下雨|空气质量|汇率|美元|人民币|股票|股价|指数|行情|新闻|热搜|今天|现在|实时)",
        text,
        re.IGNORECASE,
    ))


def is_general_model_fallback_query(text: str) -> bool:
    return bool(re.search(
        r"(黄金|基金|股票|理财|投资).*(风险|适合|建议|原理|基础知识|怎么看)",
        text,
    ))


def is_customer_aum_query(text: str, entities: IntentEntities) -> bool:
    if re.search(r"AUM", text, re.IGNORECASE):
        return True
    has_customer_anchor = bool(entities.customerName) or bool(re.search(r"(客户|客户号|客户编号|名下|他|她|该客户)", text))
    has_asset_metric = bool(re.search(r"(资产|持仓|余额|总AUM|总资产)", text, re.IGNORECASE))
    doc_context = bool(re.search(r"(资产管理|资管|监管指标|总资产.*总负债|信息披露|标准|规则|要求|办法|规定)", text))
    return has_customer_anchor and has_asset_metric and not doc_context


def is_message_send_request(text: str) -> bool:
    has_send_action = bool(re.search(r"(发送|发.{0,3}(?:消息|短信|微信|提醒|通知)|生成|预览|起草|编辑|写.{0,3}消息)", text))
    has_message_object = bool(re.search(r"(消息|短信|微信|话术|提醒|通知)", text))
    has_customer_anchor = bool(re.search(r"(给|客户|先生|女士|经理|用户)", text))
    return has_send_action and has_message_object and has_customer_anchor


def extract_customer_name(text: str):
    reserved = {"等级", "资产", "信息", "分类", "分层", "余额", "持仓", "规则", "制度", "风险", "消息", "通知", "提醒"}
    patterns = (
        r"客户(?:姓名|名)?[：:]?([\u4e00-\u9fa5]{2,4}?)(?=的|当前|AUM|aum|资产|持仓|余额|等级|$)",
        r"(?:查询|查一下|查|给)([\u4e00-\u9fa5]{2,4}?)(?=的|当前|AUM|aum|发|发送|通知|提醒)",
        r"([\u4e00-\u9fa5]{2,4})(?:当前)?(?:的)?AUM",
    )
    for pattern in patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if not match:
            continue
        name = match.group(1)
        contains_action = bool(re.search(r"(编辑|生成|发送|起草|查询|查一下|预览)", name))
        if not contains_action and name not in reserved and not any(name.endswith(word) for word in reserved):
            return name
    return None


def extract_customer_id(text: str):
    match = re.search(r"(?<![A-Za-z0-9])(CUST\d+)(?![A-Za-z0-9])", text, re.IGNORECASE)
    return match.group(1).upper() if match else None


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
