package org.gundy.chat.entity;

import java.util.List;

public class ChatResponse {

    private String answer;
    private List<String> sources;
    private String sessionId;

    public ChatResponse() {}

    public ChatResponse(String answer, List<String> sources, String sessionId) {
        this.answer = answer;
        this.sources = sources;
        this.sessionId = sessionId;
    }

    // getters and setters
    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}