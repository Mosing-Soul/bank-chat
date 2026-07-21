package org.gundy.chat.service;

/** High-precision fallback used only when structured command routing does not handle a turn. */
final class LegacyIntentFallback {
    private LegacyIntentFallback() {}

    static String normalizeSkill(String skillName) {
        if (skillName == null || skillName.trim().length() == 0) return null;
        String value = skillName.trim().toUpperCase();
        if ("MESSAGE".equals(value) || "MESSAGE_SEND".equals(value)) return "MESSAGE_SEND";
        if ("RAG".equals(value) || "RAG_QUERY".equals(value) || "RULE_QUERY".equals(value)) return "RAG_QUERY";
        if ("CUSTOMER_AUM".equals(value) || "AUM".equals(value)) return "CUSTOMER_AUM";
        if ("GOLD".equals(value) || "GOLD_PRICE".equals(value)) return "GOLD_PRICE";
        return value;
    }

    static String detectIntent(String userMessage) {
        String value = userMessage == null ? "" : userMessage.trim();
        if (value.matches(".*(AUM|aum|资产|客户).*(查询|查|多少).*")) return "CUSTOMER_AUM";
        if (value.matches(".*(黄金|金价).*(价格|多少|查询|现在).*")) return "GOLD_PRICE";
        if (value.matches(".*(规则|制度|文档|赎回|提前赎回|怎么办).*")) return "RAG_QUERY";
        if (value.matches(".*(发消息|发送消息|生成客户消息|到期提醒|资产配置提醒|给.*提醒|给.*通知).*")) return "MESSAGE_SEND";
        return null;
    }

    static boolean isExplicitSwitch(String userMessage) {
        String value = userMessage == null ? "" : userMessage.trim();
        return value.matches(".*(先别|先不|放一放|换个问题|不是|我不是要|取消刚才|重新问|另一个问题|先查|帮我查|我想问).*");
    }
}
