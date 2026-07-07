package org.gundy.chat.skill.service;

import org.gundy.chat.skill.model.MockCustomer;

import java.util.List;

public interface CoreBankClient {
    List<MockCustomer> searchCustomers(String name);

    MockCustomer getCustomer(String customerId);

    String dataSource();

    boolean mock();
}
