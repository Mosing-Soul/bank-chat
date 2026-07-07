package org.gundy.chat.skill.service;

import org.gundy.chat.skill.dto.CustomerAumResponse;
import org.gundy.chat.skill.dto.CustomerSummaryResponse;
import org.gundy.chat.skill.exception.SkillErrors;
import org.gundy.chat.skill.model.MockCustomer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CustomerSkillService {
    private final CoreBankClient coreBankClient;

    public CustomerSkillService(CoreBankClient coreBankClient) {
        this.coreBankClient = coreBankClient;
    }

    public List<CustomerSummaryResponse> searchCustomers(String name) {
        List<CustomerSummaryResponse> responses = new ArrayList<CustomerSummaryResponse>();
        for (MockCustomer customer : coreBankClient.searchCustomers(name)) {
            responses.add(new CustomerSummaryResponse(customer.getCustomerId(), customer.getCustomerName(),
                    customer.getCustomerLevel(), customer.getRiskLevel(), coreBankClient.mock()));
        }
        return responses;
    }

    public CustomerAumResponse getAum(String customerId) {
        MockCustomer customer = coreBankClient.getCustomer(customerId);
        if (customer == null) {
            throw SkillErrors.customerNotFound(customerId);
        }
        CustomerAumResponse response = new CustomerAumResponse();
        response.setCustomerId(customer.getCustomerId());
        response.setCustomerName(customer.getCustomerName());
        response.setTotalAum(customer.getTotalAum());
        response.setCurrency(customer.getCurrency());
        response.setStatisticsDate(customer.getStatisticsDate());
        response.setHoldingsSummary(customer.getHoldingsSummary());
        response.setDataSource(coreBankClient.dataSource());
        response.setMock(coreBankClient.mock());
        return response;
    }

    public MockCustomer requireCustomer(String customerId) {
        MockCustomer customer = coreBankClient.getCustomer(customerId);
        if (customer == null) {
            throw SkillErrors.customerNotFound(customerId);
        }
        return customer;
    }
}
