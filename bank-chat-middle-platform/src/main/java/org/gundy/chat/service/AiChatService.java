package org.gundy.chat.service;

import lombok.extern.log4j.Log4j;
import lombok.extern.slf4j.Slf4j;
import org.gundy.chat.entity.AiChatRequest;
import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.entity.HistoryMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class AiChatService {
    private final RestTemplate restTemplate;
    private final String aiChatUrl;

    @Autowired
    public AiChatService(RestTemplate restTemplate,
                         @Value("${ai.chat.url:${AI_CHAT_URL:http://localhost:8000/ai/chat/invoke}}") String aiChatUrl) {
        this.restTemplate = restTemplate;
        this.aiChatUrl = aiChatUrl;
    }

    public ChatResponse invoke(String traceId, String sessionId, String message, List<HistoryMessage> history) {
        return invoke(traceId, sessionId, message, history, null, false);
    }

    public ChatResponse invoke(String traceId, String sessionId, String message, List<HistoryMessage> history,
                               String requestedSkill, boolean forceSkill) {
        return invoke(traceId, sessionId, message, history, requestedSkill, forceSkill, null, null, null, null, null);
    }

    public ChatResponse invoke(String traceId, String sessionId, String message, List<HistoryMessage> history,
                               String requestedSkill, boolean forceSkill, String routerIntent,
                               Double routerConfidence, Map<String, Object> entities, String dialogAct,
                               Map<String, Object> skillExamples) {
        AiChatRequest request = new AiChatRequest();
        request.setTraceId(traceId);
        request.setSessionId(sessionId);
        request.setMessage(message);
        request.setHistory(history == null ? Collections.<HistoryMessage>emptyList() : history);
        request.setRequestedSkill(requestedSkill);
        request.setForceSkill(forceSkill);
        request.setRouterIntent(routerIntent);
        request.setRouterConfidence(routerConfidence);
        request.setEntities(entities == null ? new LinkedHashMap<String, Object>() : entities);
        request.setDialogAct(dialogAct);
        request.setSkillExamples(skillExamples == null ? new LinkedHashMap<String, Object>() : skillExamples);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Trace-Id", traceId);
        HttpEntity<AiChatRequest> entity = new HttpEntity<AiChatRequest>(request, headers);
        ResponseEntity<ChatResponse> response = restTemplate.exchange(
                aiChatUrl, HttpMethod.POST, entity, ChatResponse.class);
        log.info("ai chat invoke, response={}", response);
        return response.getBody();
    }
}
