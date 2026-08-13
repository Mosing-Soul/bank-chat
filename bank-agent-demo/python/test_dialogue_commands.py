import unittest

from ai_chat_models import (
    DialogCommand,
    DialogCommandType,
    DialogueCommandRequest,
    DialogueFlowSnapshot,
    DialogueSkillSnapshot,
)
from dialogue.command_interpreter import (
    DialogueCommandInterpreter,
    keep_highest_confidence_flow,
    merge_deterministic_slots,
)


class DialogueCommandInterpreterTest(unittest.TestCase):
    def test_new_aum_request_suspends_message_and_starts_customer_flow(self):
        request = self.request(
            "先别发，帮我查询张伟的AUM",
            [self.flow("msg-1", "MESSAGE_SEND", "ACTIVE", "WAITING_CONFIRMATION")],
        )

        result = DialogueCommandInterpreter().interpret(request)

        self.assertEqual(
            [DialogCommandType.SUSPEND_FLOW, DialogCommandType.START_FLOW],
            [item.type for item in result.commands],
        )
        self.assertEqual("msg-1", result.commands[0].targetFlowInstanceId)
        self.assertEqual("张伟", result.commands[1].slots["customerReference"])

    def test_bare_name_fills_expected_customer_slot(self):
        request = self.request(
            "张伟",
            [self.flow("aum-1", "CUSTOMER_AUM", "ACTIVE", "COLLECTING_SLOTS")],
        )

        result = DialogueCommandInterpreter().interpret(request)

        self.assertEqual([DialogCommandType.SET_SLOT], [item.type for item in result.commands])
        self.assertEqual("customerReference", result.commands[0].slot)
        self.assertEqual("张伟", result.commands[0].value)

    def test_resume_returns_to_suspended_message(self):
        request = self.request(
            "继续刚才的消息",
            [
                self.flow("msg-1", "MESSAGE_SEND", "SUSPENDED", "WAITING_CONFIRMATION"),
                self.flow("aum-1", "CUSTOMER_AUM", "ACTIVE", "COLLECTING_SLOTS"),
            ],
        )

        result = DialogueCommandInterpreter().interpret(request)

        self.assertEqual(DialogCommandType.RESUME_FLOW, result.commands[0].type)
        self.assertEqual("msg-1", result.commands[0].targetFlowInstanceId)

    def test_switch_without_target_requests_clarification(self):
        result = DialogueCommandInterpreter().interpret(self.request("更换技能", []))

        self.assertEqual(DialogCommandType.REQUEST_CLARIFICATION, result.commands[0].type)

    def test_gold_request_starts_flow_with_original_query(self):
        result = DialogueCommandInterpreter().interpret(self.request("Au9999现在多少钱", []))

        self.assertEqual(DialogCommandType.START_FLOW, result.commands[0].type)
        self.assertEqual("GOLD_PRICE", result.commands[0].targetSkill)
        self.assertEqual("Au9999现在多少钱", result.commands[0].slots["query"])

    def test_pronoun_reuses_safe_customer_reference(self):
        source = self.flow("msg-1", "MESSAGE_SEND", "ACTIVE", "WAITING_CONFIRMATION")
        source.slots["customerReference"] = "张伟"

        result = DialogueCommandInterpreter().interpret(self.request("先查一下他的AUM", [source]))

        self.assertEqual(DialogCommandType.START_FLOW, result.commands[-1].type)
        self.assertEqual("flow-slot://msg-1/customerReference",
                         result.commands[-1].slots["customerReference"])

    def test_safe_reference_overrides_model_pronoun_fragment(self):
        from ai_chat_models import DialogCommand
        model = [DialogCommand(type=DialogCommandType.START_FLOW, targetSkill="CUSTOMER_AUM",
                               slots={"customerReference": "一下他"}, confidence=0.9)]
        fallback = [DialogCommand(type=DialogCommandType.START_FLOW, targetSkill="CUSTOMER_AUM",
                                  slots={"customerReference": "flow-slot://msg-1/customerReference"},
                                  confidence=0.9)]

        merge_deterministic_slots(model, fallback)

        self.assertEqual("flow-slot://msg-1/customerReference",
                         model[0].slots["customerReference"])

    def test_message_request_extracts_customer_and_purpose(self):
        result = DialogueCommandInterpreter().interpret(self.request("给张伟生成产品到期提醒", []))

        self.assertEqual(DialogCommandType.START_FLOW, result.commands[0].type)
        self.assertEqual("MESSAGE_SEND", result.commands[0].targetSkill)
        self.assertEqual("张伟", result.commands[0].slots["customerReference"])
        self.assertEqual("产品到期提醒", result.commands[0].slots["messagePurpose"])

    def test_deterministic_slots_complete_incomplete_model_command(self):
        model = [DialogCommand(type=DialogCommandType.START_FLOW, targetSkill="MESSAGE_SEND",
                               slots={"messagePurpose": "产品到期提醒"}, confidence=0.9)]
        fallback = [DialogCommand(type=DialogCommandType.START_FLOW, targetSkill="MESSAGE_SEND",
                                  slots={"customerReference": "张伟", "messagePurpose": "产品到期提醒"},
                                  confidence=0.9)]

        merge_deterministic_slots(model, fallback)

        self.assertEqual("张伟", model[0].slots["customerReference"])
        self.assertEqual("产品到期提醒", model[0].slots["messagePurpose"])

    def test_compound_model_commands_keep_only_highest_confidence_flow(self):
        request = self.request("查询张伟AUM并给他生成产品到期提醒", [])
        commands = [
            DialogCommand(type=DialogCommandType.START_FLOW, targetSkill="CUSTOMER_AUM", confidence=0.81),
            DialogCommand(type=DialogCommandType.START_FLOW, targetSkill="MESSAGE_SEND", confidence=0.92),
        ]

        selected = keep_highest_confidence_flow(commands, request)

        self.assertEqual(1, len(selected))
        self.assertEqual("MESSAGE_SEND", selected[0].targetSkill)

    def request(self, message, flows):
        return DialogueCommandRequest(
            traceId="trace-1",
            sessionId="session-1",
            message=message,
            flowStack=flows,
            candidateSkills=[
                DialogueSkillSnapshot(
                    id="CUSTOMER_AUM",
                    name="客户资产查询",
                    description="查询客户AUM",
                    riskLevel="READ_ONLY",
                    interruptPolicy="SUSPENDABLE",
                    slots=["customerReference"],
                )
            ],
        )

    def flow(self, instance_id, skill_id, status, stage):
        return DialogueFlowSnapshot(
            instanceId=instance_id,
            skillId=skill_id,
            status=status,
            currentStage=stage,
            slots={},
        )


if __name__ == "__main__":
    unittest.main()
