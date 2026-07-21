package org.gundy.chat.entity.flow;

import java.util.LinkedHashMap;
import java.util.Map;

public class FlowInstance {
    private String instanceId;
    private String skillId;
    private String flowId;
    private String status;
    private String currentStage;
    private Map<String, Object> slots = new LinkedHashMap<String, Object>();
    private Map<String, Integer> retryCounts = new LinkedHashMap<String, Integer>();
    private int turnCount;
    private String startedAt;
    private String updatedAt;

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getFlowId() { return flowId; }
    public void setFlowId(String flowId) { this.flowId = flowId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrentStage() { return currentStage; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public Map<String, Object> getSlots() { return slots; }
    public void setSlots(Map<String, Object> slots) { this.slots = slots; }
    public Map<String, Integer> getRetryCounts() { return retryCounts; }
    public void setRetryCounts(Map<String, Integer> retryCounts) { this.retryCounts = retryCounts; }
    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
