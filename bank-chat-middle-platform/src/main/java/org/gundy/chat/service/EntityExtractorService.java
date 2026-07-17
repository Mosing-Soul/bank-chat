package org.gundy.chat.service;

import org.gundy.chat.entity.intent.ExtractedEntities;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EntityExtractorService {
    private static final String[] BANK_NAMES = {
            "招商银行", "招行", "工商银行", "工行", "建设银行", "建行", "农业银行", "农行",
            "中国银行", "中行", "交通银行", "交行", "邮储银行", "邮储", "我行", "本行",
            "总行", "分行", "支行"
    };
    private static final String[] BUSINESS_TERMS = {
            "客户等级", "客户分层", "客户分类", "等级划分", "分层规则", "分类规则",
            "风险等级", "风险测评", "适当性", "提前赎回", "赎回规则", "产品规则",
            "业务规则", "制度", "管理办法", "监管指标", "信息披露"
    };
    private static final String[] MARKET_TERMS = {
            "黄金", "金价", "Au9999", "AU9999", "行情", "价格", "汇率"
    };
    private static final String[] MESSAGE_ACTIONS = {
            "发消息", "发送消息", "发送", "通知", "提醒", "触达", "发给", "给"
    };
    private static final String[] PRODUCT_HINTS = {
            "理财", "产品", "基金", "保险", "存款", "稳健增利", "金葵花"
    };

    public ExtractedEntities extract(String text) {
        ExtractedEntities entities = new ExtractedEntities();
        String value = text == null ? "" : text.trim();
        addContained(value, BANK_NAMES, entities.getBankNames());
        addContained(value, BUSINESS_TERMS, entities.getBusinessTerms());
        addContained(value, MARKET_TERMS, entities.getMarketTerms());
        addContained(value, MESSAGE_ACTIONS, entities.getMessageActions());
        addContained(value, PRODUCT_HINTS, entities.getProductNames());
        extractCustomerIds(value, entities);
        return entities;
    }

    private void addContained(String text, String[] candidates, java.util.List<String> values) {
        for (String candidate : candidates) {
            if (text.contains(candidate) && !values.contains(candidate)) {
                values.add(candidate);
            }
        }
    }

    private void extractCustomerIds(String text, ExtractedEntities entities) {
        Matcher matcher = Pattern.compile("(?i)(?:客户号|客户编号)?[：:\\s]*(C(?:UST)?\\d{3,})").matcher(text);
        while (matcher.find()) {
            String customerId = matcher.group(1).toUpperCase();
            if (!entities.getCustomerIds().contains(customerId)) {
                entities.getCustomerIds().add(customerId);
            }
        }
    }
}
