package org.gundy.chat.entity;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AiChatRequest {
    private String traceId;
    private String sessionId;
    private String message;
    private List<HistoryMessage> history;
    private String requestedSkill;
    private Boolean forceSkill;
    private String routerIntent;
    private Double routerConfidence;
    private Map<String, Object> entities;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<HistoryMessage> getHistory() { return history; }
    public void setHistory(List<HistoryMessage> history) { this.history = history; }
    public String getRequestedSkill() { return requestedSkill; }
    public void setRequestedSkill(String requestedSkill) { this.requestedSkill = requestedSkill; }
    public Boolean getForceSkill() { return forceSkill; }
    public void setForceSkill(Boolean forceSkill) { this.forceSkill = forceSkill; }
    public String getRouterIntent() { return routerIntent; }
    public void setRouterIntent(String routerIntent) { this.routerIntent = routerIntent; }
    public Double getRouterConfidence() { return routerConfidence; }
    public void setRouterConfidence(Double routerConfidence) { this.routerConfidence = routerConfidence; }
    public Map<String, Object> getEntities() { return entities; }
    public void setEntities(Map<String, Object> entities) { this.entities = entities; }
}
