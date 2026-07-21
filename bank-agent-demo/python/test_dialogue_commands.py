import unittest

from ai_chat_models import (
    DialogCommandType,
    DialogueCommandRequest,
    DialogueFlowSnapshot,
    DialogueSkillSnapshot,
)
from dialogue.command_interpreter import DialogueCommandInterpreter


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
        source.slots["customerReference"] = "flow-slot://msg-1/customerReference"

        result = DialogueCommandInterpreter().interpret(self.request("先查一下他的AUM", [source]))

        self.assertEqual(DialogCommandType.START_FLOW, result.commands[-1].type)
        self.assertEqual("flow-slot://msg-1/customerReference",
                         result.commands[-1].slots["customerReference"])

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
