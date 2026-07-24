package org.gundy.chat.flow;

import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.entity.RagResponse;
import org.gundy.chat.entity.definition.SkillDefinition;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.gundy.chat.service.AiChatService;
import org.gundy.chat.service.RagService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadOnlyFlowHandlerTest {
    @Test
    void ragFlowKeepsQuestionAndReturnsSources() {
        RagService service = mock(RagService.class);
        RagResponse response = new RagResponse();
        response.setAnswer("临时冻结最长不超过48小时。");
        response.setSources(Arrays.asList("rule.pdf"));
        when(service.query(eq("反洗钱法中，临时冻结的最长时限是48小时吗？"), eq("s1"), anyList())).thenReturn(response);
        RagQueryFlowHandler handler = new RagQueryFlowHandler(service);
        FlowContext context = context("trace-1", "s1", "RAG_QUERY");

        Map<String, Object> slots = handler.extractSlots(context, "反洗钱法中，临时冻结的最长时限是48小时吗？");
        context.getFlowInstance().getSlots().putAll(slots);
        FlowExecutionResult result = handler.execute(context);

        assertThat(slots).containsEntry("question", "反洗钱法中，临时冻结的最长时限是48小时吗？");
        assertThat(result.getAnswer()).isEqualTo("临时冻结最长不超过48小时。");
        assertThat(result.getData()).containsEntry("sources", Arrays.asList("rule.pdf"));
    }

    @Test
    void goldFlowForcesGoldSkillAndExtractsSymbol() {
        AiChatService service = mock(AiChatService.class);
        ChatResponse response = new ChatResponse();
        response.setAnswer("Au9999 当前价格");
        when(service.invoke(eq("trace-2"), eq("s2"), eq("Au9999现在多少钱"), anyList(),
                eq("GOLD_PRICE"), eq(true))).thenReturn(response);
        GoldPriceFlowHandler handler = new GoldPriceFlowHandler(service);
        FlowContext context = context("trace-2", "s2", "GOLD_PRICE");

        Map<String, Object> slots = handler.extractSlots(context, "Au9999现在多少钱");
        context.getFlowInstance().getSlots().putAll(slots);
        FlowExecutionResult result = handler.execute(context);

        assertThat(slots).containsEntry("query", "Au9999现在多少钱").containsEntry("marketSymbol", "AU9999");
        assertThat(result.getAnswer()).isEqualTo("Au9999 当前价格");
        verify(service).invoke(eq("trace-2"), eq("s2"), eq("Au9999现在多少钱"), anyList(),
                eq("GOLD_PRICE"), eq(true));
    }

    private FlowContext context(String traceId, String sessionId, String skillId) {
        FlowInstance instance = new FlowInstance();
        instance.setSkillId(skillId);
        return new FlowContext(traceId, sessionId, new DialogState(), instance, new SkillDefinition());
    }
}
