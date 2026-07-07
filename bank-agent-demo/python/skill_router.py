import logging
import time
from typing import Dict

from ai_chat_models import IntentResult, IntentType, SkillCall, SkillRequest, SkillResult
from skill_handlers import ClarificationSkill, SkillHandler

logger = logging.getLogger(__name__)


class SkillRouter:
    def __init__(self, handlers: Dict[IntentType, SkillHandler]):
        self.handlers = handlers
        self.fallback = ClarificationSkill()

    def route(self, request: SkillRequest):
        handler = self.handlers.get(request.intent, self.handlers.get(request.intent.value, self.fallback))
        start = time.time()
        status = "SUCCESS"
        try:
            result = handler.handle(request)
            if not result.success:
                status = "ERROR"
            return result, SkillCall(skill=handler.skill_name, status=status, durationMs=int((time.time() - start) * 1000))
        except Exception as exc:
            status = "ERROR"
            logger.exception("skill handler failed: %s", handler.skill_name)
            result = SkillResult(
                success=False,
                answer="服务暂时不可用，请稍后再试。",
                error_code="SKILL_HANDLER_ERROR",
                error_message=type(exc).__name__,
            )
            return result, SkillCall(skill=handler.skill_name, status=status, durationMs=int((time.time() - start) * 1000))
