package org.gundy.chat.entity.command;

import org.gundy.chat.entity.HistoryMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DialogueCommandRequest {
    private String traceId;
    private String sessionId;
    private String message;
    private List<Map<String, Object>> flowStack = new ArrayList<Map<String, Object>>();
    private List<Map<String, Object>> candidateSkills = new ArrayList<Map<String, Object>>();
    private List<HistoryMessage> history = new ArrayList<HistoryMessage>();

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<Map<String, Object>> getFlowStack() { return flowStack; }
    public void setFlowStack(List<Map<String, Object>> flowStack) { this.flowStack = flowStack; }
    public List<Map<String, Object>> getCandidateSkills() { return candidateSkills; }
    public void setCandidateSkills(List<Map<String, Object>> candidateSkills) { this.candidateSkills = candidateSkills; }
    public List<HistoryMessage> getHistory() { return history; }
    public void setHistory(List<HistoryMessage> history) { this.history = history; }
}
