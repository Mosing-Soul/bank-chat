package org.gundy.chat.entity;

import lombok.Data;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.exception.ErrorCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ChatResponse {
    private String traceId;
    private String sessionId;
    private String intent;
    private Double confidence;
    private String answer;
    private Map<String, Object> data;
    private List<Map<String, Object>> citations;
    private List<String> sources;
    private Boolean requiresConfirmation;
    private Map<String, Object> confirmation;
    private List<Map<String, Object>> skillCalls;
    private Map<String, Object> error;
    private DialogState dialogState;

    public static ChatResponse friendlyError(String traceId, String sessionId, String message) {
        return failure(traceId, sessionId, ErrorCode.INTERNAL_ERROR, message);
    }

    public static ChatResponse failure(String traceId, String sessionId, ErrorCode errorCode) {
        return failure(traceId, sessionId, errorCode, errorCode.getMessage());
    }

    private static ChatResponse failure(String traceId, String sessionId, ErrorCode errorCode, String message) {
        ChatResponse response = new ChatResponse();
        response.setTraceId(traceId);
        response.setSessionId(sessionId);
        response.setIntent("UNKNOWN");
        response.setConfidence(0.0D);
        response.setAnswer(message);
        response.setRequiresConfirmation(false);
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", errorCode.name());
        error.put("message", message);
        error.put("traceId", traceId);
        error.put("retryable", errorCode.isRetryable());
        response.setError(error);
        return response;
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
    public List<Map<String, Object>> getCitations() { return citations; }
    public void setCitations(List<Map<String, Object>> citations) { this.citations = citations; }
    public List<String> getSources() { return sources; }
    public void setSources(List<String> sources) { this.sources = sources; }
    public Boolean getRequiresConfirmation() { return requiresConfirmation; }
    public void setRequiresConfirmation(Boolean requiresConfirmation) { this.requiresConfirmation = requiresConfirmation; }
    public Map<String, Object> getConfirmation() { return confirmation; }
    public void setConfirmation(Map<String, Object> confirmation) { this.confirmation = confirmation; }
    public List<Map<String, Object>> getSkillCalls() { return skillCalls; }
    public void setSkillCalls(List<Map<String, Object>> skillCalls) { this.skillCalls = skillCalls; }
    public Map<String, Object> getError() { return error; }
    public void setError(Map<String, Object> error) { this.error = error; }
    public DialogState getDialogState() { return dialogState; }
    public void setDialogState(DialogState dialogState) { this.dialogState = dialogState; }
}
