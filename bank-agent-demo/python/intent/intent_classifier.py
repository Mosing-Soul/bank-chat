import os
from langchain_core.prompts import ChatPromptTemplate
from intent_schema import IntentResult, IntentType

SYSTEM_PROMPT = """你是银行客户经理助手的意图识别模块。
根据用户输入，判断属于以下三类意图之一：

- knowledge_qa：用户想查询银行内部规则、产品定义、法规条款、客户分级标准等知识性内容
  例如："金葵花客户达标标准是什么" / "反洗钱法对临时冻结的规定" / "公募产品信息披露要求"

- price_query：用户想获取实时或近期金融市场行情数据
  例如："现在金价多少" / "稳健增利180天最新净值" / "黄金近一周走势"

- chitchat：用户在闲聊、打招呼，或问题完全超出银行业务范围，无法归入以上两类
  例如："你好啊" / "今天天气怎么样" / "帮我订机票"

判断规则：
1. 优先判断是否含有明确的行情词汇（金价、净值、涨跌、走势），有则归为price_query
2. 含有规则/定义/法规/标准/条款等词汇，归为knowledge_qa
3. 无法判断或明显与银行业务无关，归为chitchat
4. confidence反映你的判断把握程度：high=明确/medium=基本确定/low=模糊"""

prompt = ChatPromptTemplate.from_messages([
    ("system", SYSTEM_PROMPT),
    ("human", "{user_input}")
])

def classify_intent(llm, user_input: str) -> IntentResult:
    """
    输入用户问题和已初始化的LLM实例，返回结构化意图识别结果
    """
    structured_llm = llm.with_structured_output(IntentResult)
    chain = prompt | structured_llm

    try:
        result = chain.invoke({"user_input": user_input})
        # 确保枚举值正确
        if isinstance(result, dict):
            # 某些版本返回dict，需要构造对象
            return IntentResult(
                intent=result.get("intent", IntentType.CHITCHAT),
                confidence=result.get("confidence", "low"),
                reason=result.get("reason", "未知原因")
            )
        return result
    except Exception as e:
        # 解析失败时降级为chitchat，不抛出异常阻断流程
        return IntentResult(
            intent=IntentType.CHITCHAT,
            confidence="low",
            reason=f"意图识别解析失败，降级处理：{str(e)}"
        )