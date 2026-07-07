package org.gundy.chat.skill.repository;

import org.gundy.chat.skill.enums.CustomerLevel;
import org.gundy.chat.skill.enums.RiskLevel;
import org.gundy.chat.skill.model.MockCustomer;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class MockCustomerRepository {
    private final Map<String, MockCustomer> customers;

    public MockCustomerRepository() {
        Map<String, MockCustomer> data = new LinkedHashMap<String, MockCustomer>();
        data.put("CUST001", new MockCustomer("CUST001", "张伟",
                CustomerLevel.PRIVATE_BANKING, RiskLevel.C3_BALANCED,
                new BigDecimal("8260000.00"), "CNY", LocalDate.of(2026, 6, 23),
                Arrays.asList("现金及活期 12%", "固收理财 46%", "基金组合 28%", "贵金属 14%")));
        data.put("CUST002", new MockCustomer("CUST002", "张伟明",
                CustomerLevel.PLATINUM, RiskLevel.C2_PRUDENT,
                new BigDecimal("3180000.00"), "CNY", LocalDate.of(2026, 6, 23),
                Arrays.asList("定期存款 38%", "固收理财 44%", "基金组合 18%")));
        data.put("CUST003", new MockCustomer("CUST003", "李娜",
                CustomerLevel.GOLD, RiskLevel.C4_AGGRESSIVE,
                new BigDecimal("1560000.00"), "CNY", LocalDate.of(2026, 6, 23),
                Arrays.asList("现金及活期 9%", "权益基金 51%", "结构性存款 40%")));
        data.put("CUST004", new MockCustomer("CUST004", "王建国",
                CustomerLevel.MASS, RiskLevel.C1_CONSERVATIVE,
                new BigDecimal("420000.00"), "CNY", LocalDate.of(2026, 6, 23),
                Arrays.asList("定期存款 70%", "现金及活期 30%")));
        data.put("CUST005", new MockCustomer("CUST005", "周敏",
                CustomerLevel.PLATINUM, RiskLevel.C3_BALANCED,
                new BigDecimal("5060000.00"), "CNY", LocalDate.of(2026, 6, 23),
                Arrays.asList("保险 22%", "固收理财 35%", "基金组合 31%", "贵金属 12%")));
        this.customers = Collections.unmodifiableMap(data);
    }

    public List<MockCustomer> searchByName(String name) {
        List<MockCustomer> result = new ArrayList<MockCustomer>();
        if (name == null) {
            return result;
        }
        String keyword = name.trim();
        if (keyword.length() == 0) {
            return result;
        }
        for (MockCustomer customer : customers.values()) {
            if (customer.getCustomerName().contains(keyword)) {
                result.add(customer);
            }
        }
        return result;
    }

    public MockCustomer findById(String customerId) {
        return customers.get(customerId);
    }
}
