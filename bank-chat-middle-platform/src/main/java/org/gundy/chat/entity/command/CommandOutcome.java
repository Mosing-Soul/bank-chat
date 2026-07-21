package org.gundy.chat.entity.command;

public class CommandOutcome {
    private String commandId;
    private DialogCommandType type;
    private String status;
    private String reason;
    private String flowInstanceId;

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public DialogCommandType getType() { return type; }
    public void setType(DialogCommandType type) { this.type = type; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getFlowInstanceId() { return flowInstanceId; }
    public void setFlowInstanceId(String flowInstanceId) { this.flowInstanceId = flowInstanceId; }
}
