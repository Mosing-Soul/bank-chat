package org.gundy.chat.flow;

import org.gundy.chat.entity.HistoryMessage;
import org.gundy.chat.entity.RagResponse;
import org.gundy.chat.service.RagService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RagQueryFlowHandler implements FlowSkillHandler {
    public static final String SKILL = "RAG_QUERY";
    private final RagService ragService;

    public RagQueryFlowHandler(RagService ragService) { this.ragService = ragService; }

    public String skillId() { return SKILL; }

    public Map<String, Object> extractSlots(FlowContext context, String userMessage) {
        Map<String, Object> slots = new LinkedHashMap<String, Object>();
        String question = trim(userMessage);
        if (question != null) slots.put("question", question);
        return slots;
    }

    public FlowValidationResult validate(FlowContext context) { return FlowValidationResult.valid(); }

    public FlowExecutionResult execute(FlowContext context) {
        String question = trim(context.getFlowInstance().getSlots().get("question"));
        RagResponse response = ragService.query(question, context.getSessionId(), Collections.<HistoryMessage>emptyList());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if (response != null && response.getSources() != null) data.put("sources", response.getSources());
        String answer = response == null ? "知识查询服务暂时不可用，请稍后再试。" : response.getAnswer();
        return new FlowExecutionResult(answer, data);
    }

    private String trim(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.length() == 0 ? null : text;
    }
}
