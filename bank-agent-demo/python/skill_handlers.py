import re
import time
from typing import Callable, Dict, List, Optional

from ai_chat_models import Citation, IntentType, SkillRequest, SkillResult
from java_skill_client import JavaSkillClientError


LOCAL_MOCK_AUM = {
    "CUST001": {
        "customerId": "CUST001",
        "customerName": "\u5f20\u4f1f",
        "totalAum": "8260000.00",
        "currency": "CNY",
        "statisticsDate": "2026-06-23",
        "holdingsSummary": ["\u73b0\u91d1\u53ca\u6d3b\u671f 12%", "\u56fa\u6536\u7406\u8d22 46%", "\u57fa\u91d1\u7ec4\u5408 28%", "\u8d35\u91d1\u5c5e 14%"],
        "dataSource": "LOCAL_MOCK_BANK_CORE_AUM",
        "mock": True,
    },
    "CUST002": {
        "customerId": "CUST002",
        "customerName": "\u5f20\u4f1f\u660e",
        "totalAum": "3180000.00",
        "currency": "CNY",
        "statisticsDate": "2026-06-23",
        "holdingsSummary": ["\u5b9a\u671f\u5b58\u6b3e 38%", "\u56fa\u6536\u7406\u8d22 44%", "\u57fa\u91d1\u7ec4\u5408 18%"],
        "dataSource": "LOCAL_MOCK_BANK_CORE_AUM",
        "mock": True,
    },
}


def local_mock_customer_search(name: Optional[str]) -> List[Dict]:
    if not name:
        return []
    keyword = name.strip()
    if not keyword:
        return []
    return [
        {
            "customerId": aum["customerId"],
            "customerName": aum["customerName"],
            "mock": True,
        }
        for aum in LOCAL_MOCK_AUM.values()
        if keyword in aum["customerName"]
    ]


def local_mock_aum(customer_id: Optional[str], name: Optional[str]) -> Optional[Dict]:
    if customer_id and customer_id in LOCAL_MOCK_AUM:
        return LOCAL_MOCK_AUM[customer_id]
    matches = local_mock_customer_search(name)
    exact_matches = [customer for customer in matches if customer.get("customerName") == name]
    if len(exact_matches) == 1:
        return LOCAL_MOCK_AUM.get(exact_matches[0].get("customerId"))
    if len(matches) == 1:
        return LOCAL_MOCK_AUM.get(matches[0].get("customerId"))
    return None


class SkillHandler:
    skill_name = "base"

    def handle(self, request: SkillRequest) -> SkillResult:
        raise NotImplementedError


class KnowledgeRagSkill(SkillHandler):
    skill_name = "knowledge-rag"

    def __init__(self, rag_query: Callable):
        self.rag_query = rag_query

    def handle(self, request: SkillRequest) -> SkillResult:
        try:
            rag_response = self.rag_query(request.user_message, request.session_id, request.history)
            sources = getattr(rag_response, "sources", []) or []
            return SkillResult(
                success=True,
                answer=getattr(rag_response, "answer", ""),
                citations=[Citation(source=source, title=source) for source in sources],
                data={},
            )
        except Exception as exc:
            return SkillResult(
                success=False,
                answer="知识库问答暂时不可用，请稍后再试。",
                error_code="RAG_ERROR",
                error_message=type(exc).__name__,
            )


class CustomerAumSkill(SkillHandler):
    skill_name = "customer-aum"

    def __init__(self, java_client):
        self.java_client = java_client

    def handle(self, request: SkillRequest) -> SkillResult:
        name = request.entities.customerName or extract_pending_customer_name(request.history)
        customer_id = request.entities.customerId
        if not name and not customer_id:
            return SkillResult(success=True, answer="请提供客户姓名或客户编号，以便查询 AUM。")
        try:
            if not customer_id:
                try:
                    customers = self.java_client.search_customers(request.trace_id, name)
                except JavaSkillClientError:
                    customers = local_mock_customer_search(name)
                if not customers:
                    return SkillResult(success=True, answer=f"未找到客户{name}，请确认客户姓名。")
                exact_matches = [customer for customer in customers if customer.get("customerName") == name]
                if len(exact_matches) == 1:
                    customers = exact_matches
                if len(customers) > 1:
                    return SkillResult(
                        success=True,
                        answer="找到多个同名或相近客户，请先选择客户后再查询 AUM。",
                        data={"candidates": customers},
                    )
                customer_id = customers[0].get("customerId")
            aum = self.java_client.get_aum(request.trace_id, customer_id)
        except JavaSkillClientError as exc:
            aum = local_mock_aum(customer_id, name)
            if aum:
                answer = "客户{customerName}当前总 AUM 为 {totalAum} {currency}，统计日期 {statisticsDate}。（模拟数据）".format(**aum)
                return SkillResult(success=True, answer=answer, data={"aum": aum})
            return SkillResult(success=False, answer="客户资产查询暂时不可用，请稍后再试。", error_code=exc.code, error_message=exc.message)

        answer = "客户{customerName}当前总 AUM 为 {totalAum} {currency}，统计日期 {statisticsDate}。（模拟数据）".format(**aum)
        return SkillResult(success=True, answer=answer, data={"aum": aum})


class ExternalModelApiSkill(SkillHandler):
    skill_name = "external-model-api"
    MARKET_DATA_NOTICE = (
        "> 💡 温馨提示：以上行情来自公开网络搜索，可能存在时间延迟，仅供参考；"
        "实际交易请以正规交易平台的最新报价为准。"
    )

    def __init__(self, search_client=None, llm=None):
        self.search_client = search_client
        self.llm = llm

    def handle(self, request: SkillRequest) -> SkillResult:
        if self.search_client is None:
            return SkillResult(
                success=False,
                answer="联网搜索工具尚未配置，请设置 SERPER_API_KEY 后重试。",
                error_code="EXTERNAL_MODEL_UNAVAILABLE",
                error_message="external search client is not configured",
            )
        try:
            search_result = self.search_client.search_text(request.user_message)
            answer = self._compose_answer(request.user_message, search_result)
            return SkillResult(
                success=True,
                answer=f"{answer.rstrip()}\n\n{self.MARKET_DATA_NOTICE}",
                data={"externalApi": {"provider": "google-serper", "mock": False}},
            )
        except Exception as exc:
            return SkillResult(
                success=False,
                answer="联网搜索工具调用失败，请稍后再试。",
                error_code="EXTERNAL_MODEL_ERROR",
                error_message=type(exc).__name__,
            )

    def _compose_answer(self, question: str, search_result: str) -> str:
        if self.llm is None:
            return search_result
        prompt = (
            "你是银行客户经理助手中的外部信息查询模块。请只基于以下搜索结果回答用户问题，"
            "回答要简洁，并说明这是外部搜索结果；如果搜索结果不足，请明确说明不确定性。"
            "使用清晰的 Markdown 排版：先给简短结论，再用项目列表整理不同品种的价格和时间；"
            "不得把历史日期的价格描述成当前实时价格。\n\n"
            f"用户问题：{question}\n\n"
            f"搜索结果：\n{search_result}\n\n"
            "回答："
        )
        result = self.llm.invoke(prompt)
        return result.content if hasattr(result, "content") else str(result)


class MessagePreviewSkill(SkillHandler):
    skill_name = "message-preview"

    def __init__(self, java_client):
        self.java_client = java_client

    def handle(self, request: SkillRequest) -> SkillResult:
        pending_operation_id = extract_pending_operation_id(request.history)

        if pending_operation_id and is_cancel_message(request.user_message):
            return SkillResult(
                success=True,
                answer=f"已取消本次消息发送流程，operationId：{pending_operation_id}。如需发送，请重新说明客户和消息内容。",
                data={"messageFlow": {"status": "CANCELLED", "operationId": pending_operation_id}},
            )

        if pending_operation_id and is_confirm_message(request.user_message):
            try:
                sent = self.java_client.send_message(request.trace_id, pending_operation_id, True)
            except JavaSkillClientError as exc:
                return SkillResult(
                    success=False,
                    answer="消息发送暂时不可用，请稍后再试。",
                    error_code=exc.code,
                    error_message=exc.message,
                )
            return SkillResult(
                success=True,
                answer=(
                    "已确认并发送客户消息。\n"
                    f"- 客户：{sent.get('customerName', '未知客户')}\n"
                    f"- 发送渠道：{sent.get('channel', '模拟渠道')}\n"
                    f"- 发送状态：{sent.get('status', 'SENT')}\n"
                    f"- operationId：{sent.get('operationId', pending_operation_id)}\n"
                    "本环境为模拟发送，不会触达真实客户。"
                ),
                data={"messageSend": sent, "messageFlow": {"status": "SENT", "operationId": pending_operation_id}},
            )

        name = (
            request.entities.customerName
            or extract_customer_name_from_text(request.user_message)
            or extract_message_flow_customer(request.history)
            or extract_pending_customer_name(request.history)
        )
        purpose = (
            request.entities.messagePurpose
            or infer_message_purpose(request.user_message)
            or extract_message_flow_purpose(request.history)
        )
        template_code = request.entities.templateCode or template_code_for_purpose(purpose)

        if pending_operation_id and is_revision_message(request.user_message):
            purpose = purpose or extract_revision_purpose(request.user_message)
            template_code = template_code or template_code_for_purpose(purpose)

        if not name and not purpose:
            return SkillResult(
                success=True,
                answer="请补充要发送消息的客户姓名和消息用途。用途可以是产品到期提醒、资产配置提醒，或直接给出自定义内容。",
                data={"messageFlow": {"status": "NEED_CUSTOMER_AND_PURPOSE"}},
            )
        if not name:
            return SkillResult(
                success=True,
                answer=f"已识别消息用途：{purpose}。请提供要发送消息的客户姓名，例如：张伟。",
                data={"messageFlow": {"status": "NEED_CUSTOMER", "messagePurpose": purpose}},
            )
        if not purpose or not template_code:
            return SkillResult(
                success=True,
                answer="请说明消息用途，例如产品到期提醒、资产配置提醒，或直接给出要发送的具体内容。",
                data={"messageFlow": {"status": "NEED_PURPOSE", "customerName": name}},
            )
        try:
            customers = self.java_client.search_customers(request.trace_id, name)
            if not customers:
                return SkillResult(success=True, answer=f"未找到客户{name}，请确认客户姓名。")
            if len(customers) > 1:
                return SkillResult(
                    success=True,
                    answer="找到多个相近客户，请先选择客户后再生成消息。",
                    data={"candidates": customers, "messageFlow": {"status": "NEED_CUSTOMER_SELECTION"}},
                )
            customer_id = customers[0].get("customerId")
            preview = self.java_client.preview_message(
                request.trace_id, customer_id, template_code, variables_for_template(template_code, purpose)
            )
        except JavaSkillClientError as exc:
            return SkillResult(success=False, answer="消息预览暂时不可用，请稍后再试。", error_code=exc.code, error_message=exc.message)

        operation_id = preview.get("operationId")
        customer_name = preview.get("customerName")
        content = preview.get("content")
        return SkillResult(
            success=True,
            answer=(
                "已生成消息预览，请确认后再发送。\n"
                f"- 客户：{customer_name}\n"
                f"- 消息内容：{content}\n"
                f"- operationId：{operation_id}\n"
                "你可以回复“确认发送”完成发送，回复“取消”放弃，或说明“修改为...”重新生成。"
            ),
            data={"messagePreview": preview, "messageFlow": {"status": "PENDING_CONFIRMATION", "operationId": operation_id}},
            requires_confirmation=True,
            confirmation={
                "operationId": operation_id,
                "customerName": customer_name,
                "content": content,
                "status": preview.get("status"),
                "mock": preview.get("mock", True),
            },
        )

class GeneralChatSkill(SkillHandler):
    skill_name = "general-chat"

    def __init__(self, llm=None):
        self.llm = llm

    def handle(self, request: SkillRequest) -> SkillResult:
        if self.llm is None:
            return SkillResult(success=True, answer="你好，我是银行客户经理智能助手，可以协助查询知识规则、客户 AUM、黄金模拟行情，并生成客户消息预览。")
        prompt = (
            "你是银行客户经理智能助手。简短回答，不编造客户资产或实时行情；"
            "涉及业务操作时，引导用户提供必要信息。\n用户："
            + request.user_message
        )
        try:
            result = self.llm.invoke(prompt)
            answer = result.content if hasattr(result, "content") else str(result)
            return SkillResult(success=True, answer=answer)
        except Exception:
            return SkillResult(success=True, answer="你好，我是银行客户经理智能助手，可以协助知识问答、客户 AUM 查询、黄金模拟行情和消息预览。")


class ClarificationSkill(SkillHandler):
    skill_name = "clarification"

    def handle(self, request: SkillRequest) -> SkillResult:
        return SkillResult(success=True, answer="请说明您想查询产品规则、客户资产、黄金行情，还是需要生成客户消息。")


def extract_pending_operation_id(history) -> Optional[str]:
    if not history:
        return None
    for message in reversed(history):
        if getattr(message, "role", "") != "assistant":
            continue
        content = getattr(message, "content", "") or ""
        if "operationId" not in content or any(word in content for word in ("已确认并发送", "已取消")):
            continue
        match = re.search(r"operationId[：:\s]+([A-Za-z0-9_.-]+)", content)
        if match:
            return match.group(1).strip()
    return None


def extract_pending_customer_name(history) -> Optional[str]:
    if not history:
        return None
    for message in reversed(history):
        if getattr(message, "role", "") != "assistant":
            continue
        content = getattr(message, "content", "") or ""
        if "operationId" not in content or any(word in content for word in ("已确认并发送", "已取消")):
            continue
        match = re.search(r"客户[：:\s]+([^\n，,。]+)", content)
        if match:
            return match.group(1).strip()
    return None


def has_open_message_flow(history) -> bool:
    if not history:
        return False
    for message in reversed(history[-6:]):
        if getattr(message, "role", "") != "assistant":
            continue
        content = getattr(message, "content", "") or ""
        if any(marker in content for marker in (
            "请补充要发送消息的客户姓名和消息用途",
            "请提供要发送消息的客户姓名",
            "请说明消息用途",
            "已识别消息用途",
            "已生成消息预览",
        )):
            return True
    return False


def extract_message_flow_customer(history) -> Optional[str]:
    if not history:
        return None
    for message in reversed(history[-8:]):
        content = getattr(message, "content", "") or ""
        if getattr(message, "role", "") == "user":
            name = extract_customer_name_from_text(content)
            if name:
                return name
        if getattr(message, "role", "") == "assistant":
            match = re.search(r"customerName['\"]?\s*[:：]\s*['\"]?([\u4e00-\u9fa5]{2,4})", content)
            if match:
                return match.group(1)
    return None


def extract_message_flow_purpose(history) -> Optional[str]:
    if not history:
        return None
    for message in reversed(history[-8:]):
        if getattr(message, "role", "") != "user":
            continue
        content = getattr(message, "content", "") or ""
        purpose = infer_message_purpose(content)
        if purpose:
            return purpose
    for message in reversed(history[-8:]):
        if getattr(message, "role", "") != "assistant":
            continue
        content = getattr(message, "content", "") or ""
        match = re.search(r"已识别消息用途[：:\s]+([^\n。]+)", content)
        if match:
            return match.group(1).strip()
    return None


def extract_customer_name_from_text(text: str) -> Optional[str]:
    if not text:
        return None
    match = re.search(r"(?:客户|给)([\u4e00-\u9fa5]{2,4})", text)
    if match:
        name = re.sub(r"(发送|发|生成|消息|提醒|通知)$", "", match.group(1))
        return name or None
    if re.fullmatch(r"[\u4e00-\u9fa5]{2,4}", text.strip()) and not infer_message_purpose(text):
        return text.strip()
    return None


def infer_message_purpose(text: str) -> Optional[str]:
    if not text:
        return None
    if "到期" in text:
        return "产品到期提醒"
    if "资产配置" in text or "再平衡" in text or "调仓" in text:
        return "资产配置提醒"
    if "提醒" in text or "通知" in text:
        return "客户提醒"
    return None


def template_code_for_purpose(purpose: Optional[str]) -> Optional[str]:
    if purpose == "产品到期提醒":
        return "PRODUCT_MATURITY_REMINDER"
    if purpose == "资产配置提醒":
        return "ASSET_REBALANCE_NOTICE"
    if purpose:
        return "CUSTOM_CONTENT"
    return None


def is_confirm_message(text: str) -> bool:
    return bool(re.search(r"(确认发送|确认并发送|可以发送|发出去|发送吧|同意发送|确认)", text or ""))


def is_cancel_message(text: str) -> bool:
    return bool(re.search(r"(取消|不发|别发|放弃|撤销)", text or ""))


def is_revision_message(text: str) -> bool:
    return bool(re.search(r"(修改|改成|调整|重写|重新生成|换成)", text or ""))


def extract_revision_purpose(text: str) -> Optional[str]:
    if "到期" in text:
        return "产品到期提醒"
    if "资产配置" in text or "再平衡" in text or "调仓" in text:
        return "资产配置提醒"
    if "修改为" in text or "改成" in text:
        return re.sub(r"^(修改为|改成|调整为)", "", text).strip()
    return None


def variables_for_template(template_code: str, purpose: str) -> Dict[str, str]:
    if template_code == "PRODUCT_MATURITY_REMINDER":
        return {"productName": "稳健增利理财产品", "maturityDate": "近期"}
    if template_code == "ASSET_REBALANCE_NOTICE":
        return {"portfolioName": "当前投资组合"}
    return {"content": purpose}

