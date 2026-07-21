package org.gundy.chat.entity.command;

import java.util.ArrayList;
import java.util.List;

public class DialogueCommandResponse {
    private String traceId;
    private String sessionId;
    private List<DialogCommand> commands = new ArrayList<DialogCommand>();
    private boolean modelUsed;
    private String reason;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public List<DialogCommand> getCommands() { return commands; }
    public void setCommands(List<DialogCommand> commands) { this.commands = commands; }
    public boolean isModelUsed() { return modelUsed; }
    public void setModelUsed(boolean modelUsed) { this.modelUsed = modelUsed; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
