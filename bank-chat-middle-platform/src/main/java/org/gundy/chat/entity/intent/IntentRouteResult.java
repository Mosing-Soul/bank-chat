package org.gundy.chat.entity.intent;

import java.util.Map;

public class IntentRouteResult {
    private String requestedSkill;
    private boolean forceSkill;
    private boolean clearHistory;
    private double confidence;
    private String reason;
    private ExtractedEntities entities;

    public String getRequestedSkill() { return requestedSkill; }
    public void setRequestedSkill(String requestedSkill) { this.requestedSkill = requestedSkill; }
    public boolean isForceSkill() { return forceSkill; }
    public void setForceSkill(boolean forceSkill) { this.forceSkill = forceSkill; }
    public boolean isClearHistory() { return clearHistory; }
    public void setClearHistory(boolean clearHistory) { this.clearHistory = clearHistory; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public ExtractedEntities getEntities() { return entities; }
    public void setEntities(ExtractedEntities entities) { this.entities = entities; }

    public Map<String, Object> entityMap() {
        return entities == null ? null : entities.toMap();
    }
}
