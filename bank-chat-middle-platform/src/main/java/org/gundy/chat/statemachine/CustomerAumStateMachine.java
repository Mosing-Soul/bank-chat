package org.gundy.chat.statemachine;

import org.gundy.chat.entity.dialog.DialogIntent;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.dialog.DialogUiAction;
import org.gundy.chat.entity.dialog.DialogUiHints;
import org.gundy.chat.entity.dialog.SkillDialogState;
import org.gundy.chat.skill.dto.CustomerAumResponse;
import org.gundy.chat.skill.dto.CustomerSummaryResponse;
import org.gundy.chat.skill.service.CustomerSkillService;
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
public class CustomerAumStateMachine implements SkillStateMachine {
    public static final String SKILL = "CUSTOMER_AUM";
    private static final Pattern CUSTOMER_ID = Pattern.compile("(?i)\\b(?:CUST|C)[-_]?\\d{3,}\\b");
    private static final Pattern EXPLICIT_NAME = Pattern.compile("(?:客户(?:姓名)?(?:是|为|叫)|我叫|姓名(?:是|为))[：: ]*([\\u4e00-\\u9fa5]{2,4})");
    private static final Pattern NAME_IN_QUERY = Pattern.compile("客户([\\u4e00-\\u9fa5]{2,4}?)(?=的|当前|AUM|aum|资产|持仓|等级)");

    private final CustomerSkillService customerSkillService;

    public CustomerAumStateMachine(CustomerSkillService customerSkillService) {
        this.customerSkillService = customerSkillService;
    }

    public String skillName() { return SKILL; }

    public boolean supports(DialogState state, String userMessage) {
        return state != null && SKILL.equals(state.getActiveSkill());
    }

    public SkillTransitionResult handle(String traceId, String sessionId, DialogState state, String userMessage) {
        DialogState nextState = state == null ? newState(sessionId) : state;
        SkillDialogState skillState = skillState(nextState);
        String text = safe(userMessage);
        if (isCancel(text)) return cancel(nextState, skillState);

        String customerId = extractCustomerId(text);
        String customerName = extractCustomerName(text, "NEED_CUSTOMER".equals(skillState.getStage()));
        if (customerId != null) skillState.getSlots().put("customerId", customerId);
        else if (customerName != null) skillState.getSlots().put("customerName", customerName);

        if (hasText(value(skillState.getSlots().get("customerId"))))
            return query(nextState, skillState, value(skillState.getSlots().get("customerId")));
        if (hasText(value(skillState.getSlots().get("customerName"))))
            return resolveCustomer(nextState, skillState, value(skillState.getSlots().get("customerName")));
        return askCustomer(nextState, skillState, text.length() > 0);
    }

    private SkillTransitionResult resolveCustomer(DialogState state, SkillDialogState skillState, String customerName) {
        List<CustomerSummaryResponse> customers = customerSkillService.searchCustomers(customerName);
        if (customers == null || customers.isEmpty()) {
            skillState.getSlots().remove("customerName");
            return askCustomer(state, skillState, true);
        }
        List<CustomerSummaryResponse> exact = new ArrayList<CustomerSummaryResponse>();
        for (CustomerSummaryResponse customer : customers)
            if (customerName.equals(customer.getCustomerName())) exact.add(customer);
        if (exact.size() == 1) customers = exact;
        if (customers.size() > 1) {
            skillState.getSlots().remove("customerName");
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("candidates", customers);
            return collectingResult(state, skillState, "找到多位姓名相近的客户，请选择客户或输入客户编号。", data);
        }
        CustomerSummaryResponse customer = customers.get(0);
        skillState.getSlots().put("customerId", customer.getCustomerId());
        skillState.getSlots().put("customerName", customer.getCustomerName());
        return query(state, skillState, customer.getCustomerId());
    }

    private SkillTransitionResult query(DialogState state, SkillDialogState skillState, String customerId) {
        CustomerAumResponse aum = customerSkillService.getAum(customerId);
        skillState.setStage("COMPLETED");
        skillState.setStatus("COMPLETED");
        skillState.setRequiredSlots(new ArrayList<String>());
        state.setStatus("COMPLETED");
        state.setActiveSkill(null);
        touch(state);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("customerAum", aum);
        String answer = "已查询到客户" + aum.getCustomerName() + "的资产信息，当前AUM为"
                + aum.getTotalAum() + " " + aum.getCurrency() + "。";
        state.setUi(ui("RESULT", "客户资产查询完成", answer));
        return result(state, answer, true, data);
    }

    private SkillTransitionResult askCustomer(DialogState state, SkillDialogState skillState, boolean invalidInput) {
        int retryCount = intValue(skillState.getSlots().get("retryCount"));
        if (invalidInput && "NEED_CUSTOMER".equals(skillState.getStage())) retryCount++;
        skillState.getSlots().put("retryCount", retryCount);
        String answer;
        if (retryCount >= 3)
            answer = "暂时没有识别到客户信息。你可以输入客户姓名或编号继续，也可以回复“取消”结束本次查询。";
        else if (invalidInput && "NEED_CUSTOMER".equals(skillState.getStage()))
            answer = "办理客户资产查询还需要客户姓名或编号，例如“张伟”或“CUST001”。如需办理其他事项，可以先回复“取消”。";
        else answer = "请提供客户姓名或客户编号，以便查询资产信息。";
        return collectingResult(state, skillState, answer, null);
    }

    private SkillTransitionResult collectingResult(DialogState state, SkillDialogState skillState, String answer, Map<String, Object> data) {
        skillState.setStage("NEED_CUSTOMER");
        skillState.setStatus("COLLECTING");
        List<String> required = new ArrayList<String>();
        required.add("customerNameOrId");
        skillState.setRequiredSlots(required);
        state.setUi(ui("ASK_SLOT", "正在查询客户资产", answer));
        touch(state);
        return result(state, answer, false, data);
    }

    private SkillTransitionResult cancel(DialogState state, SkillDialogState skillState) {
        skillState.setStage("CANCELLED");
        skillState.setStatus("CANCELLED");
        state.setStatus("CANCELLED");
        state.setActiveSkill(null);
        String answer = "已结束本次客户资产查询。";
        state.setUi(ui("RESULT", "已取消客户资产查询", answer));
        touch(state);
        return result(state, answer, true, null);
    }

    private DialogState newState(String sessionId) {
        DialogState state = new DialogState();
        state.setSessionId(sessionId);
        state.setActiveSkill(SKILL);
        state.setActiveFlowId(UUID.randomUUID().toString());
        DialogIntent intent = new DialogIntent();
        intent.setCurrent(SKILL);
        intent.setConfidence(0.99D);
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

    private String extractCustomerId(String text) {
        Matcher matcher = CUSTOMER_ID.matcher(text);
        return matcher.find() ? matcher.group().toUpperCase().replace("-", "").replace("_", "") : null;
    }

    private String extractCustomerName(String text, boolean awaitingCustomer) {
        Matcher matcher = EXPLICIT_NAME.matcher(text);
        if (matcher.find()) return matcher.group(1);
        matcher = NAME_IN_QUERY.matcher(text);
        if (matcher.find()) return matcher.group(1);
        return awaitingCustomer && text.matches("[\\u4e00-\\u9fa5]{2,4}") ? text : null;
    }

    private DialogUiHints ui(String mode, String summary, String prompt) {
        DialogUiHints ui = new DialogUiHints();
        ui.setReplyMode(mode);
        ui.setSummary(summary);
        ui.setPrompt(prompt);
        if ("ASK_SLOT".equals(mode)) {
            ui.setInputHint("例如：张伟或CUST001");
            List<DialogUiAction> actions = new ArrayList<DialogUiAction>();
            actions.add(new DialogUiAction("取消查询", "CANCEL_FLOW", "secondary"));
            ui.setQuickActions(actions);
        }
        return ui;
    }

    private SkillTransitionResult result(DialogState state, String answer, boolean terminal, Map<String, Object> data) {
        SkillTransitionResult result = new SkillTransitionResult();
        result.setHandled(true);
        result.setTerminal(terminal);
        result.setAnswer(answer);
        result.setDialogState(state);
        result.setData(data);
        return result;
    }

    private boolean isCancel(String text) { return text.matches(".*(取消|退出|结束|不查了|换个业务).*"); }
    private int intValue(Object value) { return value instanceof Number ? ((Number) value).intValue() : 0; }
    private String value(Object value) { return value == null ? null : String.valueOf(value); }
    private boolean hasText(String value) { return value != null && value.trim().length() > 0; }
    private String safe(String value) { return value == null ? "" : value.trim(); }
    private void touch(DialogState state) { state.setUpdatedAt(OffsetDateTime.now(ZoneOffset.ofHours(8)).toString()); }
}
