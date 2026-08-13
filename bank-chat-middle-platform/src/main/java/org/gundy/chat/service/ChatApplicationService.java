package org.gundy.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.gundy.chat.entity.ChatRequest;
import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.entity.HistoryMessage;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.gundy.chat.entity.intent.IntentRouteResult;
import org.gundy.chat.progress.DialogueProgress;
import org.gundy.chat.statemachine.SkillTransitionResult;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class ChatApplicationService {
    private final MemoryService memoryService;
    private final AiChatService aiChatService;
    private final DialogStateService dialogStateService;
    private final DialogStateMachineService dialogStateMachineService;
    private final DialogueOrchestrationService dialogueOrchestrationService;
    private final IntentClarificationService intentClarificationService;
    private final IntentRouterService intentRouterService;
    private final SkillConfigService skillConfigService;

    public ChatApplicationService(MemoryService memoryService,
                                  AiChatService aiChatService,
                                  DialogStateService dialogStateService,
                                  DialogStateMachineService dialogStateMachineService,
                                  DialogueOrchestrationService dialogueOrchestrationService,
                                  IntentClarificationService intentClarificationService,
                                  IntentRouterService intentRouterService,
                                  SkillConfigService skillConfigService) {
        this.memoryService = memoryService;
        this.aiChatService = aiChatService;
        this.dialogStateService = dialogStateService;
        this.dialogStateMachineService = dialogStateMachineService;
        this.dialogueOrchestrationService = dialogueOrchestrationService;
        this.intentClarificationService = intentClarificationService;
        this.intentRouterService = intentRouterService;
        this.skillConfigService = skillConfigService;
    }

    public ChatResponse handle(String traceId, String sessionId, String userMessage, ChatRequest request) {
        long start = System.currentTimeMillis();
        DialogState dialogState = dialogStateService.getState(sessionId);
        List<HistoryMessage> currentHistory = memoryService.getHistory(sessionId);
        DialogueProgress.report("CONTEXT_READY", "已读取对话上下文", dialogState == null
                ? "开始新的办理事项" : "继续当前办理事项");

        SkillTransitionResult commandTransition = dialogueOrchestrationService.tryHandle(
                traceId, sessionId, userMessage, dialogState, currentHistory,
                request.getRequestedSkill(), request.forceSkill());
        if (isHandled(commandTransition)) {
            return transitionResponse(traceId, sessionId, userMessage, commandTransition, start, "COMMAND_FLOW");
        }

        IntentRouteResult route = intentRouterService.route(
                dialogState, userMessage, request.getRequestedSkill(), request.forceSkill());
        DialogueProgress.report("ROUTE_READY", "已匹配服务能力", hasText(route.getRequestedSkill())
                ? "准备进入对应业务流程" : "准备生成回答");
        String requestedSkill = hasText(route.getRequestedSkill())
                ? route.getRequestedSkill() : request.getRequestedSkill();
        boolean forceSkill = request.forceSkill() || route.isForceSkill();

        SkillTransitionResult transition = dialogStateMachineService.handle(
                traceId, sessionId, dialogState, userMessage, requestedSkill, forceSkill);
        if (isHandled(transition)) {
            return transitionResponse(traceId, sessionId, userMessage, transition, start, "STATE_MACHINE");
        }

        boolean clearState = (transition != null && transition.isClearState()) || route.isClearHistory();
        if (clearState) {
            dialogStateService.clearState(sessionId);
        }
        List<HistoryMessage> history = clearState
                ? Collections.<HistoryMessage>emptyList() : currentHistory;
        DialogueProgress.report("RESPONSE_GENERATION", "正在整理查询结果", "生成清晰、可核验的答复");
        ChatResponse response = aiChatService.invoke(
                traceId, sessionId, userMessage, history, requestedSkill, forceSkill,
                route.getRequestedSkill(), route.getConfidence(), route.entityMap(), route.getDialogAct(),
                skillConfigService.examplesPayload());
        response = normalizeResponse(response, traceId, sessionId);

        ChatResponse clarification = intentClarificationService.maybeClarify(
                traceId, sessionId, userMessage, route, forceSkill, response);
        if (clarification != null) {
            logResult(sessionId, clarification, "CLARIFICATION", start);
            return clarification;
        }

        rememberSuccessfulAnswer(sessionId, userMessage, response);
        logResult(sessionId, response, response.getError() == null ? "SUCCESS" : "ERROR", start);
        return response;
    }

    private ChatResponse normalizeResponse(ChatResponse response, String traceId, String sessionId) {
        if (response == null) {
            return ChatResponse.friendlyError(traceId, sessionId, "AI 服务暂时不可用，请稍后再试。");
        }
        response.setTraceId(hasText(response.getTraceId()) ? response.getTraceId() : traceId);
        response.setSessionId(hasText(response.getSessionId()) ? response.getSessionId() : sessionId);
        return response;
    }

    private ChatResponse transitionResponse(String traceId, String sessionId, String userMessage,
                                            SkillTransitionResult transition, long start, String status) {
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
        if (transition.isTerminal() && !hasRetainedFlow(transition.getDialogState())) {
            dialogStateService.clearState(sessionId);
        } else {
            dialogStateService.saveState(sessionId, transition.getDialogState());
        }
        rememberSuccessfulAnswer(sessionId, userMessage, response);
        logResult(sessionId, response, status, start);
        return response;
    }

    private void rememberSuccessfulAnswer(String sessionId, String userMessage, ChatResponse response) {
        if (hasText(response.getAnswer()) && response.getError() == null) {
            memoryService.addConversation(sessionId, userMessage, response.getAnswer());
        }
    }

    private void logResult(String sessionId, ChatResponse response, String status, long start) {
        log.info("sessionId={}, intent={}, status={}, durationMs={}", sessionId, response.getIntent(), status,
                System.currentTimeMillis() - start);
    }

    private boolean isHandled(SkillTransitionResult transition) {
        return transition != null && transition.isHandled();
    }

    private boolean hasRetainedFlow(DialogState state) {
        if (state == null || state.getFlowStack() == null) return false;
        for (FlowInstance flow : state.getFlowStack()) {
            if ("SUSPENDED".equals(flow.getStatus()) || "ACTIVE".equals(flow.getStatus())) return true;
        }
        return false;
    }

    private String transitionIntent(SkillTransitionResult transition) {
        if (transition.getDialogState() != null && transition.getDialogState().getIntent() != null
                && hasText(transition.getDialogState().getIntent().getCurrent())) {
            return transition.getDialogState().getIntent().getCurrent();
        }
        return "STATE_MACHINE";
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
