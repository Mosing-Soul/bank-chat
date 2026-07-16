package org.gundy.chat.service;

import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.entity.intent.IntentRouteResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IntentClarificationService {
    private static final double ROUTER_CONFIDENCE_THRESHOLD = 0.72D;
    private static final double MODEL_CONFIDENCE_THRESHOLD = 0.68D;

    private final SkillConfigService skillConfigService;

    public IntentClarificationService(SkillConfigService skillConfigService) {
        this.skillConfigService = skillConfigService;
    }

    public ChatResponse maybeClarify(String traceId,
                                     String sessionId,
                                     String userMessage,
                                     IntentRouteResult route,
                                     boolean forceSkill,
                                     ChatResponse aiResponse) {
        if (forceSkill || route == null || aiResponse == null || aiResponse.getError() != null) {
            return null;
        }
        if (Boolean.TRUE.equals(aiResponse.getRequiresConfirmation())) {
            return null;
        }
        if ("FRONTEND_REQUESTED_SKILL".equals(route.getDialogAct()) || confidentRouterRoute(route)) {
            return null;
        }

        List<Map<String, Object>> candidates = skillConfigService.clarificationCandidates(userMessage, 3);
        if (candidates.size() < 2) {
            return null;
        }

        boolean shouldClarify = isLowModelConfidence(aiResponse)
                || isUnknownIntent(aiResponse)
                || isBusinessLikeButNoRoute(userMessage, route);
        if (!shouldClarify) {
            return null;
        }

        ChatResponse response = new ChatResponse();
        response.setTraceId(traceId);
        response.setSessionId(sessionId);
        response.setIntent("CLARIFICATION");
        response.setConfidence(aiResponse.getConfidence());
        response.setAnswer("我不太确定您想办理哪类事项，请选择一个方向继续。");
        response.setRequiresConfirmation(true);
        Map<String, Object> confirmation = new LinkedHashMap<String, Object>();
        confirmation.put("type", "INTENT_CLARIFICATION");
        confirmation.put("title", "请选择要办理的事项");
        confirmation.put("originalMessage", userMessage);
        confirmation.put("candidates", candidates);
        confirmation.put("reason", clarifyReason(route, aiResponse));
        response.setConfirmation(confirmation);
        return response;
    }

    private boolean confidentRouterRoute(IntentRouteResult route) {
        return route.getRequestedSkill() != null && route.getConfidence() >= ROUTER_CONFIDENCE_THRESHOLD;
    }

    private boolean isLowModelConfidence(ChatResponse response) {
        Double confidence = response.getConfidence();
        return confidence != null && confidence > 0.0D && confidence < MODEL_CONFIDENCE_THRESHOLD;
    }

    private boolean isUnknownIntent(ChatResponse response) {
        String intent = response.getIntent();
        return "UNKNOWN".equals(intent) || "CLARIFICATION".equals(intent);
    }

    private boolean isBusinessLikeButNoRoute(String userMessage, IntentRouteResult route) {
        return (route.getRequestedSkill() == null || route.getConfidence() < ROUTER_CONFIDENCE_THRESHOLD)
                && looksBusinessLike(userMessage);
    }

    private boolean looksBusinessLike(String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        return text.matches(".*(客户|等级|AUM|资产|持仓|余额|黄金|金价|产品|赎回|规则|制度|提醒|通知|发送|到期|风险|理财|分层|分类).*");
    }

    private String clarifyReason(IntentRouteResult route, ChatResponse response) {
        if (isLowModelConfidence(response)) {
            return "MODEL_LOW_CONFIDENCE";
        }
        if (isUnknownIntent(response)) {
            return "MODEL_UNKNOWN";
        }
        if (route.getRequestedSkill() == null) {
            return "ROUTER_NO_DETERMINISTIC_ROUTE";
        }
        return "ROUTER_LOW_CONFIDENCE";
    }
}
