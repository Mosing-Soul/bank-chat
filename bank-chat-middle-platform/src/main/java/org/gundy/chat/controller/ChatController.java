package org.gundy.chat.controller;

import lombok.extern.slf4j.Slf4j;
import org.gundy.chat.entity.ChatRequest;
import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.entity.HistoryMessage;
import org.gundy.chat.entity.intent.IntentRouteResult;
import org.gundy.chat.service.AiChatService;
import org.gundy.chat.service.DialogStateMachineService;
import org.gundy.chat.service.DialogStateService;
import org.gundy.chat.service.IntentClarificationService;
import org.gundy.chat.service.IntentRouterService;
import org.gundy.chat.service.MemoryService;
import org.gundy.chat.service.SkillConfigService;
import org.gundy.chat.statemachine.SkillTransitionResult;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final MemoryService memoryService;
    private final AiChatService aiChatService;
    private final DialogStateService dialogStateService;
    private final DialogStateMachineService dialogStateMachineService;
    private final IntentClarificationService intentClarificationService;
    private final IntentRouterService intentRouterService;
    private final SkillConfigService skillConfigService;

    public ChatController(MemoryService memoryService,
                          AiChatService aiChatService,
                          DialogStateService dialogStateService,
                          DialogStateMachineService dialogStateMachineService,
                          IntentClarificationService intentClarificationService,
                          IntentRouterService intentRouterService,
                          SkillConfigService skillConfigService) {
        this.memoryService = memoryService;
        this.aiChatService = aiChatService;
        this.dialogStateService = dialogStateService;
        this.dialogStateMachineService = dialogStateMachineService;
        this.intentClarificationService = intentClarificationService;
        this.intentRouterService = intentRouterService;
        this.skillConfigService = skillConfigService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request,
                                             @RequestHeader(value = "X-Trace-Id", required = false) String requestTraceId) {
        String traceId = hasText(requestTraceId) ? requestTraceId : UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long start = System.currentTimeMillis();
        try {
            String sessionId = hasText(request.getSessionId()) ? request.getSessionId() : UUID.randomUUID().toString();
            String userMessage = request.effectiveMessage();
            if (!hasText(userMessage)) {
                return ResponseEntity.badRequest().body(ChatResponse.friendlyError(
                        traceId, sessionId, "请输入要咨询或办理的内容。"));
            }

            org.gundy.chat.entity.dialog.DialogState dialogState = dialogStateService.getState(sessionId);
            IntentRouteResult route = intentRouterService.route(dialogState, userMessage,
                    request.getRequestedSkill(), request.forceSkill());
            String effectiveRequestedSkill = hasText(route.getRequestedSkill()) ? route.getRequestedSkill() : request.getRequestedSkill();
            boolean effectiveForceSkill = request.forceSkill() || route.isForceSkill();

            SkillTransitionResult transition = dialogStateMachineService.handle(
                    traceId, sessionId, dialogState, userMessage,
                    effectiveRequestedSkill, effectiveForceSkill);
            if (transition != null && transition.isHandled()) {
                ChatResponse response = new ChatResponse();
                response.setTraceId(traceId);
                response.setSessionId(sessionId);
                response.setIntent(transitionIntent(transition));
                response.setConfidence(0.95D);
                response.setAnswer(transition.getAnswer());
                response.setData(transition.getData());
                response.setRequiresConfirmation(transition.isRequiresConfirmation());
                response.setConfirmation(transition.getConfirmation());
                response.setDialogState(transition.getDialogState());
                if (transition.isTerminal()) {
                    dialogStateService.clearState(sessionId);
                } else {
                    dialogStateService.saveState(sessionId, transition.getDialogState());
                }
                if (hasText(response.getAnswer())) {
                    memoryService.addConversation(sessionId, userMessage, response.getAnswer());
                }
                log.info("sessionId={}, intent={}, status={}, durationMs={}",
                        sessionId, response.getIntent(), "STATE_MACHINE",
                        System.currentTimeMillis() - start);
                return ResponseEntity.ok(response);
            }
            boolean clearStateBeforeAi = (transition != null && transition.isClearState()) || route.isClearHistory();
            if (clearStateBeforeAi) {
                dialogStateService.clearState(sessionId);
            }

            List<HistoryMessage> history = clearStateBeforeAi
                    ? Collections.<HistoryMessage>emptyList()
                    : memoryService.getHistory(sessionId);
            ChatResponse response = aiChatService.invoke(traceId, sessionId, userMessage, history,
                    effectiveRequestedSkill, effectiveForceSkill, route.getRequestedSkill(),
                    route.getConfidence(), route.entityMap(), route.getDialogAct(),
                    skillConfigService.examplesPayload());
            if (response == null) {
                response = ChatResponse.friendlyError(traceId, sessionId, "AI 服务暂时不可用，请稍后再试。");
            }
            response.setTraceId(hasText(response.getTraceId()) ? response.getTraceId() : traceId);
            response.setSessionId(hasText(response.getSessionId()) ? response.getSessionId() : sessionId);

            ChatResponse clarification = intentClarificationService.maybeClarify(
                    traceId, sessionId, userMessage, route, effectiveForceSkill, response);
            if (clarification != null) {
                log.info("sessionId={}, intent={}, status={}, durationMs={}",
                        sessionId, clarification.getIntent(), "CLARIFICATION",
                        System.currentTimeMillis() - start);
                return ResponseEntity.ok(clarification);
            }

            if (hasText(response.getAnswer()) && response.getError() == null) {
                memoryService.addConversation(sessionId, userMessage, response.getAnswer());
            }
            log.info("sessionId={}, intent={}, status={}, durationMs={}",
                    sessionId, response.getIntent(), response.getError() == null ? "SUCCESS" : "ERROR",
                    System.currentTimeMillis() - start);
            return ResponseEntity.ok(response);
        } catch (ResourceAccessException ex) {
            log.warn("python chat timeout or unavailable, durationMs={}", System.currentTimeMillis() - start);
            return ResponseEntity.ok(ChatResponse.friendlyError(traceId, safeSessionId(request), "AI 服务响应超时或不可用，请稍后再试。"));
        } catch (RestClientException ex) {
            log.warn("python chat call failed, durationMs={}", System.currentTimeMillis() - start);
            return ResponseEntity.ok(ChatResponse.friendlyError(traceId, safeSessionId(request), "AI 服务调用失败，请稍后再试。"));
        } finally {
            MDC.remove("traceId");
        }
    }

    private String safeSessionId(ChatRequest request) {
        return request != null && hasText(request.getSessionId()) ? request.getSessionId() : UUID.randomUUID().toString();
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }

    private String transitionIntent(SkillTransitionResult transition) {
        if (transition.getDialogState() != null && transition.getDialogState().getIntent() != null
                && hasText(transition.getDialogState().getIntent().getCurrent())) {
            return transition.getDialogState().getIntent().getCurrent();
        }
        return "STATE_MACHINE";
    }
}
