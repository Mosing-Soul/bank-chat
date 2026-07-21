package org.gundy.chat.entity.command;

import java.util.LinkedHashMap;
import java.util.Map;

public class DialogCommand {
    private String commandId;
    private DialogCommandType type;
    private String targetSkill;
    private String targetFlowInstanceId;
    private String slot;
    private Object value;
    private Map<String, Object> slots = new LinkedHashMap<String, Object>();
    private double confidence;
    private String reason;

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public DialogCommandType getType() { return type; }
    public void setType(DialogCommandType type) { this.type = type; }
    public String getTargetSkill() { return targetSkill; }
    public void setTargetSkill(String targetSkill) { this.targetSkill = targetSkill; }
    public String getTargetFlowInstanceId() { return targetFlowInstanceId; }
    public void setTargetFlowInstanceId(String targetFlowInstanceId) { this.targetFlowInstanceId = targetFlowInstanceId; }
    public String getSlot() { return slot; }
    public void setSlot(String slot) { this.slot = slot; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
    public Map<String, Object> getSlots() { return slots; }
    public void setSlots(Map<String, Object> slots) { this.slots = slots; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
