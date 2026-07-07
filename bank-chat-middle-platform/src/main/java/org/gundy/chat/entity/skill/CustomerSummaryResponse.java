package org.gundy.chat.skill.dto;

import lombok.Data;
import org.gundy.chat.skill.enums.CustomerLevel;
import org.gundy.chat.skill.enums.RiskLevel;

@Data
public class CustomerSummaryResponse {
    private String customerId;
    private String customerName;
    private CustomerLevel customerLevel;
    private RiskLevel riskLevel;
    private boolean mock;

    public CustomerSummaryResponse() {
    }

    public CustomerSummaryResponse(String customerId, String customerName,
                                   CustomerLevel customerLevel, RiskLevel riskLevel,
                                   boolean mock) {
        this.customerId = customerId;
        this.customerName = customerName;
        this.customerLevel = customerLevel;
        this.riskLevel = riskLevel;
        this.mock = mock;
    }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public CustomerLevel getCustomerLevel() { return customerLevel; }
    public void setCustomerLevel(CustomerLevel customerLevel) { this.customerLevel = customerLevel; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public boolean isMock() { return mock; }
    public void setMock(boolean mock) { this.mock = mock; }
}
