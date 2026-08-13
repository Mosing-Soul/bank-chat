from typing import Optional

from ai_chat_models import (
    AiChatError,
    AiChatRequest,
    AiChatResponse,
    IntentType,
    SkillRequest,
)
from skill_handlers import (
    extract_pending_operation_id,
    has_open_message_flow,
    is_cancel_message,
    is_confirm_message,
    is_revision_message,
)


DEFAULT_CLARIFICATION = "请说明您想查询产品规则、客户资产、黄金行情，还是需要生成客户消息。"


def forced_intent_from_skill(skill: Optional[str]) -> Optional[IntentType]:
    if not skill:
        return None
    mapping = {
        "CUSTOMER_AUM": IntentType.CUSTOMER_AUM_QUERY,
        "AUM": IntentType.CUSTOMER_AUM_QUERY,
        "GOLD_PRICE": IntentType.EXTERNAL_API_QUERY,
        "GOLD": IntentType.EXTERNAL_API_QUERY,
        "RAG_QUERY": IntentType.KNOWLEDGE_QA,
        "RULE_QUERY": IntentType.KNOWLEDGE_QA,
        "MESSAGE_SEND": IntentType.MESSAGE_SEND,
        "MESSAGE": IntentType.MESSAGE_SEND,
    }
    return mapping.get(skill.strip().upper())


class AiChatOrchestrator:
    """Coordinates intent policy and skill execution without HTTP concerns."""

    def __init__(self, intent_service, skill_router):
        self._intent_service = intent_service
        self._skill_router = skill_router

    def invoke(self, payload: AiChatRequest) -> AiChatResponse:
        intent = self._intent_service.recognize(
            payload.message,
            router_intent=payload.routerIntent or payload.requestedSkill,
            router_confidence=payload.routerConfidence,
            entities=payload.entities,
            dialog_act=payload.dialogAct,
            skill_examples=payload.skillExamples,
        )
        forced_intent = forced_intent_from_skill(payload.requestedSkill) if payload.forceSkill else None
        effective_history = [] if forced_intent else payload.history
        if forced_intent:
            intent.intent = forced_intent
            intent.confidence = 0.98
            intent.reason = "forced by requestedSkill"
        elif self._continues_message_flow(payload):
            intent.intent = IntentType.MESSAGE_SEND
            intent.confidence = max(intent.confidence, 0.95)
            intent.reason = "pending message confirmation flow"

        skill_request = SkillRequest(
            trace_id=payload.traceId,
            session_id=payload.sessionId,
            user_message=payload.message,
            intent=intent.intent,
            entities=intent.entities,
            history=effective_history,
        )
        result, call = self._skill_router.route(skill_request)
        error = None
        if not result.success:
            error = AiChatError(
                code=result.error_code or "SKILL_ERROR",
                message=result.error_message or "skill failed",
            )
        response_data = dict(result.data)
        response_data["intentAnalysis"] = {
            "missingSlots": intent.missingSlots,
            "candidateIntents": [candidate.value for candidate in intent.candidateIntents],
            "ambiguities": intent.ambiguities,
        }
        return AiChatResponse(
            traceId=payload.traceId,
            sessionId=payload.sessionId,
            intent=intent.intent,
            confidence=intent.confidence,
            answer=result.answer or DEFAULT_CLARIFICATION,
            data=response_data,
            citations=result.citations,
            sources=[citation.source for citation in result.citations],
            requiresConfirmation=result.requires_confirmation,
            confirmation=result.confirmation,
            skillCalls=[call],
            error=error,
        )

    @staticmethod
    def _continues_message_flow(payload: AiChatRequest) -> bool:
        pending_confirmation = extract_pending_operation_id(payload.history) and (
            is_confirm_message(payload.message)
            or is_cancel_message(payload.message)
            or is_revision_message(payload.message)
        )
        return bool(pending_confirmation or has_open_message_flow(payload.history))
