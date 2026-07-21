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
2. 一句话包含多个动作时按执行顺序返回多个命令。
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


def _entity_with_customer(customer_name):
    from ai_chat_models import IntentEntities
    return IntentEntities(customerName=customer_name)


def refers_to_prior_customer(text: str) -> bool:
    return bool(re.search(r"(他|她|该客户|这个客户|那位客户|刚才.*客户)", text))


def prior_customer_reference(flows: List[DialogueFlowSnapshot]):
    for flow in reversed(flows):
        value = flow.slots.get("customerReference")
        if isinstance(value, str) and value.startswith("flow-slot://"):
            return value
    return None
