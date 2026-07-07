package org.gundy.chat.entity;

import lombok.Data;

@Data
public class ChatRequest {
    private String question;
    private String message;
    private String sessionId;
    private String requestedSkill;
    private Boolean forceSkill;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRequestedSkill() {
        return requestedSkill;
    }

    public void setRequestedSkill(String requestedSkill) {
        this.requestedSkill = requestedSkill;
    }

    public Boolean getForceSkill() {
        return forceSkill;
    }

    public void setForceSkill(Boolean forceSkill) {
        this.forceSkill = forceSkill;
    }

    public String effectiveMessage() {
        if (message != null && message.trim().length() > 0) {
            return message;
        }
        return question;
    }

    public boolean forceSkill() {
        return Boolean.TRUE.equals(forceSkill);
    }
}
