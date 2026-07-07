package org.gundy.chat.skill.model;

import lombok.Data;
import org.gundy.chat.skill.enums.CustomerLevel;
import org.gundy.chat.skill.enums.RiskLevel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class MockCustomer {
    private final String customerId;
    private final String customerName;
    private final CustomerLevel customerLevel;
    private final RiskLevel riskLevel;
    private final BigDecimal totalAum;
    private final String currency;
    private final LocalDate statisticsDate;
    private final List<String> holdingsSummary;

    public MockCustomer(String customerId, String customerName, CustomerLevel customerLevel,
                        RiskLevel riskLevel, BigDecimal totalAum, String currency,
                        LocalDate statisticsDate, List<String> holdingsSummary) {
        this.customerId = customerId;
        this.customerName = customerName;
        this.customerLevel = customerLevel;
        this.riskLevel = riskLevel;
        this.totalAum = totalAum;
        this.currency = currency;
        this.statisticsDate = statisticsDate;
        this.holdingsSummary = holdingsSummary;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public CustomerLevel getCustomerLevel() {
        return customerLevel;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public BigDecimal getTotalAum() {
        return totalAum;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getStatisticsDate() {
        return statisticsDate;
    }

    public List<String> getHoldingsSummary() {
        return holdingsSummary;
    }
}
