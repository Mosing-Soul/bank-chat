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
        extractCustomerNames(value, entities);
        return entities;
    }

    private void addContained(String text, String[] candidates, java.util.List<String> values) {
        for (String candidate : candidates) {
            if (text.contains(candidate) && !values.contains(candidate)) {
                values.add(candidate);
            }
        }
    }

    private void extractCustomerNames(String text, ExtractedEntities entities) {
        Matcher customerMatcher = Pattern.compile("客户([\\u4e00-\\u9fa5]{2,4})").matcher(text);
        while (customerMatcher.find()) {
            addCustomerName(customerMatcher.group(1), entities);
        }
        Matcher sendMatcher = Pattern.compile("给([\\u4e00-\\u9fa5]{2,4})(?:发|发送|通知|提醒)").matcher(text);
        while (sendMatcher.find()) {
            addCustomerName(sendMatcher.group(1), entities);
        }
    }

    private void addCustomerName(String name, ExtractedEntities entities) {
        if (name == null) {
            return;
        }
        String value = name.replaceAll("(发送|通知|提醒|客户|消息)$", "");
        if (value.length() >= 2 && !entities.getCustomerNames().contains(value)) {
            entities.getCustomerNames().add(value);
        }
    }
}
