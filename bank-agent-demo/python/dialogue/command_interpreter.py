import json
import re
from typing import List, Optional

from ai_chat_models import (
    DialogCommand,
    DialogCommandType,
    DialogueCommandPlan,
    DialogueCommandRequest,
    DialogueCommandResponse,
    DialogueFlowSnapshot,
)
from intent.structured_intent import extract_customer_name, is_customer_aum_query


SYSTEM_PROMPT = """
你是银行对话助手的对话命令理解模块。你只能理解用户如何推进对话，不能执行银行业务。

允许的命令：START_FLOW、SUSPEND_FLOW、RESUME_FLOW、CANCEL_FLOW、SET_SLOT、CLEAR_SLOT、
CONFIRM、REJECT、REQUEST_CLARIFICATION、NO_OP。

要求：
1. 必须结合当前 Flow Stack、阶段、已填槽位和候选技能理解用户输入。
2. 一句话包含多个独立业务意图时，比较置信度，只选择置信度最高的一个业务意图；不得因为复合意图返回 REQUEST_CLARIFICATION，也不得同时 START_FLOW 多个技能。
3. 用户切换业务时，必要时先 SUSPEND_FLOW，再 START_FLOW。
4. 用户只是在回答当前待填参数时返回 SET_SLOT，不要重新 START_FLOW。
5. 不能明确区分修改当前任务和切换任务时返回 REQUEST_CLARIFICATION。
6. 不得编造候选技能、Flow 实例或槽位名称。
7. confidence 为 0 到 1；reason 只写简短依据，不输出推理过程。
8. flow-slot:// 开头的值是安全槽位引用；处理“他、该客户、刚才那位客户”时原样复制引用，不得猜测真实值。
"""

try:
    from langchain_core.prompts import ChatPromptTemplate

    PROMPT = ChatPromptTemplate.from_messages([
        ("system", SYSTEM_PROMPT),
        ("human", "用户输入：{message}\n\n对话上下文：\n{context}"),
    ])
except Exception:
    PROMPT = None


class DialogueCommandInterpreter:
    def __init__(self, llm=None):
        self.llm = llm

    def interpret(self, request: DialogueCommandRequest) -> DialogueCommandResponse:
        fallback = fallback_commands(request)
        if self.llm is None or PROMPT is None:
            return response(request, fallback, False, "deterministic fallback")
        try:
            chain = PROMPT | self.llm.with_structured_output(DialogueCommandPlan)
            result = chain.invoke({"message": request.message, "context": context_json(request)})
            if isinstance(result, dict):
                result = DialogueCommandPlan(**result)
            if not result.commands and fallback:
                return response(request, fallback, False, "empty model output; deterministic fallback")
            result.commands = keep_highest_confidence_flow(result.commands, request)
            merge_deterministic_slots(result.commands, fallback)
            return response(request, result.commands, True, result.reason)
        except Exception as exc:
            return response(request, fallback, False, f"model unavailable: {type(exc).__name__}")


def fallback_commands(request: DialogueCommandRequest) -> List[DialogCommand]:
    text = (request.message or "").strip()
    active = active_flow(request.flowStack)
    suspended = [flow for flow in request.flowStack if flow.status == "SUSPENDED"]

    if re.search(r"(继续|恢复|回到).*(刚才|之前|消息|任务)", text):
        target = select_suspended(suspended, text)
        if target:
            return [command(DialogCommandType.RESUME_FLOW, target.skillId, target.instanceId, 0.92, "resume prior flow")]
    if re.search(r"(确认发送|确认并发送|可以发送|发送吧|确认)$", text):
        return [command(DialogCommandType.CONFIRM, flow_skill(active), flow_id(active), 0.98, "explicit confirmation")]
    if re.search(r"(取消|退出|不办了|不查了|别发|不发了)$", text):
        return [command(DialogCommandType.CANCEL_FLOW, flow_skill(active), flow_id(active), 0.98, "explicit cancellation")]

    if active and active.skillId == "CUSTOMER_AUM" and active.currentStage == "COLLECTING_SLOTS" \
            and re.fullmatch(r"[\u4e00-\u9fa5]{2,4}", text):
        return [slot_command(active, "customerReference", text)]

    customer_name = extract_customer_name(text)
    if refers_to_prior_customer(text):
        customer_name = None

    if is_message_send_request(text):
        customer_reference = extract_message_customer_reference(text)
        purpose = extract_message_purpose(text)
        slot_values = {}
        if customer_reference:
            slot_values["customerReference"] = customer_reference
        if purpose:
            slot_values["messagePurpose"] = purpose
        if active and active.skillId == "MESSAGE_SEND":
            commands = []
            for slot, value in slot_values.items():
                commands.append(slot_command(active, slot, value))
            return commands or [command(DialogCommandType.REQUEST_CLARIFICATION, active.skillId,
                                        active.instanceId, 0.7, "message slots are missing")]
        commands = switch_commands(active, "MESSAGE_SEND")
        if commands and commands[-1].type == DialogCommandType.START_FLOW:
            commands[-1].slots.update(slot_values)
        return commands

    if is_customer_aum_query(text, _entity_with_customer(customer_name)):
        commands = switch_commands(active, "CUSTOMER_AUM")
        if customer_name:
            if commands and commands[-1].type == DialogCommandType.START_FLOW:
                commands[-1].slots["customerReference"] = customer_name
            else:
                commands.append(slot_command(active, "customerReference", customer_name))
        elif refers_to_prior_customer(text):
            reference = prior_customer_reference(request.flowStack)
            if reference and commands and commands[-1].type == DialogCommandType.START_FLOW:
                commands[-1].slots["customerReference"] = reference
        return commands
    if re.search(r"(黄金|金价|Au9999|AU9999).*(价格|多少|行情|现在|实时)", text):
        commands = switch_commands(active, "GOLD_PRICE")
        if commands and commands[-1].type == DialogCommandType.START_FLOW:
            commands[-1].slots["query"] = text
        return commands
    if re.search(r"(更换技能|切换业务|换个问题|办理其他事项)$", text):
        return [command(DialogCommandType.REQUEST_CLARIFICATION, None, None, 0.8, "switch target is missing")]
    if active and active.currentStage == "COLLECTING_SLOTS":
        return [command(DialogCommandType.REQUEST_CLARIFICATION, active.skillId, active.instanceId,
                        0.5, "input does not match the expected slot")]
    return [command(DialogCommandType.NO_OP, flow_skill(active), flow_id(active), 0.5, "no actionable dialogue command")]


def switch_commands(active: Optional[DialogueFlowSnapshot], target_skill: str) -> List[DialogCommand]:
    if active and active.skillId == target_skill:
        return []
    commands: List[DialogCommand] = []
    if active:
        commands.append(command(DialogCommandType.SUSPEND_FLOW, active.skillId, active.instanceId,
                                0.9, "new task interrupts active flow"))
    commands.append(command(DialogCommandType.START_FLOW, target_skill, None, 0.9, "start requested task"))
    return commands


def slot_command(active: Optional[DialogueFlowSnapshot], slot: str, value) -> DialogCommand:
    result = command(DialogCommandType.SET_SLOT, flow_skill(active), flow_id(active), 0.9, "fill expected slot")
    result.slot = slot
    result.value = value
    return result


def command(command_type, skill, instance_id, confidence, reason) -> DialogCommand:
    return DialogCommand(type=command_type, targetSkill=skill, targetFlowInstanceId=instance_id,
                         confidence=confidence, reason=reason)


def active_flow(flows: List[DialogueFlowSnapshot]) -> Optional[DialogueFlowSnapshot]:
    return next((flow for flow in reversed(flows) if flow.status == "ACTIVE"), None)


def select_suspended(flows: List[DialogueFlowSnapshot], text: str) -> Optional[DialogueFlowSnapshot]:
    if "消息" in text:
        match = next((flow for flow in reversed(flows) if flow.skillId == "MESSAGE_SEND"), None)
        if match:
            return match
    return flows[-1] if flows else None


def flow_skill(flow):
    return flow.skillId if flow else None


def flow_id(flow):
    return flow.instanceId if flow else None


def context_json(request: DialogueCommandRequest) -> str:
    payload = {
        "flowStack": [model_dict(flow) for flow in request.flowStack],
        "candidateSkills": [model_dict(skill) for skill in request.candidateSkills],
        "history": [model_dict(item) for item in request.history[-6:]],
    }
    return json.dumps(payload, ensure_ascii=False)


def model_dict(model):
    return model.model_dump() if hasattr(model, "model_dump") else model.dict()


def response(request, commands, model_used, reason):
    return DialogueCommandResponse(traceId=request.traceId, sessionId=request.sessionId,
                                   commands=commands, modelUsed=model_used, reason=reason)


def merge_deterministic_slots(model_commands: List[DialogCommand], fallback_commands_: List[DialogCommand]):
    """补齐高精度降级已识别但模型遗漏的槽位，不覆盖模型已有判断。"""
    for fallback in fallback_commands_:
        if fallback.type != DialogCommandType.START_FLOW or not fallback.slots:
            continue
        target = next((item for item in model_commands
                       if item.type == DialogCommandType.START_FLOW
                       and item.targetSkill == fallback.targetSkill), None)
        if target:
            for slot, value in fallback.slots.items():
                # 跨 Flow 引用来自服务端 Flow Stack，比模型从“他/该客户”等话语中
                # 猜出的字面值更可靠，必须覆盖“一下他”之类的错误实体抽取。
                if isinstance(value, str) and value.startswith("flow-slot://"):
                    target.slots[slot] = value
                else:
                    target.slots.setdefault(slot, value)


def keep_highest_confidence_flow(commands: List[DialogCommand], request: DialogueCommandRequest) -> List[DialogCommand]:
    """复合请求只保留最高置信度的业务 Flow；允许为该 Flow 保留一次挂起当前任务的命令。"""
    starts = [item for item in commands if item.type == DialogCommandType.START_FLOW]
    target_skills = {item.targetSkill for item in starts if item.targetSkill}
    if len(target_skills) <= 1:
        return commands
    winner = max(starts, key=lambda item: item.confidence)
    active = active_flow(request.flowStack)
    filtered = []
    for item in commands:
        if item.type == DialogCommandType.START_FLOW:
            if item is winner:
                filtered.append(item)
        elif item.targetSkill == winner.targetSkill:
            filtered.append(item)
        elif item.type == DialogCommandType.SUSPEND_FLOW and active and item.targetFlowInstanceId == active.instanceId:
            if not any(existing.type == DialogCommandType.SUSPEND_FLOW for existing in filtered):
                filtered.append(item)
    return filtered


def _entity_with_customer(customer_name):
    from ai_chat_models import IntentEntities
    return IntentEntities(customerName=customer_name)


def refers_to_prior_customer(text: str) -> bool:
    return bool(re.search(r"(他|她|该客户|这个客户|那位客户|刚才.*客户)", text))


def prior_customer_reference(flows: List[DialogueFlowSnapshot]):
    for flow in reversed(flows):
        if flow.status == "CANCELLED":
            continue
        value = flow.slots.get("customerReference")
        if value is not None and str(value).strip():
            # Flow 内保存的是已经解析后的真实槽位值；跨 Flow 传递时只暴露
            # 可校验的引用，由 Java Dispatcher 在执行前解析，避免复制敏感值。
            return f"flow-slot://{flow.instanceId}/customerReference"
    return None


def is_message_send_request(text: str) -> bool:
    return bool(re.search(r"(发消息|发送消息|生成(?:一条)?(?:客户)?消息|消息预览|"
                          r"给.+(?:生成|发送|发|通知|提醒)|(?:到期|资产配置|调仓|再平衡)提醒)", text))


def extract_message_customer_reference(text: str) -> Optional[str]:
    customer_id = re.search(r"(?i)\b(?:CUST|C)[-_]?\d{3,}\b", text)
    if customer_id:
        return customer_id.group(0).upper().replace("-", "").replace("_", "")
    patterns = [
        r"(?:给|客户(?:姓名)?(?:是|为)?)[：: ]*([\u4e00-\u9fa5]{2,4}?)(?=生成|发送|发|通知|提醒|消息|的|[，,。\s]|$)",
        r"生成(?:一条)?给([\u4e00-\u9fa5]{2,4})的",
    ]
    for pattern in patterns:
        match = re.search(pattern, text)
        if match:
            return match.group(1)
    return None


def extract_message_purpose(text: str) -> Optional[str]:
    if "到期" in text:
        return "产品到期提醒"
    if "资产配置" in text or "再平衡" in text or "调仓" in text:
        return "资产配置提醒"
    custom = re.search(r"(?:内容是|发送内容是|消息内容是|自定义内容是)(.+)", text)
    if custom:
        return custom.group(1).strip()
    if "提醒" in text or "通知" in text:
        return "客户提醒"
    return None
