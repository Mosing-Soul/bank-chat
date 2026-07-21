package org.gundy.chat.statemachine;

import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.flow.FlowEngine;
import org.springframework.stereotype.Component;

@Component
public class MessageSendStateMachine implements SkillStateMachine {
    public static final String SKILL = "MESSAGE_SEND";
    private final FlowEngine flowEngine;

    public MessageSendStateMachine(FlowEngine flowEngine) { this.flowEngine = flowEngine; }
    public String skillName() { return SKILL; }
    public boolean supports(DialogState state, String userMessage) {
        if (state != null && SKILL.equals(state.getActiveSkill())) return true;
        String text = userMessage == null ? "" : userMessage.trim();
        return text.matches(".*(发消息|发送消息|生成客户消息|生成消息|消息预览|到期提醒|资产配置提醒|给.*提醒|给.*通知).*");
    }
    public SkillTransitionResult handle(String traceId, String sessionId, DialogState state, String userMessage) {
        return flowEngine.handle(traceId, sessionId, state, SKILL, userMessage);
    }
}
