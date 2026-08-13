package org.gundy.chat.service;

import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.intent.ExtractedEntities;
import org.gundy.chat.entity.intent.IntentRouteResult;
import org.springframework.stereotype.Service;

@Service
public class IntentRouterService {
    private final EntityExtractorService entityExtractorService;

    public IntentRouterService(EntityExtractorService entityExtractorService) {
        this.entityExtractorService = entityExtractorService;
    }

    public IntentRouteResult route(DialogState state, String userMessage, String requestedSkill, boolean forceSkill) {
        ExtractedEntities entities = entityExtractorService.extract(userMessage);
        IntentRouteResult result = new IntentRouteResult();
        result.setEntities(entities);

        String normalizedRequestedSkill = normalizeSkill(requestedSkill);
        if (normalizedRequestedSkill != null) {
            result.setRequestedSkill(normalizedRequestedSkill);
            result.setForceSkill(forceSkill);
            result.setClearHistory(forceSkill);
            result.setConfidence(forceSkill ? 0.99D : 0.9D);
            result.setReason("frontend requested skill");
            result.setDialogAct("FRONTEND_REQUESTED_SKILL");
            return result;
        }

        String text = safe(userMessage);
        if (isCompoundIntentQuery(text, entities)) {
            result.setConfidence(0.0D);
            result.setReason("compound request delegated to model top-1 selection");
            result.setDialogAct("MODEL_SELECT_HIGHEST_CONFIDENCE");
            return result;
        }
        if (isExplicitCustomerAumQuery(text)) {
            return routeTo(result, "CUSTOMER_AUM", 0.94D, "explicit customer asset query");
        }
        if (isInstitutionKnowledgeQuery(text, entities)) {
            return routeTo(result, "RAG_QUERY", 0.92D, "institution knowledge query");
        }
        if (isGoldPriceQuery(text, entities)) {
            return routeTo(result, "GOLD_PRICE", 0.9D, "market price query");
        }
        if (isMessageSendQuery(text, entities)) {
            result.setRequestedSkill("MESSAGE_SEND");
            result.setForceSkill(false);
            result.setClearHistory(false);
            result.setConfidence(0.82D);
            result.setReason("message action query");
            result.setDialogAct("ROUTER_CONTINUE_MESSAGE");
            return result;
        }

        result.setConfidence(0.0D);
        result.setReason("no deterministic route");
        result.setDialogAct("NO_DETERMINISTIC_ROUTE");
        return result;
    }

    private boolean isExplicitCustomerAumQuery(String text) {
        if (text.matches(".*(规则|制度|办法|怎么划分|如何划分).*")) {
            return false;
        }
        if (text.matches(".*(AUM|aum).*")) {
            return true;
        }
        return text.matches(".*(查询|查一下|查|多少|当前).*(客户|CUST\\d+).*(资产|持仓).*")
                || text.matches(".*(客户|CUST\\d+).*(资产|持仓).*(查询|查一下|查|多少|当前).*");
    }

    private IntentRouteResult routeTo(IntentRouteResult result, String skill, double confidence, String reason) {
        result.setRequestedSkill(skill);
        result.setForceSkill(true);
        result.setClearHistory(true);
        result.setConfidence(confidence);
        result.setReason(reason);
        result.setDialogAct("ROUTER_SWITCH_INTENT");
        return result;
    }

    private boolean isInstitutionKnowledgeQuery(String text, ExtractedEntities entities) {
        if (entities.hasBankName() && entities.hasBusinessTerm()) {
            return true;
        }
        if (entities.hasBusinessTerm() && text.matches(".*(规则|制度|办法|怎么|如何|怎么样|是什么|划分|分类|分层).*")) {
            return true;
        }
        return text.matches(".*(客户等级|客户分层|客户分类|等级划分).*(规则|制度|怎么|如何|怎么样|是什么|划分).*");
    }

    private boolean isGoldPriceQuery(String text, ExtractedEntities entities) {
        return entities.hasMarketTerm()
                && text.matches(".*(黄金|金价|Au9999|AU9999).*(价格|多少|查询|现在|行情|走势|怎么样).*");
    }

    private boolean isMessageSendQuery(String text, ExtractedEntities entities) {
        return entities.hasMessageAction()
                && text.matches(".*(发消息|发送消息|发送|通知|提醒|触达|到期提醒|资产配置提醒|给.*提醒|给.*通知).*");
    }

    private boolean isCompoundIntentQuery(String text, ExtractedEntities entities) {
        int matches = 0;
        if (isExplicitCustomerAumQuery(text)) matches++;
        if (isInstitutionKnowledgeQuery(text, entities)) matches++;
        if (isGoldPriceQuery(text, entities)) matches++;
        if (isMessageSendQuery(text, entities)) matches++;
        return matches > 1;
    }

    private String normalizeSkill(String skillName) {
        return LegacyIntentFallback.normalizeSkill(skillName);
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
