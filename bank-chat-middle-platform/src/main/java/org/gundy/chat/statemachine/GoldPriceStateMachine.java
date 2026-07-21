package org.gundy.chat.statemachine;

import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.flow.FlowEngine;
import org.springframework.stereotype.Component;

@Component
public class GoldPriceStateMachine implements SkillStateMachine {
    private final FlowEngine flowEngine;
    public GoldPriceStateMachine(FlowEngine flowEngine) { this.flowEngine = flowEngine; }
    public String skillName() { return "GOLD_PRICE"; }
    public boolean supports(DialogState state, String userMessage) {
        return state != null && "GOLD_PRICE".equals(state.getActiveSkill());
    }
    public SkillTransitionResult handle(String traceId, String sessionId, DialogState state, String userMessage) {
        return flowEngine.handle(traceId, sessionId, state, skillName(), userMessage);
    }
}
