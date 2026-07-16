package org.gundy.chat.service;

import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.entity.intent.IntentRouteResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentClarificationServiceTest {
    private final IntentClarificationService service = new IntentClarificationService(new SkillConfigService());

    @Test
    void clarifiesAmbiguousBusinessQuestionWhenModelConfidenceIsLow() {
        IntentRouteResult route = noRoute();
        ChatResponse aiResponse = new ChatResponse();
        aiResponse.setIntent("CUSTOMER_AUM");
        aiResponse.setConfidence(0.52D);
        aiResponse.setAnswer("mock answer");

        ChatResponse clarification = service.maybeClarify(
                "trace-1", "s1", "帮我查一下客户等级", route, false, aiResponse);

        assertThat(clarification).isNotNull();
        assertThat(clarification.getIntent()).isEqualTo("CLARIFICATION");
        assertThat(clarification.getRequiresConfirmation()).isTrue();
        assertThat(clarification.getConfirmation()).containsEntry("type", "INTENT_CLARIFICATION");
        assertThat(clarification.getConfirmation().get("candidates")).isNotNull();
    }

    @Test
    void doesNotClarifyGeneralChatQuestion() {
        IntentRouteResult route = noRoute();
        ChatResponse aiResponse = new ChatResponse();
        aiResponse.setIntent("GENERAL_CHAT");
        aiResponse.setConfidence(0.81D);
        aiResponse.setAnswer("mock answer");

        ChatResponse clarification = service.maybeClarify(
                "trace-1", "s1", "你好，介绍一下你自己", route, false, aiResponse);

        assertThat(clarification).isNull();
    }

    @Test
    void doesNotClarifyForcedFrontendSkill() {
        IntentRouteResult route = noRoute();
        ChatResponse aiResponse = new ChatResponse();
        aiResponse.setIntent("UNKNOWN");
        aiResponse.setConfidence(0.2D);

        ChatResponse clarification = service.maybeClarify(
                "trace-1", "s1", "客户等级", route, true, aiResponse);

        assertThat(clarification).isNull();
    }

    private IntentRouteResult noRoute() {
        IntentRouteResult result = new IntentRouteResult();
        result.setConfidence(0.0D);
        result.setReason("no deterministic route");
        result.setDialogAct("NO_DETERMINISTIC_ROUTE");
        return result;
    }
}
