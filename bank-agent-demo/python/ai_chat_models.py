from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class IntentType(str, Enum):
    KNOWLEDGE_QA = "KNOWLEDGE_QA"
    CUSTOMER_AUM_QUERY = "CUSTOMER_AUM_QUERY"
    EXTERNAL_API_QUERY = "EXTERNAL_API_QUERY"
    MESSAGE_SEND = "MESSAGE_SEND"
    GENERAL_CHAT = "GENERAL_CHAT"
    UNKNOWN = "UNKNOWN"


class IntentEntities(BaseModel):
    customerName: Optional[str] = None
    customerId: Optional[str] = None
    templateCode: Optional[str] = None
    messagePurpose: Optional[str] = None


class IntentResult(BaseModel):
    intent: IntentType
    confidence: float = Field(ge=0.0, le=1.0)
    entities: IntentEntities = Field(default_factory=IntentEntities)
    reason: str = ""


class HistoryMessage(BaseModel):
    role: str
    content: str


class AiChatRequest(BaseModel):
    traceId: str
    sessionId: str
    message: str
    history: List[HistoryMessage] = Field(default_factory=list)
    requestedSkill: Optional[str] = None
    forceSkill: bool = False
    routerIntent: Optional[str] = None
    routerConfidence: Optional[float] = None
    entities: Dict[str, Any] = Field(default_factory=dict)


class Citation(BaseModel):
    source: str
    title: Optional[str] = None


class SkillCall(BaseModel):
    skill: str
    status: str
    durationMs: int


class SkillRequest(BaseModel):
    trace_id: str
    session_id: str
    user_message: str
    intent: IntentType
    entities: IntentEntities
    history: List[HistoryMessage] = Field(default_factory=list)


class SkillResult(BaseModel):
    success: bool
    answer: Optional[str] = None
    data: Dict[str, Any] = Field(default_factory=dict)
    citations: List[Citation] = Field(default_factory=list)
    requires_confirmation: bool = False
    confirmation: Optional[Dict[str, Any]] = None
    error_code: Optional[str] = None
    error_message: Optional[str] = None


class AiChatError(BaseModel):
    code: str
    message: str


class AiChatResponse(BaseModel):
    traceId: str
    sessionId: str
    intent: IntentType
    confidence: float
    answer: str
    data: Dict[str, Any] = Field(default_factory=dict)
    citations: List[Citation] = Field(default_factory=list)
    sources: List[str] = Field(default_factory=list)
    requiresConfirmation: bool = False
    confirmation: Optional[Dict[str, Any]] = None
    skillCalls: List[SkillCall] = Field(default_factory=list)
    error: Optional[AiChatError] = None
