package org.gundy.chat.flow;

import java.util.Map;

public class FlowExecutionResult {
    private String answer;
    private Map<String, Object> data;

    public FlowExecutionResult() {}

    public FlowExecutionResult(String answer, Map<String, Object> data) {
        this.answer = answer;
        this.data = data;
    }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
}
