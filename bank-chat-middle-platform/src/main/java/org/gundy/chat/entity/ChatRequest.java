package org.gundy.chat.entity;

import javax.validation.constraints.NotBlank;

public class ChatRequest {

    @NotBlank(message = "问题不能为空")
    private String question;

    private String sessionId;   // 可选，不传则后端自动生成

    // getters and setters
    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}