package org.gundy.chat.flow;

import org.gundy.chat.entity.definition.SkillDefinition;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;

public class FlowContext {
    private final String traceId;
    private final String sessionId;
    private final DialogState dialogState;
    private final FlowInstance flowInstance;
    private final SkillDefinition skillDefinition;

    public FlowContext(String traceId, String sessionId, DialogState dialogState,
                       FlowInstance flowInstance, SkillDefinition skillDefinition) {
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.dialogState = dialogState;
        this.flowInstance = flowInstance;
        this.skillDefinition = skillDefinition;
    }

    public String getTraceId() { return traceId; }
    public String getSessionId() { return sessionId; }
    public DialogState getDialogState() { return dialogState; }
    public FlowInstance getFlowInstance() { return flowInstance; }
    public SkillDefinition getSkillDefinition() { return skillDefinition; }
}
