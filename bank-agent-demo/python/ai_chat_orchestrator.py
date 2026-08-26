import time
from datetime import datetime
from typing import Optional
from zoneinfo import ZoneInfo

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

    def __init__(self, intent_service, rag_service, external_search_client, answer_llm, now_provider=None):
        self._intent_service = intent_service
        self._rag_service = rag_service
        self._external_search = external_search_client
        self._answer_llm = answer_llm
        self._now_provider = now_provider or (lambda: datetime.now(ZoneInfo("Asia/Shanghai")))

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
        current_date = self._now_provider().strftime("%Y-%m-%d")
        internal_context = ""
        external_context = ""
        citations = []
        calls = []
        errors = []
        retrieval_evidence = []
        web_evidence = []

        if IntentType.KNOWLEDGE_QA in selected:
            call_started = time.perf_counter()
            try:
                retrieval = self._rag_service.retrieve(query)
                internal_context = retrieval.context
                retrieval_evidence = getattr(retrieval, "evidence", None) or []
                citations = [Citation(source=source, title=source) for source in retrieval.sources]
            except Exception as exc:
                errors.append(f"RAG_ERROR:{type(exc).__name__}")
            calls.append(self._call("knowledge-rag-retrieval", call_started, not errors))

        if IntentType.EXTERNAL_API_QUERY in selected:
            call_started = time.perf_counter()
            try:
                if self._external_search is None:
                    raise RuntimeError("external search is not configured")
                dated_query = f"{query}\n当前日期（北京时间）：{current_date}"
                if hasattr(self._external_search, "search_with_sources"):
                    search_result = self._external_search.search_with_sources(dated_query)
                    external_context = search_result.context
                    web_evidence = [
                        {
                            "rank": index,
                            "title": source.title,
                            "url": source.url,
                            "snippet": source.snippet[:360],
                            "date": source.date,
                        }
                        for index, source in enumerate(search_result.sources, start=1)
                    ]
                    citations.extend(
                        Citation(source=source.url, title=source.title, type="WEB", url=source.url)
                        for source in search_result.sources
                    )
                else:
                    external_context = self._external_search.search_text(dated_query)
                ok = True
            except Exception as exc:
                errors.append(f"EXTERNAL_SEARCH_ERROR:{type(exc).__name__}")
                ok = False
            calls.append(self._call("external-search", call_started, ok))

        answer_started = time.perf_counter()
        answer = self._answer(payload, query, internal_context, external_context, current_date)
        answer = self._append_internal_sources(answer, citations)
        calls.append(self._call("unified-answer-llm", answer_started, True))
        total_duration_ms = max(0, int((time.perf_counter() - started) * 1000))
        route = self._route_name(selected)
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
            "executionTrace": {
                "route": route,
                "query": query,
                "retrieval": retrieval_evidence,
                "webResults": web_evidence,
                "citations": [item.model_dump() for item in citations],
                "timing": {
                    "totalMs": total_duration_ms,
                    "stages": [item.model_dump() for item in calls],
                },
            },
        }
        error = AiChatError(code="PARTIAL_EVIDENCE_ERROR", message="; ".join(errors)) if errors else None
        return AiChatResponse(
            traceId=payload.traceId, sessionId=payload.sessionId, intent=intent.intent,
            confidence=intent.confidence, answer=answer, data=data, citations=citations,
            sources=[item.source for item in citations], skillCalls=calls, error=error,
        )

    def _answer(self, payload, query, internal_context, external_context, current_date):
        history = "\n".join(f"{item.role}: {item.content}" for item in payload.history[-8:]) or "（无）"
        prompt = f"""你是银行客户经理智能助手。请统一整理证据并直接回答用户，不要暴露路由或节点名。
内部文档是行内依据；外部搜索是公开信息，涉及时间敏感内容要提示时效。证据不足时明确说明，禁止编造。
若两类证据都有，综合回答并清楚区分内部规定与外部信息。回答简洁、自然，使用必要的 Markdown。
当前日期（北京时间）：{current_date}。不得把当前日期之后的内容描述成已经发生；搜索结果只有月日而没有年份时，不得擅自推断为今年。
外部证据按 WEB 编号提供。引用外部事实时，必须紧跟一个可点击的 Markdown 链接，格式为 [↗](对应URL)；只能使用证据中真实出现的URL。
不要在回答末尾另列外部来源清单。不要自行输出行内来源清单，系统会统一追加行内文件来源。

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
    def _append_internal_sources(answer, citations):
        files = list(dict.fromkeys(
            item.source for item in citations if item.type == "INTERNAL" and item.source
        ))
        if not files:
            return answer
        source_lines = "\n".join(f"- `{source}`" for source in files)
        return f"{answer.rstrip()}\n\n---\n\n📚 **行内来源**\n\n{source_lines}"

    @staticmethod
    def _call(name, started, success):
        return SkillCall(skill=name, status="SUCCESS" if success else "ERROR",
                         durationMs=max(0, int((time.perf_counter() - started) * 1000)))

    @staticmethod
    def _route_name(selected):
        has_rag = IntentType.KNOWLEDGE_QA in selected
        has_web = IntentType.EXTERNAL_API_QUERY in selected
        if has_rag and has_web:
            return "RAG + WEB"
        if has_rag:
            return "RAG"
        if has_web:
            return "WEB SEARCH"
        return "LLM DIRECT"

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
