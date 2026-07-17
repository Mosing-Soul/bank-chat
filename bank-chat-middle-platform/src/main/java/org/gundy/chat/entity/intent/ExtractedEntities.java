package org.gundy.chat.entity.intent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExtractedEntities {
    private List<String> bankNames = new ArrayList<String>();
    private List<String> customerNames = new ArrayList<String>();
    private List<String> customerIds = new ArrayList<String>();
    private List<String> productNames = new ArrayList<String>();
    private List<String> businessTerms = new ArrayList<String>();
    private List<String> marketTerms = new ArrayList<String>();
    private List<String> messageActions = new ArrayList<String>();

    public List<String> getBankNames() { return bankNames; }
    public void setBankNames(List<String> bankNames) { this.bankNames = bankNames; }
    public List<String> getCustomerNames() { return customerNames; }
    public void setCustomerNames(List<String> customerNames) { this.customerNames = customerNames; }
    public List<String> getCustomerIds() { return customerIds; }
    public void setCustomerIds(List<String> customerIds) { this.customerIds = customerIds; }
    public List<String> getProductNames() { return productNames; }
    public void setProductNames(List<String> productNames) { this.productNames = productNames; }
    public List<String> getBusinessTerms() { return businessTerms; }
    public void setBusinessTerms(List<String> businessTerms) { this.businessTerms = businessTerms; }
    public List<String> getMarketTerms() { return marketTerms; }
    public void setMarketTerms(List<String> marketTerms) { this.marketTerms = marketTerms; }
    public List<String> getMessageActions() { return messageActions; }
    public void setMessageActions(List<String> messageActions) { this.messageActions = messageActions; }

    public boolean hasBankName() { return !bankNames.isEmpty(); }
    public boolean hasCustomerName() { return !customerNames.isEmpty(); }
    public boolean hasCustomerId() { return !customerIds.isEmpty(); }
    public boolean hasBusinessTerm() { return !businessTerms.isEmpty(); }
    public boolean hasMarketTerm() { return !marketTerms.isEmpty(); }
    public boolean hasMessageAction() { return !messageActions.isEmpty(); }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("bankNames", bankNames);
        values.put("customerNames", customerNames);
        values.put("customerIds", customerIds);
        values.put("productNames", productNames);
        values.put("businessTerms", businessTerms);
        values.put("marketTerms", marketTerms);
        values.put("messageActions", messageActions);
        return values;
    }
}
