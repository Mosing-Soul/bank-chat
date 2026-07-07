package org.gundy.chat.skill.repository;

import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class MessageTemplateRepository {
    private final Map<String, String> templates;
    private final Map<String, List<String>> requiredVariables;
    private final List<String> sensitiveWords;

    public MessageTemplateRepository() {
        Map<String, String> templateData = new LinkedHashMap<String, String>();
        templateData.put("PRODUCT_MATURITY_REMINDER",
                "{customerName}先生/女士，您持有的{productName}将于{maturityDate}到期，请关注资金安排。");
        templateData.put("ASSET_REBALANCE_NOTICE",
                "{customerName}先生/女士，结合近期市场变化，建议您关注{portfolioName}的资产配置复核。");
        templateData.put("CUSTOM_CONTENT",
                "{customerName}先生/女士，{content}");
        this.templates = Collections.unmodifiableMap(templateData);

        Map<String, List<String>> variableData = new LinkedHashMap<String, List<String>>();
        variableData.put("PRODUCT_MATURITY_REMINDER", Arrays.asList("productName", "maturityDate"));
        variableData.put("ASSET_REBALANCE_NOTICE", Arrays.asList("portfolioName"));
        variableData.put("CUSTOM_CONTENT", Arrays.asList("content"));
        this.requiredVariables = Collections.unmodifiableMap(variableData);

        this.sensitiveWords = Collections.unmodifiableList(Arrays.asList("保本高收益", "稳赚不赔", "内部消息"));
    }

    public String findTemplate(String templateCode) {
        return templates.get(templateCode);
    }

    public List<String> requiredVariables(String templateCode) {
        List<String> variables = requiredVariables.get(templateCode);
        return variables == null ? Collections.<String>emptyList() : variables;
    }

    public List<String> sensitiveWords() {
        return sensitiveWords;
    }
}
