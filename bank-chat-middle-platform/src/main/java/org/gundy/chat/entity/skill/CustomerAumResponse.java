package org.gundy.chat.skill.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class CustomerAumResponse {
    private String customerId;
    private String customerName;
    private BigDecimal totalAum;
    private String currency;
    private LocalDate statisticsDate;
    private List<String> holdingsSummary;
    private String dataSource;
    private boolean mock;

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public BigDecimal getTotalAum() { return totalAum; }
    public void setTotalAum(BigDecimal totalAum) { this.totalAum = totalAum; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocalDate getStatisticsDate() { return statisticsDate; }
    public void setStatisticsDate(LocalDate statisticsDate) { this.statisticsDate = statisticsDate; }
    public List<String> getHoldingsSummary() { return holdingsSummary; }
    public void setHoldingsSummary(List<String> holdingsSummary) { this.holdingsSummary = holdingsSummary; }
    public String getDataSource() { return dataSource; }
    public void setDataSource(String dataSource) { this.dataSource = dataSource; }
    public boolean isMock() { return mock; }
    public void setMock(boolean mock) { this.mock = mock; }
}
