package org.gundy.chat.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gundy.chat.entity.command.CommandDispatchResult;
import org.gundy.chat.entity.command.CommandOutcome;
import org.gundy.chat.entity.command.DialogCommand;
import org.gundy.chat.entity.command.DialogCommandType;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.gundy.chat.entity.definition.SkillDefinition;
import org.gundy.chat.entity.definition.SlotDefinition;
import org.gundy.chat.flow.FlowEngine;
import org.gundy.chat.policy.ConversationPolicy;
import org.gundy.chat.policy.PolicyDecision;
import org.gundy.chat.service.SkillDefinitionRegistry;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DialogCommandDispatcher {
    private static final Pattern FLOW_SLOT_REFERENCE = Pattern.compile("^flow-slot://([^/]+)/([^/]+)$");
    private final ConversationPolicy policy;
    private final FlowEngine flowEngine;
    private final SkillDefinitionRegistry registry;
    private final ObjectMapper objectMapper;

    public DialogCommandDispatcher(ConversationPolicy policy, FlowEngine flowEngine, SkillDefinitionRegistry registry,
                                   ObjectMapper objectMapper) {
        this.policy = policy;
        this.flowEngine = flowEngine;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    public CommandDispatchResult dispatch(String sessionId, DialogState state, List<DialogCommand> commands) {
        List<DialogCommand> safeCommands = commands == null ? Collections.<DialogCommand>emptyList() : commands;
        CommandDispatchResult preflight = dispatchInternal(sessionId, copy(state), safeCommands);
        if (preflight.isClarificationRequired() || hasRejected(preflight)) {
            preflight.setDialogState(state);
            return preflight;
        }
        return dispatchInternal(sessionId, state, safeCommands);
    }

    private CommandDispatchResult dispatchInternal(String sessionId, DialogState state, List<DialogCommand> safeCommands) {
        CommandDispatchResult result = new CommandDispatchResult();
        DialogState current = state;
        for (DialogCommand command : safeCommands) {
            ensureCommandId(command);
            PolicyDecision decision = policy.evaluate(current, command);
            if (PolicyDecision.Verdict.CLARIFY.equals(decision.getVerdict())) {
                result.getOutcomes().add(outcome(command, "CLARIFICATION_REQUIRED", decision.getReason(), null));
                result.setClarificationRequired(true);
                result.setClarificationPrompt(decision.getReason());
                break;
            }
            if (PolicyDecision.Verdict.DENY.equals(decision.getVerdict())) {
                result.getOutcomes().add(outcome(command, "REJECTED", decision.getReason(), null));
                continue;
            }

            String payloadError = validatePayload(command);
            if (payloadError != null) {
                result.getOutcomes().add(outcome(command, "REJECTED", payloadError, null));
                continue;
            }
            String referenceError = validateReferences(current, command);
            if (referenceError != null) {
                result.getOutcomes().add(outcome(command, "REJECTED", referenceError, null));
                continue;
            }

            FlowInstance affected = applyExistingFlowAction(current, decision);
            try {
                ApplyResult applied = apply(sessionId, current, command);
                current = applied.state;
                if (applied.instance != null) affected = applied.instance;
                result.getOutcomes().add(outcome(command, "APPLIED", decision.getReason(),
                        affected == null ? null : affected.getInstanceId()));
            } catch (RuntimeException ex) {
                result.getOutcomes().add(outcome(command, "REJECTED", ex.getMessage(), null));
            }
        }
        result.setDialogState(current);
        return result;
    }

    private DialogState copy(DialogState state) {
        return state == null ? null : objectMapper.convertValue(state, DialogState.class);
    }

    private boolean hasRejected(CommandDispatchResult result) {
        for (CommandOutcome outcome : result.getOutcomes()) if ("REJECTED".equals(outcome.getStatus())) return true;
        return false;
    }

    private FlowInstance applyExistingFlowAction(DialogState state, PolicyDecision decision) {
        if (state == null) return null;
        FlowInstance active = flowEngine.activeFlow(state);
        if (active == null) return null;
        if (PolicyDecision.ExistingFlowAction.SUSPEND.equals(decision.getExistingFlowAction())) {
            flowEngine.suspendFlow(state, active);
        } else if (PolicyDecision.ExistingFlowAction.CANCEL.equals(decision.getExistingFlowAction())) {
            flowEngine.cancelFlow(state, active);
        }
        return active;
    }

    private ApplyResult apply(String sessionId, DialogState state, DialogCommand command) {
        DialogCommandType type = command.getType();
        if (DialogCommandType.START_FLOW.equals(type)) {
            DialogState next = flowEngine.startFlow(sessionId, state, command.getTargetSkill());
            FlowInstance instance = flowEngine.activeFlow(next);
            applyInitialSlots(next, instance, command);
            return new ApplyResult(next, instance);
        }

        FlowInstance target = flowEngine.findFlow(state, command.getTargetFlowInstanceId(), command.getTargetSkill());
        if (DialogCommandType.SUSPEND_FLOW.equals(type)) {
            flowEngine.suspendFlow(state, target);
        } else if (DialogCommandType.RESUME_FLOW.equals(type)) {
            flowEngine.resumeFlow(state, target);
        } else if (DialogCommandType.CANCEL_FLOW.equals(type) || DialogCommandType.REJECT.equals(type)) {
            flowEngine.cancelFlow(state, target);
        } else if (DialogCommandType.SET_SLOT.equals(type)) {
            flowEngine.setSlot(state, target, command.getSlot(),
                    resolveSlotValue(state, target, command.getSlot(), command.getValue()));
        } else if (DialogCommandType.CLEAR_SLOT.equals(type)) {
            flowEngine.clearSlot(state, target, command.getSlot());
        } else if (DialogCommandType.CONFIRM.equals(type)) {
            flowEngine.confirmFlow(state, target);
        } else if (DialogCommandType.NO_OP.equals(type)) {
            return new ApplyResult(state, target);
        } else {
            throw new IllegalStateException("Command execution is not implemented: " + type);
        }
        return new ApplyResult(state, target);
    }

    private void applyInitialSlots(DialogState state, FlowInstance instance, DialogCommand command) {
        if (command.getSlots() == null) return;
        for (Map.Entry<String, Object> entry : command.getSlots().entrySet()) {
            DialogCommand setSlot = new DialogCommand();
            setSlot.setType(DialogCommandType.SET_SLOT);
            setSlot.setTargetFlowInstanceId(instance.getInstanceId());
            setSlot.setSlot(entry.getKey());
            setSlot.setValue(entry.getValue());
            PolicyDecision slotDecision = policy.evaluate(state, setSlot);
            if (!PolicyDecision.Verdict.ALLOW.equals(slotDecision.getVerdict())) {
                throw new IllegalArgumentException(slotDecision.getReason());
            }
            flowEngine.setSlot(state, instance, entry.getKey(),
                    resolveSlotValue(state, instance, entry.getKey(), entry.getValue()));
        }
    }

    private Object resolveSlotValue(DialogState state, FlowInstance target, String targetSlotId, Object value) {
        return resolveSlotValue(state, target.getSkillId(), targetSlotId, value);
    }

    private Object resolveSlotValue(DialogState state, String targetSkill, String targetSlotId, Object value) {
        if (!(value instanceof String)) return value;
        Matcher matcher = FLOW_SLOT_REFERENCE.matcher((String) value);
        if (!matcher.matches()) {
            if (((String) value).startsWith("flow-slot://")) throw new IllegalArgumentException("invalid flow slot reference");
            return value;
        }
        FlowInstance source = flowEngine.findFlow(state, matcher.group(1), null);
        if (source == null || "CANCELLED".equals(source.getStatus())) {
            throw new IllegalArgumentException("referenced flow is unavailable");
        }
        SlotDefinition sourceSlot = slot(registry.require(source.getSkillId()), matcher.group(2));
        SlotDefinition targetSlot = slot(registry.require(targetSkill), targetSlotId);
        if (sourceSlot == null || targetSlot == null || !sourceSlot.isShareable() || !targetSlot.isShareable()
                || !sourceSlot.getType().equals(targetSlot.getType())) {
            throw new IllegalArgumentException("flow slot reference is not shareable with target slot");
        }
        Object resolved = source.getSlots().get(sourceSlot.getId());
        if (resolved == null) throw new IllegalArgumentException("referenced slot has no value");
        return resolved;
    }

    private String validateReferences(DialogState state, DialogCommand command) {
        try {
            if (DialogCommandType.START_FLOW.equals(command.getType()) && command.getSlots() != null) {
                for (Map.Entry<String, Object> entry : command.getSlots().entrySet()) {
                    resolveSlotValue(state, command.getTargetSkill(), entry.getKey(), entry.getValue());
                }
            } else if (DialogCommandType.SET_SLOT.equals(command.getType())) {
                FlowInstance target = flowEngine.findFlow(state, command.getTargetFlowInstanceId(), command.getTargetSkill());
                if (target != null) resolveSlotValue(state, target, command.getSlot(), command.getValue());
            }
            return null;
        } catch (RuntimeException ex) {
            return ex.getMessage();
        }
    }

    private SlotDefinition slot(SkillDefinition definition, String slotId) {
        for (SlotDefinition slot : definition.getSlots()) if (slotId.equals(slot.getId())) return slot;
        return null;
    }

    private void ensureCommandId(DialogCommand command) {
        if (command.getCommandId() == null || command.getCommandId().trim().length() == 0) {
            command.setCommandId(UUID.randomUUID().toString());
        }
    }

    private String validatePayload(DialogCommand command) {
        if (!DialogCommandType.START_FLOW.equals(command.getType()) || command.getSlots() == null) return null;
        SkillDefinition definition = registry.require(command.getTargetSkill());
        for (String slotId : command.getSlots().keySet()) {
            boolean declared = false;
            for (SlotDefinition slot : definition.getSlots()) {
                if (slotId.equals(slot.getId())) {
                    declared = true;
                    break;
                }
            }
            if (!declared) return "slot is not declared by target skill: " + slotId;
        }
        return null;
    }

    private CommandOutcome outcome(DialogCommand command, String status, String reason, String instanceId) {
        CommandOutcome outcome = new CommandOutcome();
        outcome.setCommandId(command.getCommandId());
        outcome.setType(command.getType());
        outcome.setStatus(status);
        outcome.setReason(reason);
        outcome.setFlowInstanceId(instanceId);
        return outcome;
    }

    private static class ApplyResult {
        private final DialogState state;
        private final FlowInstance instance;

        private ApplyResult(DialogState state, FlowInstance instance) {
            this.state = state;
            this.instance = instance;
        }
    }
}
