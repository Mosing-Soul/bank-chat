package org.gundy.chat.skill.service;

import org.gundy.chat.skill.model.MockCustomer;
import org.gundy.chat.skill.repository.MockCustomerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SimulatedCoreBankClient implements CoreBankClient {
    private final MockCustomerRepository customerRepository;
    private final long latencyMillis;

    public SimulatedCoreBankClient(MockCustomerRepository customerRepository,
                                   @Value("${bank.skills.customer.core-simulator.latency-ms:30}") long latencyMillis) {
        this.customerRepository = customerRepository;
        this.latencyMillis = latencyMillis;
    }

    @Override
    public List<MockCustomer> searchCustomers(String name) {
        simulateNetworkLatency();
        return customerRepository.searchByName(name);
    }

    @Override
    public MockCustomer getCustomer(String customerId) {
        simulateNetworkLatency();
        return customerRepository.findById(customerId);
    }

    @Override
    public String dataSource() {
        return "CORE_BANK_AUM_SIMULATOR";
    }

    @Override
    public boolean mock() {
        return true;
    }

    private void simulateNetworkLatency() {
        if (latencyMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(latencyMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
