package org.gundy.chat.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FlowValidationResult {
    private boolean valid;
    private String answer;
    private Map<String, Object> data;
    private List<String> slotsToClear = new ArrayList<String>();

    public static FlowValidationResult valid() {
        FlowValidationResult result = new FlowValidationResult();
        result.setValid(true);
        return result;
    }

    public static FlowValidationResult invalid(String answer, Map<String, Object> data, String slotToClear) {
        FlowValidationResult result = new FlowValidationResult();
        result.setValid(false);
        result.setAnswer(answer);
        result.setData(data);
        if (slotToClear != null) result.getSlotsToClear().add(slotToClear);
        return result;
    }

    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
    public List<String> getSlotsToClear() { return slotsToClear; }
    public void setSlotsToClear(List<String> slotsToClear) { this.slotsToClear = slotsToClear; }
}
