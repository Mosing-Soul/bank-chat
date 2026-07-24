package org.gundy.chat.flow;

import org.gundy.chat.entity.definition.FlowStageDefinition;
import org.gundy.chat.entity.definition.SkillDefinition;
import org.gundy.chat.entity.definition.SlotDefinition;
import org.gundy.chat.entity.dialog.DialogIntent;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.dialog.DialogUiAction;
import org.gundy.chat.entity.dialog.DialogUiHints;
import org.gundy.chat.entity.dialog.SkillDialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.gundy.chat.service.SkillDefinitionRegistry;
import org.gundy.chat.progress.DialogueProgress;
import org.gundy.chat.statemachine.SkillTransitionResult;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FlowEngine {
    private static final int MAX_STAGE_TRANSITIONS_PER_TURN = 10;

    private final SkillDefinitionRegistry registry;
    private final Map<String, FlowSkillHandler> handlers;

    public FlowEngine(SkillDefinitionRegistry registry, List<FlowSkillHandler> handlers) {
        this.registry = registry;
        Map<String, FlowSkillHandler> values = new LinkedHashMap<String, FlowSkillHandler>();
        for (FlowSkillHandler handler : handlers) {
            if (values.put(handler.skillId(), handler) != null) {
                throw new IllegalArgumentException("Duplicate flow handler: " + handler.skillId());
            }
        }
        this.handlers = Collections.unmodifiableMap(values);
    }

    public DialogState startFlow(String sessionId, DialogState state, String skillId) {
        SkillDefinition definition = registry.require(skillId);
        DialogState nextState = state == null ? newDialogState(sessionId, definition) : state;
        FlowInstance instance = activeOrNewInstance(nextState, definition);
        updateIntent(nextState, definition);
        syncLegacyState(nextState, instance, definition);
        touch(nextState, instance);
        return nextState;
    }

    public FlowInstance activeFlow(DialogState state) {
        if (state == null || state.getFlowStack() == null) return null;
        for (int i = state.getFlowStack().size() - 1; i >= 0; i--) {
            FlowInstance instance = state.getFlowStack().get(i);
            if ("ACTIVE".equals(instance.getStatus())) return instance;
        }
        return null;
    }

    public FlowInstance findFlow(DialogState state, String instanceId, String skillId) {
        if (state == null || state.getFlowStack() == null) return null;
        for (int i = state.getFlowStack().size() - 1; i >= 0; i--) {
            FlowInstance instance = state.getFlowStack().get(i);
            if (instanceId != null && instanceId.equals(instance.getInstanceId())) return instance;
            if (instanceId == null && skillId != null && skillId.equalsIgnoreCase(instance.getSkillId())) return instance;
        }
        return instanceId == null && skillId == null ? activeFlow(state) : null;
    }

    public void suspendFlow(DialogState state, FlowInstance instance) {
        requireMutable(instance);
        instance.setStatus("SUSPENDED");
        if (instance.getInstanceId().equals(state.getActiveFlowId())) {
            state.setActiveFlowId(null);
            state.setActiveSkill(null);
            state.setStatus("SUSPENDED");
        }
        syncLegacyState(state, instance, registry.require(instance.getSkillId()));
        touch(state, instance);
    }

    public void resumeFlow(DialogState state, FlowInstance instance) {
        if (!"SUSPENDED".equals(instance.getStatus())) {
            throw new IllegalStateException("Only a suspended flow can be resumed");
        }
        instance.setStatus("ACTIVE");
        state.setStatus("ACTIVE");
        state.setActiveFlowId(instance.getInstanceId());
        state.setActiveSkill(instance.getSkillId());
        SkillDefinition definition = registry.require(instance.getSkillId());
        updateIntent(state, definition);
        syncLegacyState(state, instance, definition);
        touch(state, instance);
    }

    public void cancelFlow(DialogState state, FlowInstance instance) {
        requireMutable(instance);
        SkillDefinition definition = registry.require(instance.getSkillId());
        instance.setStatus("CANCELLED");
        instance.setCurrentStage(terminalStage(definition, "CANCEL"));
        if (instance.getInstanceId().equals(state.getActiveFlowId())) {
            state.setActiveFlowId(null);
            state.setActiveSkill(null);
            state.setStatus("CANCELLED");
        }
        syncLegacyState(state, instance, definition);
        touch(state, instance);
    }

    public void setSlot(DialogState state, FlowInstance instance, String slot, Object value) {
        requireMutable(instance);
        if (value == null) instance.getSlots().remove(slot);
        else instance.getSlots().put(slot, value);
        syncLegacyState(state, instance, registry.require(instance.getSkillId()));
        touch(state, instance);
    }

    public void clearSlot(DialogState state, FlowInstance instance, String slot) {
        setSlot(state, instance, slot, null);
    }

    public void confirmFlow(DialogState state, FlowInstance instance) {
        requireMutable(instance);
        SkillDefinition definition = registry.require(instance.getSkillId());
        FlowStageDefinition stage = currentStage(definition, instance);
        if (!"CONFIRM".equals(stage.getType())) throw new IllegalStateException("Flow is not waiting for confirmation");
        advance(definition, instance);
        syncLegacyState(state, instance, definition);
        touch(state, instance);
    }

    public SkillTransitionResult handle(String traceId, String sessionId, DialogState state,
                                        String skillId, String userMessage) {
        SkillDefinition definition = registry.require(skillId);
        FlowSkillHandler handler = handlers.get(definition.getId());
        if (handler == null) {
            return SkillTransitionResult.notHandled();
        }

        DialogState nextState = state == null ? newDialogState(sessionId, definition) : state;
        FlowInstance instance = activeOrNewInstance(nextState, definition);
        updateIntent(nextState, definition);
        instance.setTurnCount(instance.getTurnCount() + 1);
        touch(nextState, instance);
        FlowContext context = new FlowContext(traceId, sessionId, nextState, instance, definition);

        if (isCancel(userMessage)) {
            return cancel(nextState, instance, definition);
        }

        Map<String, Object> extracted = handler.extractSlots(context, userMessage);
        if (extracted != null) {
            for (Map.Entry<String, Object> entry : extracted.entrySet()) {
                if (entry.getValue() != null) instance.getSlots().put(entry.getKey(), entry.getValue());
            }
        }

        boolean confirmationRevisionHandled = false;
        for (int transition = 0; transition < MAX_STAGE_TRANSITIONS_PER_TURN; transition++) {
            FlowStageDefinition stage = currentStage(definition, instance);
            if (stage.isTerminal()) {
                return terminal(nextState, instance, definition, null, null);
            }
            if ("COLLECT".equals(stage.getType())) {
                DialogueProgress.report("FLOW_COLLECT", "正在核对办理信息", "检查当前业务所需信息是否完整");
                List<String> missing = missingSlots(stage, instance);
                if (!missing.isEmpty()) {
                    return askForSlot(nextState, instance, definition, missing.get(0), extracted == null || extracted.isEmpty());
                }
                advance(definition, instance);
                continue;
            }
            if ("VALIDATE".equals(stage.getType())) {
                DialogueProgress.report("FLOW_VALIDATE", "正在核验业务信息", "校验客户和业务参数");
                FlowValidationResult validation = handler.validate(context);
                if (!validation.isValid()) {
                    for (String slot : validation.getSlotsToClear()) instance.getSlots().remove(slot);
                    instance.setCurrentStage(definition.getFlow().getInitialStage());
                    syncLegacyState(nextState, instance, definition);
                    nextState.setUi(slotUi(definition, validation.getAnswer()));
                    touch(nextState, instance);
                    return result(nextState, validation.getAnswer(), false, validation.getData());
                }
                advance(definition, instance);
                continue;
            }
            if ("EXECUTE".equals(stage.getType())) {
                DialogueProgress.report("FLOW_EXECUTE", "正在调用业务服务", definition.getName());
                FlowExecutionResult execution = handler.execute(context);
                advance(definition, instance);
                FlowStageDefinition nextStage = currentStage(definition, instance);
                if (nextStage.isTerminal()) {
                    return terminal(nextState, instance, definition, execution.getAnswer(), execution.getData());
                }
                syncLegacyState(nextState, instance, definition);
                return result(nextState, execution.getAnswer(), false, execution.getData());
            }
            if ("CONFIRM".equals(stage.getType())) {
                DialogueProgress.report("FLOW_CONFIRM", "正在准备确认信息", "执行前等待您的明确确认");
                if (isConfirm(userMessage)) {
                    advance(definition, instance);
                    continue;
                }
                if (!confirmationRevisionHandled && handler.isConfirmationRevision(context, userMessage)) {
                    confirmationRevisionHandled = true;
                    instance.setCurrentStage(definition.getFlow().getInitialStage());
                    continue;
                }
                FlowConfirmationResult confirmation = handler.prepareConfirmation(context);
                syncLegacyState(nextState, instance, definition);
                nextState.setUi(confirmationUi(definition, confirmation.getAnswer()));
                touch(nextState, instance);
                SkillTransitionResult waiting = result(nextState, confirmation.getAnswer(), false, confirmation.getData());
                waiting.setRequiresConfirmation(true);
                waiting.setConfirmation(confirmation.getConfirmation());
                return waiting;
            }
            advance(definition, instance);
        }
        throw new IllegalStateException("Flow exceeded stage transition limit: " + definition.getFlow().getId());
    }

    private DialogState newDialogState(String sessionId, SkillDefinition definition) {
        DialogState state = new DialogState();
        state.setVersion("2.0");
        state.setSessionId(sessionId);
        state.setMode("FLOW_STACK");
        state.setStatus("ACTIVE");
        DialogIntent intent = new DialogIntent();
        intent.setCurrent(definition.getId());
        intent.setConfidence(0.99D);
        intent.setSource("FLOW_ENGINE");
        state.setIntent(intent);
        return state;
    }

    private void updateIntent(DialogState state, SkillDefinition definition) {
        DialogIntent intent = state.getIntent();
        if (intent == null) intent = new DialogIntent();
        intent.setCurrent(definition.getId());
        intent.setConfidence(0.99D);
        intent.setSource("FLOW_ENGINE");
        state.setIntent(intent);
    }

    private FlowInstance activeOrNewInstance(DialogState state, SkillDefinition definition) {
        if (state.getFlowStack() == null) state.setFlowStack(new ArrayList<FlowInstance>());
        for (int i = state.getFlowStack().size() - 1; i >= 0; i--) {
            FlowInstance candidate = state.getFlowStack().get(i);
            if (definition.getId().equals(candidate.getSkillId()) && "ACTIVE".equals(candidate.getStatus())) {
                state.setActiveSkill(definition.getId());
                state.setActiveFlowId(candidate.getInstanceId());
                return candidate;
            }
        }

        FlowInstance instance = new FlowInstance();
        instance.setInstanceId(UUID.randomUUID().toString());
        instance.setSkillId(definition.getId());
        instance.setFlowId(definition.getFlow().getId());
        instance.setStatus("ACTIVE");
        instance.setCurrentStage(definition.getFlow().getInitialStage());
        instance.setStartedAt(now());
        migrateLegacySlots(state, definition, instance);
        state.getFlowStack().add(instance);
        state.setVersion("2.0");
        state.setMode("FLOW_STACK");
        state.setStatus("ACTIVE");
        state.setActiveSkill(definition.getId());
        state.setActiveFlowId(instance.getInstanceId());
        return instance;
    }

    private void migrateLegacySlots(DialogState state, SkillDefinition definition, FlowInstance instance) {
        SkillDialogState legacy = state.getSkills() == null ? null : state.getSkills().get(definition.getId());
        if (legacy == null || legacy.getSlots() == null) return;
        Object customerId = legacy.getSlots().get("customerId");
        Object customerName = legacy.getSlots().get("customerName");
        if (customerId != null) {
            instance.getSlots().put("customerReference", customerId);
            instance.getSlots().put("customerId", customerId);
        } else if (customerName != null) {
            instance.getSlots().put("customerReference", customerName);
            instance.getSlots().put("customerName", customerName);
        }
    }

    private SkillTransitionResult askForSlot(DialogState state, FlowInstance instance, SkillDefinition definition,
                                             String slotId, boolean noValueExtracted) {
        int retry = count(instance.getRetryCounts().get(slotId));
        if (instance.getTurnCount() > 1 && noValueExtracted) retry++;
        instance.getRetryCounts().put(slotId, retry);
        SlotDefinition slot = slot(definition, slotId);
        String prompt = slot.getPrompts().get(0);
        if (retry > 0) prompt = prompt + " 如果需要办理其他事项，可以先回复“取消”。";
        if (retry >= 3) prompt = "暂时没有识别到所需信息。" + prompt;
        syncLegacyState(state, instance, definition);
        state.setUi(slotUi(definition, prompt));
        touch(state, instance);
        return result(state, prompt, false, null);
    }

    private SkillTransitionResult cancel(DialogState state, FlowInstance instance, SkillDefinition definition) {
        instance.setStatus("CANCELLED");
        instance.setCurrentStage(terminalStage(definition, "CANCEL"));
        state.setStatus("CANCELLED");
        state.setActiveSkill(null);
        syncLegacyState(state, instance, definition);
        String answer = "已结束本次" + definition.getName() + "。";
        state.setUi(resultUi("已取消" + definition.getName(), answer));
        touch(state, instance);
        return result(state, answer, true, null);
    }

    private SkillTransitionResult terminal(DialogState state, FlowInstance instance, SkillDefinition definition,
                                           String answer, Map<String, Object> data) {
        instance.setStatus("COMPLETED");
        state.setStatus("COMPLETED");
        state.setActiveSkill(null);
        syncLegacyState(state, instance, definition);
        String finalAnswer = answer == null ? definition.getName() + "已完成。" : answer;
        state.setUi(resultUi(definition.getName() + "完成", finalAnswer));
        touch(state, instance);
        return result(state, finalAnswer, true, data);
    }

    private void syncLegacyState(DialogState state, FlowInstance instance, SkillDefinition definition) {
        if (state.getSkills() == null) state.setSkills(new LinkedHashMap<String, SkillDialogState>());
        SkillDialogState legacy = state.getSkills().get(definition.getId());
        if (legacy == null) {
            legacy = new SkillDialogState();
            legacy.setSkill(definition.getId());
            state.getSkills().put(definition.getId(), legacy);
        }
        legacy.setStage(instance.getCurrentStage());
        legacy.setStatus(instance.getStatus());
        legacy.setSlots(new LinkedHashMap<String, Object>(instance.getSlots()));
        FlowStageDefinition stage = currentStage(definition, instance);
        legacy.setRequiredSlots(stage.getRequiredSlots() == null
                ? new ArrayList<String>() : new ArrayList<String>(stage.getRequiredSlots()));
    }

    private FlowStageDefinition currentStage(SkillDefinition definition, FlowInstance instance) {
        for (FlowStageDefinition stage : definition.getFlow().getStages()) {
            if (stage.getId().equals(instance.getCurrentStage())) return stage;
        }
        throw new IllegalStateException("Unknown current stage " + instance.getCurrentStage());
    }

    private void advance(SkillDefinition definition, FlowInstance instance) {
        List<FlowStageDefinition> stages = definition.getFlow().getStages();
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).getId().equals(instance.getCurrentStage())) {
                if (i + 1 >= stages.size()) throw new IllegalStateException("No next stage after " + instance.getCurrentStage());
                instance.setCurrentStage(stages.get(i + 1).getId());
                instance.setUpdatedAt(now());
                return;
            }
        }
        throw new IllegalStateException("Unknown stage " + instance.getCurrentStage());
    }

    private List<String> missingSlots(FlowStageDefinition stage, FlowInstance instance) {
        List<String> missing = new ArrayList<String>();
        if (stage.getRequiredSlots() == null) return missing;
        for (String slot : stage.getRequiredSlots()) {
            Object value = instance.getSlots().get(slot);
            if (value == null || String.valueOf(value).trim().length() == 0) missing.add(slot);
        }
        return missing;
    }

    private SlotDefinition slot(SkillDefinition definition, String slotId) {
        for (SlotDefinition slot : definition.getSlots()) if (slotId.equals(slot.getId())) return slot;
        throw new IllegalStateException("Unknown slot " + slotId);
    }

    private String terminalStage(SkillDefinition definition, String type) {
        for (FlowStageDefinition stage : definition.getFlow().getStages()) {
            if (stage.isTerminal() && type.equals(stage.getType())) return stage.getId();
        }
        throw new IllegalStateException("Missing terminal stage " + type + " in " + definition.getFlow().getId());
    }

    private DialogUiHints slotUi(SkillDefinition definition, String prompt) {
        DialogUiHints ui = new DialogUiHints();
        ui.setReplyMode("ASK_SLOT");
        ui.setSummary("正在办理" + definition.getName());
        ui.setPrompt(prompt);
        List<DialogUiAction> actions = new ArrayList<DialogUiAction>();
        actions.add(new DialogUiAction("取消", "CANCEL_FLOW", "secondary"));
        ui.setQuickActions(actions);
        return ui;
    }

    private DialogUiHints resultUi(String summary, String prompt) {
        DialogUiHints ui = new DialogUiHints();
        ui.setReplyMode("RESULT");
        ui.setSummary(summary);
        ui.setPrompt(prompt);
        return ui;
    }

    private DialogUiHints confirmationUi(SkillDefinition definition, String prompt) {
        DialogUiHints ui = new DialogUiHints();
        ui.setReplyMode("CONFIRMATION");
        ui.setSummary(definition.getName() + "确认");
        ui.setPrompt(prompt);
        List<DialogUiAction> actions = new ArrayList<DialogUiAction>();
        actions.add(new DialogUiAction("确认发送", "CONFIRM", "primary"));
        actions.add(new DialogUiAction("修改", "REVISE", "secondary"));
        actions.add(new DialogUiAction("取消", "CANCEL_FLOW", "secondary"));
        ui.setQuickActions(actions);
        return ui;
    }

    private SkillTransitionResult result(DialogState state, String answer, boolean terminal, Map<String, Object> data) {
        SkillTransitionResult result = new SkillTransitionResult();
        result.setHandled(true);
        result.setTerminal(terminal);
        result.setAnswer(answer);
        result.setDialogState(state);
        result.setData(data);
        return result;
    }

    private boolean isCancel(String value) {
        String text = value == null ? "" : value.trim();
        return text.matches(".*(取消|退出|结束|不查了|换个业务).*");
    }

    private boolean isConfirm(String value) {
        String text = value == null ? "" : value.trim();
        return text.matches(".*(确认发送|确认并发送|可以发送|发送吧|同意发送|确认)$");
    }

    private void requireMutable(FlowInstance instance) {
        if (instance == null) throw new IllegalArgumentException("Flow instance is required");
        if ("COMPLETED".equals(instance.getStatus()) || "CANCELLED".equals(instance.getStatus())) {
            throw new IllegalStateException("Terminal flow cannot be changed");
        }
    }

    private int count(Integer value) { return value == null ? 0 : value.intValue(); }
    private String now() { return OffsetDateTime.now(ZoneOffset.ofHours(8)).toString(); }
    private void touch(DialogState state, FlowInstance instance) {
        String value = now();
        state.setUpdatedAt(value);
        instance.setUpdatedAt(value);
    }
}
