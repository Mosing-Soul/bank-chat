package org.gundy.chat.flow;

import java.util.Map;

public class FlowConfirmationResult {
    private final String answer;
    private final Map<String, Object> data;
    private final Map<String, Object> confirmation;

    public FlowConfirmationResult(String answer, Map<String, Object> data, Map<String, Object> confirmation) {
        this.answer = answer;
        this.data = data;
        this.confirmation = confirmation;
    }

    public String getAnswer() { return answer; }
    public Map<String, Object> getData() { return data; }
    public Map<String, Object> getConfirmation() { return confirmation; }
}
