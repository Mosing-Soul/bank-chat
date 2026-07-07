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
            return result;
        }

        String text = safe(userMessage);
        if (isInstitutionKnowledgeQuery(text, entities)) {
            return routeTo(result, "RAG_QUERY", 0.92D, "institution knowledge query");
        }
        if (isGoldPriceQuery(text, entities)) {
            return routeTo(result, "GOLD_PRICE", 0.9D, "market price query");
        }
        if (isCustomerAumQuery(text, entities)) {
            return routeTo(result, "CUSTOMER_AUM", 0.88D, "specific customer data query");
        }
        if (isMessageSendQuery(text, entities)) {
            result.setRequestedSkill("MESSAGE_SEND");
            result.setForceSkill(false);
            result.setClearHistory(false);
            result.setConfidence(0.82D);
            result.setReason("message action query");
            return result;
        }

        result.setConfidence(0.0D);
        result.setReason("no deterministic route");
        return result;
    }

    private IntentRouteResult routeTo(IntentRouteResult result, String skill, double confidence, String reason) {
        result.setRequestedSkill(skill);
        result.setForceSkill(true);
        result.setClearHistory(true);
        result.setConfidence(confidence);
        result.setReason(reason);
        return result;
    }

    private boolean isInstitutionKnowledgeQuery(String text, ExtractedEntities entities) {
        if (entities.hasBankName() && entities.hasBusinessTerm()) {
            return true;
        }
        if (entities.hasBusinessTerm() && text.matches(".*(规则|制度|办法|怎么|如何|怎么样|是什么|划分|分类|分层).*")) {
            return true;
        }
        if (text.matches(".*(客户等级|客户分层|客户分类|等级划分).*(规则|制度|怎么|如何|怎么样|是什么|划分).*")) {
            return true;
        }
        return false;
    }

    private boolean isGoldPriceQuery(String text, ExtractedEntities entities) {
        return entities.hasMarketTerm() && text.matches(".*(黄金|金价|Au9999|AU9999).*(价格|多少|查询|现在|行情|走势).*");
    }

    private boolean isCustomerAumQuery(String text, ExtractedEntities entities) {
        if (!entities.hasCustomerName()) {
            return false;
        }
        return text.matches(".*(AUM|aum|资产|持仓|余额|客户等级).*(查询|查|多少|是多少).*")
                || text.matches(".*(查询|查).*(AUM|aum|资产|持仓|余额|客户等级).*");
    }

    private boolean isMessageSendQuery(String text, ExtractedEntities entities) {
        return entities.hasMessageAction()
                && text.matches(".*(发消息|发送消息|发送|通知|提醒|触达|到期提醒|资产配置提醒|给.*提醒|给.*通知).*");
    }

    private String normalizeSkill(String skillName) {
        if (skillName == null || skillName.trim().length() == 0) {
            return null;
        }
        String value = skillName.trim().toUpperCase();
        if ("MESSAGE".equals(value) || "MESSAGE_SEND".equals(value)) {
            return "MESSAGE_SEND";
        }
        if ("RAG".equals(value) || "RAG_QUERY".equals(value) || "RULE_QUERY".equals(value)) {
            return "RAG_QUERY";
        }
        if ("CUSTOMER_AUM".equals(value) || "AUM".equals(value)) {
            return "CUSTOMER_AUM";
        }
        if ("GOLD".equals(value) || "GOLD_PRICE".equals(value)) {
            return "GOLD_PRICE";
        }
        return value;
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
