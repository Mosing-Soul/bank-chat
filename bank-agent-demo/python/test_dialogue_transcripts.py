import json
import os
import unittest

from ai_chat_models import DialogueCommandRequest, DialogueFlowSnapshot, DialogueSkillSnapshot
from dialogue.command_interpreter import DialogueCommandInterpreter


class DialogueTranscriptTest(unittest.TestCase):
    def test_transcript_dataset(self):
        path = os.path.join(os.path.dirname(__file__), "transcripts", "dialogue_intent_transcripts.json")
        with open(path, encoding="utf-8") as source:
            scenarios = json.load(source)
        for scenario in scenarios:
            with self.subTest(scenario=scenario["name"]):
                flows = [DialogueFlowSnapshot(**item) for item in scenario["initialFlowStack"]]
                for turn_index, turn in enumerate(scenario["turns"]):
                    result = DialogueCommandInterpreter().interpret(self.request(turn["user"], flows))
                    self.assertEqual(turn["commands"], [item.type.value for item in result.commands])
                    last = result.commands[-1]
                    if "lastTargetSkill" in turn:
                        self.assertEqual(turn["lastTargetSkill"], last.targetSkill)
                    if "lastSlots" in turn:
                        self.assertEqual(turn["lastSlots"], last.slots)
                    if "lastSlot" in turn:
                        self.assertEqual(turn["lastSlot"], last.slot)
                    if "lastValue" in turn:
                        self.assertEqual(turn["lastValue"], last.value)
                    self.apply(flows, result.commands, turn_index)

    def request(self, message, flows):
        return DialogueCommandRequest(
            traceId="transcript", sessionId="transcript-session", message=message, flowStack=flows,
            candidateSkills=[
                DialogueSkillSnapshot(id="CUSTOMER_AUM", name="客户资产查询", description="查询客户AUM",
                                      riskLevel="READ_ONLY", interruptPolicy="SUSPENDABLE",
                                      slots=["customerReference"]),
                DialogueSkillSnapshot(id="MESSAGE_SEND", name="客户消息触达", description="生成并确认发送消息",
                                      riskLevel="EXTERNAL_SIDE_EFFECT", interruptPolicy="CONFIRM_OR_SUSPEND",
                                      slots=["customerReference", "messagePurpose"]),
                DialogueSkillSnapshot(id="GOLD_PRICE", name="黄金行情", description="查询黄金价格",
                                      riskLevel="EXTERNAL_READ", interruptPolicy="REPLACEABLE", slots=["query"]),
            ],
        )

    def apply(self, flows, commands, turn_index):
        for command in commands:
            if command.type.value == "SUSPEND_FLOW":
                target = self.find(flows, command.targetFlowInstanceId, command.targetSkill)
                if target:
                    target.status = "SUSPENDED"
            elif command.type.value == "RESUME_FLOW":
                for flow in flows:
                    if flow.status == "ACTIVE":
                        flow.status = "CANCELLED"
                target = self.find(flows, command.targetFlowInstanceId, command.targetSkill)
                if target:
                    target.status = "ACTIVE"
            elif command.type.value == "START_FLOW":
                flows.append(DialogueFlowSnapshot(instanceId=f"generated-{turn_index}", skillId=command.targetSkill,
                                                   status="ACTIVE", currentStage="COLLECTING_SLOTS",
                                                   slots=dict(command.slots)))
            elif command.type.value == "SET_SLOT":
                target = self.find(flows, command.targetFlowInstanceId, command.targetSkill)
                if target:
                    target.slots[command.slot] = command.value

    def find(self, flows, instance_id, skill_id):
        for flow in reversed(flows):
            if instance_id and flow.instanceId == instance_id:
                return flow
            if not instance_id and skill_id and flow.skillId == skill_id:
                return flow
        return None


if __name__ == "__main__":
    unittest.main()
