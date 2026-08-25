package org.gundy.chat.service;

import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.intent.IntentRouteResult;
import org.springframework.stereotype.Service;

@Service
public class IntentRouterService {
    public IntentRouteResult route(DialogState state, String userMessage, String requestedSkill, boolean forceSkill) {
        IntentRouteResult result = new IntentRouteResult();
        String skill = forceSkill ? normalizeSkill(requestedSkill) : null;
        if (skill != null) {
            result.setRequestedSkill(skill);
            result.setForceSkill(true);
            result.setClearHistory(true);
            result.setConfidence(0.99D);
            result.setReason("explicit frontend action");
            result.setDialogAct("FRONTEND_REQUESTED_SKILL");
            return result;
        }
        result.setConfidence(0.0D);
        result.setReason("delegated to llm intent classifier");
        result.setDialogAct("LLM_INTENT_CLASSIFICATION");
        return result;
    }

    private String normalizeSkill(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase();
        if ("RAG_QUERY".equals(normalized) || "RULE_QUERY".equals(normalized)) return "RAG_QUERY";
        if ("GOLD_PRICE".equals(normalized) || "EXTERNAL_SEARCH".equals(normalized)) return "GOLD_PRICE";
        return null;
    }
}
