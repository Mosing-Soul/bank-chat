package org.gundy.chat.entity;

import lombok.Data;

import java.util.List;

@Data
public class RagResponse {

    private String answer;
    private List<String> sources;

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
}
