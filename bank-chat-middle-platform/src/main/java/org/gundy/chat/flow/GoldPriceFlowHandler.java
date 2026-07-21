package org.gundy.chat.flow;

import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.entity.HistoryMessage;
import org.gundy.chat.service.AiChatService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GoldPriceFlowHandler implements FlowSkillHandler {
    public static final String SKILL = "GOLD_PRICE";
    private static final Pattern SYMBOL = Pattern.compile("(?i)(AU\\d{4}|XAU(?:/USD)?)");
    private final AiChatService aiChatService;

    public GoldPriceFlowHandler(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    public String skillId() { return SKILL; }

    public Map<String, Object> extractSlots(FlowContext context, String userMessage) {
        Map<String, Object> slots = new LinkedHashMap<String, Object>();
        String query = trim(userMessage);
        if (query == null) return slots;
        slots.put("query", query);
        Matcher matcher = SYMBOL.matcher(query);
        if (matcher.find()) slots.put("marketSymbol", matcher.group(1).toUpperCase());
        return slots;
    }

    public FlowValidationResult validate(FlowContext context) { return FlowValidationResult.valid(); }

    public FlowExecutionResult execute(FlowContext context) {
        String query = trim(context.getFlowInstance().getSlots().get("query"));
        ChatResponse response = aiChatService.invoke(context.getTraceId(), context.getSessionId(), query,
                Collections.<HistoryMessage>emptyList(), SKILL, true);
        Map<String, Object> data = response == null || response.getData() == null
                ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(response.getData());
        if (response != null && response.getSources() != null) data.put("sources", response.getSources());
        String answer = response == null ? "黄金行情服务暂时不可用，请稍后再试。" : response.getAnswer();
        return new FlowExecutionResult(answer, data);
    }

    private String trim(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.length() == 0 ? null : text;
    }
}
