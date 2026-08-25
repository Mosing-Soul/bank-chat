import time
from typing import Optional

from ai_chat_models import AiChatError, AiChatRequest, AiChatResponse, Citation, IntentType, SkillCall


def forced_intent_from_skill(skill: Optional[str]) -> Optional[IntentType]:
    if not skill:
        return None
    return {
        "RAG_QUERY": IntentType.KNOWLEDGE_QA,
        "RULE_QUERY": IntentType.KNOWLEDGE_QA,
        "GOLD_PRICE": IntentType.EXTERNAL_API_QUERY,
        "EXTERNAL_SEARCH": IntentType.EXTERNAL_API_QUERY,
        "GENERAL_CHAT": IntentType.GENERAL_CHAT,
    }.get(skill.strip().upper())


class AiChatOrchestrator:
    """Phase-1 conversational pipeline: classify, collect evidence, answer once."""

    def __init__(self, intent_service, rag_service, external_search_client, answer_llm):
        self._intent_service = intent_service
        self._rag_service = rag_service
        self._external_search = external_search_client
        self._answer_llm = answer_llm

    def invoke(self, payload: AiChatRequest) -> AiChatResponse:
        started = time.perf_counter()
        forced_intent = forced_intent_from_skill(payload.requestedSkill) if payload.forceSkill else None
        if forced_intent:
            from ai_chat_models import IntentResult
            intent = IntentResult(intent=forced_intent, selectedIntents=[forced_intent], confidence=0.99,
                                  rewrittenQuery=payload.message, reason="explicit page action")
        else:
            intent = self._intent_service.recognize(payload.message, history=payload.history)

        selected = list(dict.fromkeys(intent.selectedIntents or [intent.intent]))
        if intent.intent == IntentType.UNKNOWN:
            return self._clarification(payload, intent, started)

        query = intent.rewrittenQuery or payload.message
        internal_context = ""
        external_context = ""
        citations = []
        calls = []
        errors = []

        if IntentType.KNOWLEDGE_QA in selected:
            call_started = time.perf_counter()
            try:
                retrieval = self._rag_service.retrieve(query)
                internal_context = retrieval.context
                citations = [Citation(source=source, title=source) for source in retrieval.sources]
            except Exception as exc:
                errors.append(f"RAG_ERROR:{type(exc).__name__}")
            calls.append(self._call("knowledge-rag-retrieval", call_started, not errors))

        if IntentType.EXTERNAL_API_QUERY in selected:
            call_started = time.perf_counter()
            try:
                if self._external_search is None:
                    raise RuntimeError("external search is not configured")
                external_context = self._external_search.search_text(query)
                ok = True
            except Exception as exc:
                errors.append(f"EXTERNAL_SEARCH_ERROR:{type(exc).__name__}")
                ok = False
            calls.append(self._call("external-search", call_started, ok))

        answer = self._answer(payload, query, internal_context, external_context)
        calls.append(self._call("unified-answer-llm", started, True))
        data = {
            "intentAnalysis": {
                "selectedIntents": [item.value for item in selected],
                "candidateIntents": [item.value for item in intent.candidateIntents],
                "rewrittenQuery": query,
                "ambiguities": intent.ambiguities,
                "reason": intent.reason,
            },
            "evidence": {
                "internalContextUsed": bool(internal_context),
                "externalContextUsed": bool(external_context),
            },
        }
        error = AiChatError(code="PARTIAL_EVIDENCE_ERROR", message="; ".join(errors)) if errors else None
        return AiChatResponse(
            traceId=payload.traceId, sessionId=payload.sessionId, intent=intent.intent,
            confidence=intent.confidence, answer=answer, data=data, citations=citations,
            sources=[item.source for item in citations], skillCalls=calls, error=error,
        )

    def _answer(self, payload, query, internal_context, external_context):
        history = "\n".join(f"{item.role}: {item.content}" for item in payload.history[-8:]) or "（无）"
        prompt = f"""你是银行客户经理智能助手。请统一整理证据并直接回答用户，不要暴露路由或节点名。
内部文档是行内依据；外部搜索是公开信息，涉及时间敏感内容要提示时效。证据不足时明确说明，禁止编造。
若两类证据都有，综合回答并清楚区分内部规定与外部信息。回答简洁、自然，使用必要的 Markdown。

最近对话：
{history}

当前问题（已消解指代）：{query}

内部文档证据：
{internal_context or '无'}

外部搜索证据：
{external_context or '无'}

最终回答："""
        if self._answer_llm is None:
            return "当前大模型服务不可用，请稍后再试。"
        try:
            result = self._answer_llm.invoke(prompt)
            return (result.content if hasattr(result, "content") else str(result)).strip()
        except Exception:
            return "当前大模型服务不可用，请稍后再试。"

    @staticmethod
    def _call(name, started, success):
        return SkillCall(skill=name, status="SUCCESS" if success else "ERROR",
                         durationMs=max(0, int((time.perf_counter() - started) * 1000)))

    @staticmethod
    def _clarification(payload, intent, started):
        labels = {
            IntentType.KNOWLEDGE_QA: "查询行内知识库",
            IntentType.EXTERNAL_API_QUERY: "联网搜索公开信息",
            IntentType.GENERAL_CHAT: "直接咨询大模型",
        }
        candidates = intent.candidateIntents or [IntentType.KNOWLEDGE_QA, IntentType.EXTERNAL_API_QUERY]
        options = [{"intent": item.value, "label": labels.get(item, item.value)} for item in candidates]
        return AiChatResponse(
            traceId=payload.traceId, sessionId=payload.sessionId, intent=IntentType.UNKNOWN,
            confidence=intent.confidence, answer="我理解到不止一种可能。请选择你希望我处理的方向。",
            data={"intentAnalysis": {"candidateIntents": [item.value for item in candidates]}},
            requiresConfirmation=True, confirmation={"type": "INTENT_SELECTION", "options": options},
            skillCalls=[AiChatOrchestrator._call("intent-clarification", started, True)],
        )
