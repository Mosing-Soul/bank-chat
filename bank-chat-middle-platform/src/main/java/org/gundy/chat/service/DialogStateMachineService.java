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
        if (skillName == null || skillName.trim().length() == 0) {
            return null;
        }
        String value = skillName.trim().toUpperCase();
        if ("MESSAGE".equals(value) || "MESSAGE_SEND".equals(value)) {
            return "MESSAGE_SEND";
        }
        if ("RAG".equals(value) || "RAG_QUERY".equals(value) || "RULE_QUERY".equals(value)) {
            return "RAG_QUERY";
        }
        if ("CUSTOMER_AUM".equals(value) || "AUM".equals(value)) {
            return "CUSTOMER_AUM";
        }
        if ("GOLD".equals(value) || "GOLD_PRICE".equals(value)) {
            return "GOLD_PRICE";
        }
        return value;
    }

    private String detectIntent(String userMessage) {
        String value = userMessage == null ? "" : userMessage.trim();
        if (value.matches(".*(AUM|aum|\u8d44\u4ea7|\u5ba2\u6237).*(\u67e5\u8be2|\u67e5|\u591a\u5c11).*")) {
            return "CUSTOMER_AUM";
        }
        if (value.matches(".*(\u9ec4\u91d1|\u91d1\u4ef7).*(\u4ef7\u683c|\u591a\u5c11|\u67e5\u8be2|\u73b0\u5728).*")) {
            return "GOLD_PRICE";
        }
        if (value.matches(".*(\u89c4\u5219|\u5236\u5ea6|\u6587\u6863|\u8d4e\u56de|\u63d0\u524d\u8d4e\u56de|\u600e\u4e48\u529e).*")) {
            return "RAG_QUERY";
        }
        if (value.matches(".*(\u53d1\u6d88\u606f|\u53d1\u9001\u6d88\u606f|\u751f\u6210\u5ba2\u6237\u6d88\u606f|\u5230\u671f\u63d0\u9192|\u8d44\u4ea7\u914d\u7f6e\u63d0\u9192|\u7ed9.*\u63d0\u9192|\u7ed9.*\u901a\u77e5).*")) {
            return "MESSAGE_SEND";
        }
        return null;
    }

    private boolean isExplicitSwitch(String userMessage) {
        String value = userMessage == null ? "" : userMessage.trim();
        return value.matches(".*(\u5148\u522b|\u5148\u4e0d|\u653e\u4e00\u653e|\u6362\u4e2a\u95ee\u9898|\u4e0d\u662f|\u6211\u4e0d\u662f\u8981|\u53d6\u6d88\u521a\u624d|\u91cd\u65b0\u95ee|\u53e6\u4e00\u4e2a\u95ee\u9898|\u5148\u67e5|\u5e2e\u6211\u67e5|\u6211\u60f3\u95ee).*");
    }
}
