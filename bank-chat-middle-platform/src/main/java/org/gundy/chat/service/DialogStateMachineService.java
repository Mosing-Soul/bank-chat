package org.gundy.chat.service;

import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.statemachine.SkillStateMachine;
import org.gundy.chat.statemachine.SkillTransitionResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DialogStateMachineService {
    private final List<SkillStateMachine> stateMachines;

    public DialogStateMachineService(List<SkillStateMachine> stateMachines) {
        this.stateMachines = stateMachines;
    }

    public SkillTransitionResult handle(String traceId, String sessionId, DialogState state, String userMessage) {
        return handle(traceId, sessionId, state, userMessage, null, false);
    }

    public SkillTransitionResult handle(String traceId, String sessionId, DialogState state, String userMessage,
                                        String requestedSkill, boolean forceSkill) {
        String normalizedRequestedSkill = normalizeSkill(requestedSkill);
        if (normalizedRequestedSkill != null) {
            SkillStateMachine requestedStateMachine = findStateMachine(normalizedRequestedSkill);
            if (forceSkill || state == null || state.getActiveSkill() == null
                    || normalizedRequestedSkill.equals(state.getActiveSkill())) {
                if (requestedStateMachine != null) {
                    DialogState nextState = normalizedRequestedSkill.equals(state == null ? null : state.getActiveSkill())
                            ? state : null;
                    return requestedStateMachine.handle(traceId, sessionId, nextState, userMessage);
                }
                return clearAndPassThrough();
            }
        }

        if (state != null && state.getActiveSkill() != null) {
            String detectedIntent = detectIntent(userMessage);
            if (isExplicitSwitch(userMessage) && detectedIntent != null
                    && !state.getActiveSkill().equals(detectedIntent)) {
                SkillStateMachine requestedStateMachine = findStateMachine(detectedIntent);
                if (requestedStateMachine != null) {
                    return requestedStateMachine.handle(traceId, sessionId, null, userMessage);
                }
                return clearAndPassThrough();
            }
            for (SkillStateMachine stateMachine : stateMachines) {
                if (state.getActiveSkill().equals(stateMachine.skillName())) {
                    return stateMachine.handle(traceId, sessionId, state, userMessage);
                }
            }
        }

        for (SkillStateMachine stateMachine : stateMachines) {
            if (stateMachine.supports(state, userMessage)) {
                return stateMachine.handle(traceId, sessionId, state, userMessage);
            }
        }
        return SkillTransitionResult.notHandled();
    }

    private SkillStateMachine findStateMachine(String skillName) {
        for (SkillStateMachine stateMachine : stateMachines) {
            if (skillName.equals(stateMachine.skillName())) {
                return stateMachine;
            }
        }
        return null;
    }

    private SkillTransitionResult clearAndPassThrough() {
        SkillTransitionResult result = SkillTransitionResult.notHandled();
        result.setClearState(true);
        return result;
    }

    private String normalizeSkill(String skillName) {
        if (skillName == null) return null;
        String value = skillName.trim().toUpperCase();
        if ("RAG_QUERY".equals(value) || "RULE_QUERY".equals(value)) return "RAG_QUERY";
        if ("GOLD_PRICE".equals(value) || "EXTERNAL_SEARCH".equals(value)) return "GOLD_PRICE";
        return null;
    }

    private String detectIntent(String userMessage) {
        return null;
    }

    private boolean isExplicitSwitch(String userMessage) {
        return false;
    }
}
