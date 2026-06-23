from pydantic import BaseModel
from enum import Enum

class IntentType(str, Enum):
    KNOWLEDGE_QA = "knowledge_qa"      # 知识问答：查规则、查定义、查法规
    PRICE_QUERY = "price_query"        # 行情查询：金价、净值、涨跌
    CHITCHAT = "chitchat"              # 闲聊：问候、无关话题、无法归类

class IntentResult(BaseModel):
    intent: IntentType
    confidence: str                    # high / medium / low
    reason: str                        # 一句话说明判断依据，用于日志和调试