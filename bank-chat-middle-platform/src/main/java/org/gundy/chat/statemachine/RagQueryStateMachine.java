package org.gundy.chat.statemachine;

import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.flow.FlowEngine;
import org.springframework.stereotype.Component;

@Component
public class RagQueryStateMachine implements SkillStateMachine {
    private final FlowEngine flowEngine;

    public RagQueryStateMachine(FlowEngine flowEngine) { this.flowEngine = flowEngine; }

    public String skillName() { return "RAG_QUERY"; }

    public boolean supports(DialogState state, String userMessage) {
        return state != null && "RAG_QUERY".equals(state.getActiveSkill());
    }

    public SkillTransitionResult handle(String traceId, String sessionId, DialogState state, String userMessage) {
        return flowEngine.handle(traceId, sessionId, state, skillName(), userMessage);
    }
}
