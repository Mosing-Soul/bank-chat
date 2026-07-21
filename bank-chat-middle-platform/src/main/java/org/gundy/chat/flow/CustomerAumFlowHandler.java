package org.gundy.chat.flow;

import org.gundy.chat.skill.dto.CustomerAumResponse;
import org.gundy.chat.skill.dto.CustomerSummaryResponse;
import org.gundy.chat.skill.service.CustomerSkillService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CustomerAumFlowHandler implements FlowSkillHandler {
    public static final String SKILL = "CUSTOMER_AUM";
    private static final Pattern CUSTOMER_ID = Pattern.compile("(?i)\\b(?:CUST|C)[-_]?\\d{3,}\\b");
    private static final Pattern EXPLICIT_NAME = Pattern.compile("(?:客户(?:姓名)?(?:是|为|叫)|我叫|姓名(?:是|为))[：: ]*([\\u4e00-\\u9fa5]{2,4})");
    private static final Pattern NAME_IN_QUERY = Pattern.compile("客户([\\u4e00-\\u9fa5]{2,4}?)(?=的|当前|AUM|aum|资产|持仓|等级)");
    private static final Pattern NAME_BEFORE_METRIC = Pattern.compile("(?:查询|查一下|查)?([\\u4e00-\\u9fa5]{2,4})的(?:AUM|aum|资产|持仓)");

    private final CustomerSkillService customerSkillService;

    public CustomerAumFlowHandler(CustomerSkillService customerSkillService) {
        this.customerSkillService = customerSkillService;
    }

    public String skillId() { return SKILL; }

    public Map<String, Object> extractSlots(FlowContext context, String userMessage) {
        String text = userMessage == null ? "" : userMessage.trim();
        String reference = extractCustomerId(text);
        if (reference == null) reference = extractCustomerName(text);
        if (reference == null) return new LinkedHashMap<String, Object>();
        Map<String, Object> slots = new LinkedHashMap<String, Object>();
        slots.put("customerReference", reference);
        return slots;
    }

    public FlowValidationResult validate(FlowContext context) {
        String reference = string(context.getFlowInstance().getSlots().get("customerReference"));
        if (reference == null) {
            return FlowValidationResult.invalid("请提供客户姓名或客户编号。", null, "customerReference");
        }
        if (CUSTOMER_ID.matcher(reference).matches()) {
            context.getFlowInstance().getSlots().put("customerId", normalizeCustomerId(reference));
            return FlowValidationResult.valid();
        }

        List<CustomerSummaryResponse> customers = customerSkillService.searchCustomers(reference);
        if (customers == null || customers.isEmpty()) {
            return FlowValidationResult.invalid("未找到客户“" + reference + "”，请确认姓名或输入客户编号。",
                    null, "customerReference");
        }
        List<CustomerSummaryResponse> exact = new ArrayList<CustomerSummaryResponse>();
        for (CustomerSummaryResponse customer : customers) {
            if (reference.equals(customer.getCustomerName())) exact.add(customer);
        }
        if (exact.size() == 1) customers = exact;
        if (customers.size() > 1) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("candidates", customers);
            return FlowValidationResult.invalid("找到多位姓名相近的客户，请选择客户或输入客户编号。",
                    data, "customerReference");
        }
        CustomerSummaryResponse customer = customers.get(0);
        context.getFlowInstance().getSlots().put("customerId", customer.getCustomerId());
        context.getFlowInstance().getSlots().put("customerName", customer.getCustomerName());
        return FlowValidationResult.valid();
    }

    public FlowExecutionResult execute(FlowContext context) {
        String customerId = string(context.getFlowInstance().getSlots().get("customerId"));
        CustomerAumResponse aum = customerSkillService.getAum(customerId);
        context.getFlowInstance().getSlots().put("customerName", aum.getCustomerName());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("customerAum", aum);
        String answer = "已查询到客户" + aum.getCustomerName() + "的资产信息，当前AUM为"
                + aum.getTotalAum() + " " + aum.getCurrency() + "。";
        return new FlowExecutionResult(answer, data);
    }

    private String extractCustomerId(String text) {
        Matcher matcher = CUSTOMER_ID.matcher(text);
        return matcher.find() ? normalizeCustomerId(matcher.group()) : null;
    }

    private String extractCustomerName(String text) {
        Matcher matcher = EXPLICIT_NAME.matcher(text);
        if (matcher.find()) return matcher.group(1);
        matcher = NAME_IN_QUERY.matcher(text);
        if (matcher.find()) return matcher.group(1);
        matcher = NAME_BEFORE_METRIC.matcher(text);
        if (matcher.find()) return matcher.group(1);
        return text.matches("[\\u4e00-\\u9fa5]{2,4}") ? text : null;
    }

    private String normalizeCustomerId(String value) {
        return value.toUpperCase().replace("-", "").replace("_", "");
    }

    private String string(Object value) {
        return value == null || String.valueOf(value).trim().length() == 0 ? null : String.valueOf(value).trim();
    }
}
