package org.gundy.chat.policy;

import org.gundy.chat.entity.command.DialogCommand;
import org.gundy.chat.entity.command.DialogCommandType;
import org.gundy.chat.entity.definition.InterruptPolicy;
import org.gundy.chat.entity.definition.SkillDefinition;
import org.gundy.chat.entity.definition.SkillRiskLevel;
import org.gundy.chat.entity.definition.SlotDefinition;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.gundy.chat.service.SkillDefinitionRegistry;
import org.springframework.stereotype.Service;

@Service
public class ConversationPolicy {
    private static final double MIN_SEMANTIC_COMMAND_CONFIDENCE = 0.55D;

    private final SkillDefinitionRegistry registry;

    public ConversationPolicy(SkillDefinitionRegistry registry) {
        this.registry = registry;
    }

    public PolicyDecision evaluate(DialogState state, DialogCommand command) {
        if (command == null || command.getType() == null) {
            return PolicyDecision.deny("command type is required");
        }
        if (command.getConfidence() > 0.0D && command.getConfidence() < MIN_SEMANTIC_COMMAND_CONFIDENCE) {
            return PolicyDecision.clarify("command confidence is too low");
        }
        if (DialogCommandType.REQUEST_CLARIFICATION.equals(command.getType())) {
            return PolicyDecision.clarify(hasText(command.getReason()) ? command.getReason() : "user intent is ambiguous");
        }
        if (DialogCommandType.NO_OP.equals(command.getType())) {
            return PolicyDecision.allow("no state change requested");
        }
        if (DialogCommandType.START_FLOW.equals(command.getType())) {
            return evaluateStart(state, command);
        }

        FlowInstance target = targetFlow(state, command);
        if (target == null) {
            return PolicyDecision.deny("target flow was not found");
        }
        if ("COMPLETED".equals(target.getStatus()) || "CANCELLED".equals(target.getStatus())) {
            return PolicyDecision.deny("terminal flow cannot be changed");
        }
        if (DialogCommandType.SET_SLOT.equals(command.getType()) || DialogCommandType.CLEAR_SLOT.equals(command.getType())) {
            return evaluateSlotCommand(target, command);
        }
        if (DialogCommandType.SUSPEND_FLOW.equals(command.getType())) {
            return interruptionDecision(target, false);
        }
        if (DialogCommandType.RESUME_FLOW.equals(command.getType())) {
            if (!"SUSPENDED".equals(target.getStatus())) {
                return PolicyDecision.deny("only a suspended flow can be resumed");
            }
            FlowInstance active = activeFlow(state);
            if (active == null || active.getInstanceId().equals(target.getInstanceId())) {
                return PolicyDecision.allow("resume suspended flow");
            }
            return interruptionDecision(active, true);
        }
        if (DialogCommandType.CANCEL_FLOW.equals(command.getType())) {
            return PolicyDecision.allow("user may cancel a non-terminal flow");
        }
        if (DialogCommandType.CONFIRM.equals(command.getType())) {
            return "WAITING_CONFIRMATION".equals(target.getCurrentStage())
                    ? PolicyDecision.allow("confirmation matches current stage")
                    : PolicyDecision.deny("flow is not waiting for confirmation");
        }
        if (DialogCommandType.REJECT.equals(command.getType())) {
            return PolicyDecision.allow("user rejected the pending operation");
        }
        return PolicyDecision.deny("unsupported command: " + command.getType());
    }

    private PolicyDecision evaluateStart(DialogState state, DialogCommand command) {
        if (!hasText(command.getTargetSkill())) {
            return PolicyDecision.deny("target skill is required for START_FLOW");
        }
        SkillDefinition target = registry.find(command.getTargetSkill());
        if (target == null || !target.isEnabled()) {
            return PolicyDecision.deny("target skill is unknown or disabled");
        }
        FlowInstance active = activeFlow(state);
        if (active == null) {
            return PolicyDecision.allow("no active flow");
        }
        if (target.getId().equals(active.getSkillId())) {
            return PolicyDecision.allow("target flow is already active");
        }
        return interruptionDecision(active, true);
    }

    private PolicyDecision evaluateSlotCommand(FlowInstance target, DialogCommand command) {
        if (!hasText(command.getSlot())) {
            return PolicyDecision.deny("slot name is required");
        }
        SkillDefinition definition = registry.require(target.getSkillId());
        for (SlotDefinition slot : definition.getSlots()) {
            if (command.getSlot().equals(slot.getId())) {
                return PolicyDecision.allow("slot belongs to target flow");
            }
        }
        return PolicyDecision.deny("slot is not declared by target skill");
    }

    private PolicyDecision interruptionDecision(FlowInstance active, boolean chooseAction) {
        SkillDefinition current = registry.require(active.getSkillId());
        if ("EXECUTING".equals(active.getCurrentStage())
                && SkillRiskLevel.EXTERNAL_SIDE_EFFECT.equals(current.getRiskLevel())) {
            return PolicyDecision.deny("side-effect flow cannot be interrupted while executing");
        }
        InterruptPolicy policy = current.getInterruptPolicy();
        if (InterruptPolicy.NOT_INTERRUPTIBLE.equals(policy)) {
            return PolicyDecision.deny("current flow is not interruptible");
        }
        if (!chooseAction) {
            return PolicyDecision.allow("current flow may be suspended");
        }
        if (InterruptPolicy.REPLACEABLE.equals(policy)) {
            return PolicyDecision.allow("replace current flow", PolicyDecision.ExistingFlowAction.CANCEL);
        }
        return PolicyDecision.allow("suspend current flow", PolicyDecision.ExistingFlowAction.SUSPEND);
    }

    private FlowInstance activeFlow(DialogState state) {
        if (state == null || state.getFlowStack() == null) return null;
        for (int i = state.getFlowStack().size() - 1; i >= 0; i--) {
            FlowInstance instance = state.getFlowStack().get(i);
            if ("ACTIVE".equals(instance.getStatus())) return instance;
        }
        return null;
    }

    private FlowInstance targetFlow(DialogState state, DialogCommand command) {
        if (state == null || state.getFlowStack() == null) return null;
        for (int i = state.getFlowStack().size() - 1; i >= 0; i--) {
            FlowInstance instance = state.getFlowStack().get(i);
            if (hasText(command.getTargetFlowInstanceId())
                    && command.getTargetFlowInstanceId().equals(instance.getInstanceId())) return instance;
            if (!hasText(command.getTargetFlowInstanceId()) && hasText(command.getTargetSkill())
                    && command.getTargetSkill().equalsIgnoreCase(instance.getSkillId())) return instance;
        }
        return !hasText(command.getTargetFlowInstanceId()) && !hasText(command.getTargetSkill()) ? activeFlow(state) : null;
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
