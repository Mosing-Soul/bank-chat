from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class IntentType(str, Enum):
    KNOWLEDGE_QA = "KNOWLEDGE_QA"
    EXTERNAL_API_QUERY = "EXTERNAL_API_QUERY"
    GENERAL_CHAT = "GENERAL_CHAT"
    UNKNOWN = "UNKNOWN"


class HistoryMessage(BaseModel):
    role: str
    content: str


class IntentEntities(BaseModel):
    bankName: Optional[str] = None
    productName: Optional[str] = None
    businessTerm: Optional[str] = None
    marketSymbol: Optional[str] = None


class IntentResult(BaseModel):
    intent: IntentType
    confidence: float = Field(default=0.8, ge=0.0, le=1.0)
    selectedIntents: List[IntentType] = Field(default_factory=list)
    rewrittenQuery: Optional[str] = None
    entities: IntentEntities = Field(default_factory=IntentEntities)
    missingSlots: List[str] = Field(default_factory=list)
    candidateIntents: List[IntentType] = Field(default_factory=list)
    ambiguities: List[str] = Field(default_factory=list)
    reason: str = ""


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
    dialogAct: Optional[str] = None
    skillExamples: Dict[str, Any] = Field(default_factory=dict)


class Citation(BaseModel):
    source: str
    title: Optional[str] = None
    type: str = "INTERNAL"
    url: Optional[str] = None


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
