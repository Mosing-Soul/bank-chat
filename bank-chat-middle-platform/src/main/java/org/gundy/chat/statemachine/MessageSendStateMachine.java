package org.gundy.chat.statemachine;

import org.gundy.chat.entity.dialog.DialogIntent;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.dialog.DialogUiAction;
import org.gundy.chat.entity.dialog.DialogUiCard;
import org.gundy.chat.entity.dialog.DialogUiField;
import org.gundy.chat.entity.dialog.DialogUiHints;
import org.gundy.chat.entity.dialog.SkillDialogState;
import org.gundy.chat.skill.dto.CustomerSummaryResponse;
import org.gundy.chat.skill.dto.MessagePreviewRequest;
import org.gundy.chat.skill.dto.MessagePreviewResponse;
import org.gundy.chat.skill.dto.MessageSendRequest;
import org.gundy.chat.skill.dto.MessageSendResponse;
import org.gundy.chat.skill.service.CustomerSkillService;
import org.gundy.chat.skill.service.MessageSkillService;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MessageSendStateMachine implements SkillStateMachine {
    public static final String SKILL = "MESSAGE_SEND";

    private final CustomerSkillService customerSkillService;
    private final MessageSkillService messageSkillService;

    public MessageSendStateMachine(CustomerSkillService customerSkillService, MessageSkillService messageSkillService) {
        this.customerSkillService = customerSkillService;
        this.messageSkillService = messageSkillService;
    }

    @Override
    public String skillName() {
        return SKILL;
    }

    @Override
    public boolean supports(DialogState state, String userMessage) {
        if (state != null && SKILL.equals(state.getActiveSkill())) {
            return true;
        }
        return looksLikeMessageSend(userMessage);
    }

    @Override
    public SkillTransitionResult handle(String traceId, String sessionId, DialogState state, String userMessage) {
        DialogState nextState = state == null ? newState(sessionId) : state;
        SkillDialogState skillState = skillState(nextState);
        fillSlots(skillState, userMessage);

        String text = safe(userMessage);
        String stage = skillState.getStage();
        if ("PREVIEW_PENDING".equals(stage)) {
            if (isCancel(text)) {
                return cancel(nextState, skillState);
            }
            if (isConfirm(text)) {
                return send(traceId, nextState, skillState);
            }
            if (isRevision(text)) {
                applyRevision(skillState, text);
            } else {
                return askConfirmation(nextState, skillState);
            }
        }

        if (missing(skillState, "messagePurpose")) {
            return askPurpose(nextState, skillState);
        }
        if (missing(skillState, "customerName")) {
            return askCustomer(nextState, skillState);
        }
        return preview(traceId, nextState, skillState);
    }

    private DialogState newState(String sessionId) {
        DialogState state = new DialogState();
        state.setSessionId(sessionId);
        state.setActiveSkill(SKILL);
        state.setActiveFlowId(UUID.randomUUID().toString());
        state.setMode("SINGLE_SKILL");
        DialogIntent intent = new DialogIntent();
        intent.setCurrent(SKILL);
        intent.setConfidence(0.95D);
        intent.setSource("JAVA_STATE_MACHINE");
        state.setIntent(intent);
        return state;
    }

    private SkillDialogState skillState(DialogState state) {
        SkillDialogState skillState = state.getSkills().get(SKILL);
        if (skillState == null) {
            skillState = new SkillDialogState();
            skillState.setSkill(SKILL);
            skillState.setStage("COLLECTING");
            skillState.setStatus("COLLECTING");
            state.getSkills().put(SKILL, skillState);
        }
        return skillState;
    }

    private void fillSlots(SkillDialogState state, String userMessage) {
        Map<String, Object> slots = state.getSlots();
        String purpose = inferPurpose(userMessage);
        if (purpose != null) {
            slots.put("messagePurpose", purpose);
            slots.put("templateCode", templateCodeForPurpose(purpose));
        }
        String customerName = extractCustomerName(userMessage);
        if (customerName != null) {
            slots.put("customerName", customerName);
        }
        if (!slots.containsKey("content")) {
            String customContent = extractCustomContent(userMessage);
            if (customContent != null) {
                slots.put("content", customContent);
                slots.put("messagePurpose", customContent);
                slots.put("templateCode", "CUSTOM_CONTENT");
            }
        }
    }

    private SkillTransitionResult askPurpose(DialogState state, SkillDialogState skillState) {
        skillState.setStage("NEED_PURPOSE");
        skillState.setStatus("COLLECTING");
        skillState.setRequiredSlots(list("messagePurpose"));
        String answer = "请说明消息用途，例如产品到期提醒、资产配置提醒，或直接给出自定义内容。";
        state.setUi(ui("ASK_SLOT", "正在生成客户消息", answer, "例如：产品到期提醒", cancelActions(), null));
        touch(state);
        return result(state, answer, false, false, null, null);
    }

    private SkillTransitionResult askCustomer(DialogState state, SkillDialogState skillState) {
        skillState.setStage("NEED_CUSTOMER");
        skillState.setStatus("COLLECTING");
        skillState.setRequiredSlots(list("customerName"));
        String purpose = str(skillState.getSlots().get("messagePurpose"));
        String answer = purpose == null
                ? "请提供要发送消息的客户姓名。"
                : "已识别消息用途：" + purpose + "。请提供要发送消息的客户姓名。";
        state.setUi(ui("ASK_SLOT", "正在生成客户消息", answer, "例如：张伟", cancelActions(), null));
        touch(state);
        return result(state, answer, false, false, null, null);
    }

    private SkillTransitionResult preview(String traceId, DialogState state, SkillDialogState skillState) {
        Map<String, Object> slots = skillState.getSlots();
        String customerName = str(slots.get("customerName"));
        List<CustomerSummaryResponse> customers = customerSkillService.searchCustomers(customerName);
        if (customers == null || customers.isEmpty()) {
            String answer = "未找到客户" + customerName + "，请确认客户姓名。";
            skillState.setStage("NEED_CUSTOMER");
            skillState.setRequiredSlots(list("customerName"));
            state.setUi(ui("ASK_SLOT", "正在生成客户消息", answer, "例如：张伟", cancelActions(), null));
            touch(state);
            return result(state, answer, false, false, null, null);
        }
        List<CustomerSummaryResponse> exactMatches = new ArrayList<CustomerSummaryResponse>();
        for (CustomerSummaryResponse customer : customers) {
            if (customerName.equals(customer.getCustomerName())) {
                exactMatches.add(customer);
            }
        }
        if (exactMatches.size() == 1) {
            customers = exactMatches;
        }
        if (customers.size() > 1) {
            String answer = "找到多个相近客户，请输入更完整的客户姓名或客户编号。";
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("candidates", customers);
            skillState.setStage("NEED_CUSTOMER");
            state.setUi(ui("ASK_SLOT", "正在生成客户消息", answer, "例如：张伟", cancelActions(), null));
            touch(state);
            return result(state, answer, false, false, data, null);
        }

        CustomerSummaryResponse customer = customers.get(0);
        slots.put("customerId", customer.getCustomerId());
        slots.put("customerName", customer.getCustomerName());

        MessagePreviewRequest request = new MessagePreviewRequest();
        request.setTraceId(traceId);
        request.setCustomerId(customer.getCustomerId());
        request.setTemplateCode(str(slots.get("templateCode")));
        request.setVariables(variablesForTemplate(str(slots.get("templateCode")), str(slots.get("messagePurpose")), str(slots.get("content"))));
        MessagePreviewResponse preview = messageSkillService.preview(request);

        slots.put("operationId", preview.getOperationId());
        slots.put("content", preview.getContent());
        skillState.setStage("PREVIEW_PENDING");
        skillState.setStatus("WAITING_CONFIRMATION");
        skillState.setConfirmation(confirmation(preview));
        skillState.setLastOutput(previewData(preview));
        skillState.setExpiresAt(preview.getExpiresAt() == null ? null : preview.getExpiresAt().toString());

        String answer = "已生成消息预览，请确认内容后再发送。你也可以选择修改或取消。";
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("messagePreview", previewData(preview));
        data.put("messageFlow", flowData("PENDING_CONFIRMATION", preview.getOperationId()));
        state.setUi(ui("CONFIRMATION", "消息发送确认", "请确认消息内容", null, null, previewCards(preview)));
        touch(state);
        return result(state, answer, false, true, data, confirmation(preview));
    }

    private SkillTransitionResult askConfirmation(DialogState state, SkillDialogState skillState) {
        String answer = "当前已有待确认消息。请回复“确认发送”“取消”，或说明“修改为...”。";
        state.setUi(ui("CONFIRMATION", "消息发送确认", answer, null, confirmActions(), null));
        touch(state);
        return result(state, answer, false, true, null, skillState.getConfirmation());
    }

    private SkillTransitionResult send(String traceId, DialogState state, SkillDialogState skillState) {
        String operationId = str(skillState.getSlots().get("operationId"));
        MessageSendRequest request = new MessageSendRequest();
        request.setTraceId(traceId);
        request.setOperationId(operationId);
        request.setConfirmed(true);
        MessageSendResponse sent = messageSkillService.send(request);
        skillState.setStage("SENT");
        skillState.setStatus("COMPLETED");
        skillState.setLastOutput(sendData(sent));
        state.setStatus("COMPLETED");
        state.setActiveSkill(null);
        state.setUi(ui("RESULT", "消息已发送", "已确认并发送客户消息", null, null, null));
        touch(state);

        String answer = "已确认并发送给客户" + sent.getCustomerName() + "。本环境为模拟发送，不会触达真实客户。";
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("messageSend", sendData(sent));
        data.put("messageFlow", flowData("SENT", sent.getOperationId()));
        return result(state, answer, true, false, data, null);
    }

    private SkillTransitionResult cancel(DialogState state, SkillDialogState skillState) {
        String operationId = str(skillState.getSlots().get("operationId"));
        skillState.setStage("CANCELLED");
        skillState.setStatus("CANCELLED");
        state.setStatus("CANCELLED");
        state.setActiveSkill(null);
        state.setUi(ui("RESULT", "已取消消息发送", "已取消本次消息发送流程", null, null, null));
        touch(state);
        String answer = "已取消本次消息发送流程。";
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("messageFlow", flowData("CANCELLED", operationId));
        return result(state, answer, true, false, data, null);
    }

    private void applyRevision(SkillDialogState state, String text) {
        String revised = text.replaceFirst("^(修改为|改成|调整为|重写为|重新生成)", "").trim();
        if (revised.length() > 0) {
            state.getSlots().put("content", revised);
            state.getSlots().put("messagePurpose", revised);
            state.getSlots().put("templateCode", "CUSTOM_CONTENT");
        }
        state.getSlots().remove("operationId");
        state.setStage("COLLECTING");
        state.setStatus("COLLECTING");
    }

    private boolean looksLikeMessageSend(String text) {
        String value = safe(text);
        return value.matches(".*(发消息|发送消息|生成客户消息|生成消息|消息预览|到期提醒|资产配置提醒|给.*提醒|给.*通知).*");
    }

    private String inferPurpose(String text) {
        String value = safe(text);
        if (value.contains("到期")) {
            return "产品到期提醒";
        }
        if (value.contains("资产配置") || value.contains("再平衡") || value.contains("调仓")) {
            return "资产配置提醒";
        }
        if (value.contains("提醒") || value.contains("通知")) {
            return "客户提醒";
        }
        return null;
    }

    private String extractCustomerName(String text) {
        String value = safe(text);
        Matcher matcher = Pattern.compile("(?:客户|给)([\\u4e00-\\u9fa5]{2,4}?)(?=发送|发|通知|提醒|消息|的|$|[，,。\\s])").matcher(value);
        if (matcher.find()) {
            String name = cleanCustomerName(matcher.group(1));
            return name.length() == 0 ? null : name;
        }
        if (value.matches("[\\u4e00-\\u9fa5]{2,4}") && inferPurpose(value) == null && !isConfirm(value) && !isCancel(value)) {
            return value;
        }
        return null;
    }

    private String cleanCustomerName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("(发送|发|生成|消息|提醒|通知)$", "").trim();
    }

    private String extractCustomContent(String text) {
        String value = safe(text);
        Matcher matcher = Pattern.compile("(?:内容是|发送内容是|消息内容是|自定义内容是)(.+)").matcher(value);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String templateCodeForPurpose(String purpose) {
        if ("产品到期提醒".equals(purpose)) {
            return "PRODUCT_MATURITY_REMINDER";
        }
        if ("资产配置提醒".equals(purpose)) {
            return "ASSET_REBALANCE_NOTICE";
        }
        if (purpose != null) {
            return "CUSTOM_CONTENT";
        }
        return null;
    }

    private Map<String, String> variablesForTemplate(String templateCode, String purpose, String content) {
        Map<String, String> variables = new LinkedHashMap<String, String>();
        if ("PRODUCT_MATURITY_REMINDER".equals(templateCode)) {
            variables.put("productName", "稳健增利理财产品");
            variables.put("maturityDate", "近期");
        } else if ("ASSET_REBALANCE_NOTICE".equals(templateCode)) {
            variables.put("portfolioName", "当前投资组合");
        } else {
            variables.put("content", content != null ? content : purpose);
        }
        return variables;
    }

    private boolean isConfirm(String text) {
        return safe(text).matches(".*(确认发送|确认并发送|可以发送|发出去|发送吧|同意发送|确认).*");
    }

    private boolean isCancel(String text) {
        return safe(text).matches(".*(取消|不发|别发|放弃|撤销).*");
    }

    private boolean isRevision(String text) {
        return safe(text).matches(".*(修改|改成|调整|重写|重新生成|换成).*");
    }

    private boolean missing(SkillDialogState state, String slot) {
        Object value = state.getSlots().get(slot);
        return value == null || String.valueOf(value).trim().length() == 0;
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void touch(DialogState state) {
        state.setUpdatedAt(OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
    }

    private List<String> list(String value) {
        List<String> values = new ArrayList<String>();
        values.add(value);
        return values;
    }

    private List<DialogUiAction> cancelActions() {
        List<DialogUiAction> actions = new ArrayList<DialogUiAction>();
        actions.add(new DialogUiAction("取消", "CANCEL_FLOW", "secondary"));
        return actions;
    }

    private List<DialogUiAction> confirmActions() {
        List<DialogUiAction> actions = new ArrayList<DialogUiAction>();
        actions.add(new DialogUiAction("确认发送", "CONFIRM", "primary"));
        actions.add(new DialogUiAction("修改", "REVISE", "secondary"));
        actions.add(new DialogUiAction("取消", "CANCEL", "secondary"));
        return actions;
    }

    private List<DialogUiCard> previewCards(MessagePreviewResponse preview) {
        DialogUiCard card = new DialogUiCard();
        card.setType("MESSAGE_PREVIEW");
        card.setTitle("消息发送确认");
        card.getFields().add(new DialogUiField("客户", preview.getCustomerName()));
        card.getFields().add(new DialogUiField("内容", preview.getContent()));
        card.setActions(confirmActions());
        List<DialogUiCard> cards = new ArrayList<DialogUiCard>();
        cards.add(card);
        return cards;
    }

    private DialogUiHints ui(String mode, String summary, String prompt, String inputHint,
                             List<DialogUiAction> actions, List<DialogUiCard> cards) {
        DialogUiHints ui = new DialogUiHints();
        ui.setReplyMode(mode);
        ui.setSummary(summary);
        ui.setPrompt(prompt);
        ui.setInputHint(inputHint);
        if (actions != null) {
            ui.setQuickActions(actions);
        }
        if (cards != null) {
            ui.setCards(cards);
        }
        return ui;
    }

    private Map<String, Object> confirmation(MessagePreviewResponse preview) {
        Map<String, Object> confirmation = new LinkedHashMap<String, Object>();
        confirmation.put("operationId", preview.getOperationId());
        confirmation.put("customerName", preview.getCustomerName());
        confirmation.put("content", preview.getContent());
        confirmation.put("status", preview.getStatus() == null ? null : preview.getStatus().name());
        confirmation.put("mock", preview.isMock());
        return confirmation;
    }

    private Map<String, Object> previewData(MessagePreviewResponse preview) {
        Map<String, Object> data = confirmation(preview);
        data.put("customerId", preview.getCustomerId());
        data.put("operationType", preview.getOperationType());
        data.put("sensitiveWords", preview.getSensitiveWords());
        data.put("expiresAt", preview.getExpiresAt());
        return data;
    }

    private Map<String, Object> sendData(MessageSendResponse sent) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("operationId", sent.getOperationId());
        data.put("customerId", sent.getCustomerId());
        data.put("customerName", sent.getCustomerName());
        data.put("status", sent.getStatus() == null ? null : sent.getStatus().name());
        data.put("sentAt", sent.getSentAt());
        data.put("channel", sent.getChannel());
        data.put("dataSource", sent.getDataSource());
        data.put("mock", sent.isMock());
        return data;
    }

    private Map<String, Object> flowData(String status, String operationId) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("status", status);
        data.put("operationId", operationId);
        return data;
    }

    private SkillTransitionResult result(DialogState state, String answer, boolean terminal,
                                         boolean requiresConfirmation, Map<String, Object> data,
                                         Map<String, Object> confirmation) {
        SkillTransitionResult result = new SkillTransitionResult();
        result.setHandled(true);
        result.setTerminal(terminal);
        result.setRequiresConfirmation(requiresConfirmation);
        result.setAnswer(answer);
        result.setDialogState(state);
        result.setData(data);
        result.setConfirmation(confirmation);
        return result;
    }
}
