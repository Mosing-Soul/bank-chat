package org.gundy.chat.flow;

import org.gundy.chat.skill.dto.CustomerSummaryResponse;
import org.gundy.chat.skill.dto.MessagePreviewRequest;
import org.gundy.chat.skill.dto.MessagePreviewResponse;
import org.gundy.chat.skill.dto.MessageSendRequest;
import org.gundy.chat.skill.dto.MessageSendResponse;
import org.gundy.chat.skill.service.CustomerSkillService;
import org.gundy.chat.skill.service.MessageSkillService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MessageSendFlowHandler implements FlowSkillHandler {
    public static final String SKILL = "MESSAGE_SEND";
    private final CustomerSkillService customerSkillService;
    private final MessageSkillService messageSkillService;

    public MessageSendFlowHandler(CustomerSkillService customerSkillService, MessageSkillService messageSkillService) {
        this.customerSkillService = customerSkillService;
        this.messageSkillService = messageSkillService;
    }

    public String skillId() { return SKILL; }

    public Map<String, Object> extractSlots(FlowContext context, String userMessage) {
        String text = safe(userMessage);
        Map<String, Object> slots = new LinkedHashMap<String, Object>();
        if (isControl(text)) return slots;
        String revision = revisionContent(text);
        if (revision != null) {
            slots.put("messagePurpose", revision);
            slots.put("messageContent", revision);
            context.getFlowInstance().getSlots().remove("operationId");
            context.getFlowInstance().getSlots().remove("confirmationSnapshot");
            return slots;
        }
        String purpose = inferPurpose(text);
        if (purpose != null) slots.put("messagePurpose", purpose);
        String customer = extractCustomer(text);
        if (customer != null) slots.put("customerReference", customer);
        String content = customContent(text);
        if (content != null) {
            slots.put("messagePurpose", content);
            slots.put("messageContent", content);
        }
        return slots;
    }

    public FlowValidationResult validate(FlowContext context) {
        Map<String, Object> slots = context.getFlowInstance().getSlots();
        String reference = str(slots.get("customerReference"));
        List<CustomerSummaryResponse> customers = customerSkillService.searchCustomers(reference);
        if (customers == null || customers.isEmpty()) {
            return FlowValidationResult.invalid("未找到客户“" + reference + "”，请确认客户姓名或编号。", null,
                    "customerReference");
        }
        List<CustomerSummaryResponse> exact = new ArrayList<CustomerSummaryResponse>();
        for (CustomerSummaryResponse customer : customers) {
            if (reference.equals(customer.getCustomerName()) || reference.equalsIgnoreCase(customer.getCustomerId())) exact.add(customer);
        }
        if (exact.size() == 1) customers = exact;
        if (customers.size() > 1) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("candidates", customers);
            return FlowValidationResult.invalid("找到多位相近客户，请选择客户或输入客户编号。", data,
                    "customerReference");
        }
        CustomerSummaryResponse customer = customers.get(0);
        slots.put("customerId", customer.getCustomerId());
        slots.put("customerName", customer.getCustomerName());
        return FlowValidationResult.valid();
    }

    public FlowConfirmationResult prepareConfirmation(FlowContext context) {
        Map<String, Object> slots = context.getFlowInstance().getSlots();
        Map<String, Object> confirmation = snapshot(slots.get("confirmationSnapshot"));
        if (confirmation == null) {
            MessagePreviewResponse preview = preview(context, slots);
            confirmation = previewData(preview);
            slots.put("confirmationSnapshot", new LinkedHashMap<String, Object>(confirmation));
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("messagePreview", confirmation);
        data.put("messageFlow", flowData("PENDING_CONFIRMATION", str(confirmation.get("operationId"))));
        return new FlowConfirmationResult("消息预览已准备好，请核对客户和内容后确认发送。", data, confirmation);
    }

    public boolean isConfirmationRevision(FlowContext context, String userMessage) {
        return revisionContent(safe(userMessage)) != null;
    }

    public FlowExecutionResult execute(FlowContext context) {
        String operationId = str(context.getFlowInstance().getSlots().get("operationId"));
        if (operationId == null) throw new IllegalStateException("消息发送缺少已确认的预览操作");
        MessageSendRequest request = new MessageSendRequest();
        request.setTraceId(context.getTraceId());
        request.setOperationId(operationId);
        request.setConfirmed(true);
        MessageSendResponse sent = messageSkillService.send(request);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("messageSend", sendData(sent));
        data.put("messageFlow", flowData("SENT", sent.getOperationId()));
        return new FlowExecutionResult("已确认并发送给客户" + sent.getCustomerName()
                + "。本环境为模拟发送，不会触达真实客户。", data);
    }

    private MessagePreviewResponse preview(FlowContext context, Map<String, Object> slots) {
        MessagePreviewRequest request = new MessagePreviewRequest();
        request.setTraceId(context.getTraceId());
        request.setCustomerId(str(slots.get("customerId")));
        String purpose = str(slots.get("messagePurpose"));
        String content = str(slots.get("messageContent"));
        String templateCode = templateCode(purpose, content);
        request.setTemplateCode(templateCode);
        request.setVariables(variables(templateCode, purpose, content));
        MessagePreviewResponse preview = messageSkillService.preview(request);
        slots.put("operationId", preview.getOperationId());
        slots.put("messageContent", preview.getContent());
        return preview;
    }

    private String inferPurpose(String text) {
        if (text.contains("到期")) return "产品到期提醒";
        if (text.contains("资产配置") || text.contains("再平衡") || text.contains("调仓")) return "资产配置提醒";
        if (text.contains("提醒") || text.contains("通知")) return "客户提醒";
        return null;
    }

    private String extractCustomer(String text) {
        Matcher matcher = Pattern.compile("(?:客户|给)([\\u4e00-\\u9fa5]{2,4}?)(?=发送|发|通知|提醒|消息|的|$|[，,。\\s])").matcher(text);
        if (matcher.find()) return matcher.group(1).trim();
        return text.matches("[\\u4e00-\\u9fa5]{2,4}") && inferPurpose(text) == null ? text : null;
    }

    private String customContent(String text) {
        Matcher matcher = Pattern.compile("(?:内容是|发送内容是|消息内容是|自定义内容是)(.+)").matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String revisionContent(String text) {
        Matcher matcher = Pattern.compile("^(?:修改为|改成|调整为|重写为|重新生成为|换成)[：: ]*(.+)$").matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String templateCode(String purpose, String content) {
        if (content != null && !content.equals(purpose)) return "CUSTOM_CONTENT";
        if ("产品到期提醒".equals(purpose)) return "PRODUCT_MATURITY_REMINDER";
        if ("资产配置提醒".equals(purpose)) return "ASSET_REBALANCE_NOTICE";
        return "CUSTOM_CONTENT";
    }

    private Map<String, String> variables(String templateCode, String purpose, String content) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if ("PRODUCT_MATURITY_REMINDER".equals(templateCode)) {
            values.put("productName", "持有产品"); values.put("maturityDate", "近期");
        } else if ("ASSET_REBALANCE_NOTICE".equals(templateCode)) {
            values.put("reason", purpose); values.put("action", "联系客户经理了解详情");
        } else {
            values.put("content", content == null ? purpose : content);
        }
        return values;
    }

    private Map<String, Object> previewData(MessagePreviewResponse preview) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("operationId", preview.getOperationId()); data.put("customerId", preview.getCustomerId());
        data.put("customerName", preview.getCustomerName()); data.put("content", preview.getContent());
        data.put("status", preview.getStatus() == null ? null : preview.getStatus().name());
        data.put("sensitiveWords", preview.getSensitiveWords()); data.put("expiresAt", preview.getExpiresAt());
        data.put("mock", preview.isMock());
        return data;
    }

    private Map<String, Object> sendData(MessageSendResponse sent) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("operationId", sent.getOperationId()); data.put("customerId", sent.getCustomerId());
        data.put("customerName", sent.getCustomerName()); data.put("status", sent.getStatus().name());
        data.put("sentAt", sent.getSentAt()); data.put("channel", sent.getChannel()); data.put("mock", sent.isMock());
        return data;
    }

    private Map<String, Object> flowData(String status, String operationId) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("status", status); data.put("operationId", operationId); return data;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshot(Object value) {
        return value instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) value) : null;
    }

    private boolean isControl(String text) { return text.matches(".*(确认发送|确认并发送|可以发送|发送吧|取消|不发|别发)$"); }
    private String safe(String value) { return value == null ? "" : value.trim(); }
    private String str(Object value) { return value == null || String.valueOf(value).trim().length() == 0 ? null : String.valueOf(value).trim(); }
}
