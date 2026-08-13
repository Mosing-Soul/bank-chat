from typing import List

from pydantic import BaseModel, Field

from ai_chat_models import HistoryMessage


class QueryRequest(BaseModel):
    question: str
    session_id: str
    history: List[HistoryMessage] = Field(default_factory=list)


class QueryResponse(BaseModel):
    answer: str
    sources: List[str] = Field(default_factory=list)
