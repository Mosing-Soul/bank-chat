package org.gundy.chat.entity.dialog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SkillDialogState {
    private String skill;
    private String stage;
    private String status;
    private Map<String, Object> slots = new LinkedHashMap<String, Object>();
    private List<String> requiredSlots = new ArrayList<String>();
    private List<String> optionalSlots = new ArrayList<String>();
    private Map<String, Object> lastOutput;
    private Map<String, Object> confirmation;
    private String expiresAt;

    public String getSkill() { return skill; }
    public void setSkill(String skill) { this.skill = skill; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Map<String, Object> getSlots() { return slots; }
    public void setSlots(Map<String, Object> slots) { this.slots = slots; }
    public List<String> getRequiredSlots() { return requiredSlots; }
    public void setRequiredSlots(List<String> requiredSlots) { this.requiredSlots = requiredSlots; }
    public List<String> getOptionalSlots() { return optionalSlots; }
    public void setOptionalSlots(List<String> optionalSlots) { this.optionalSlots = optionalSlots; }
    public Map<String, Object> getLastOutput() { return lastOutput; }
    public void setLastOutput(Map<String, Object> lastOutput) { this.lastOutput = lastOutput; }
    public Map<String, Object> getConfirmation() { return confirmation; }
    public void setConfirmation(Map<String, Object> confirmation) { this.confirmation = confirmation; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
}
