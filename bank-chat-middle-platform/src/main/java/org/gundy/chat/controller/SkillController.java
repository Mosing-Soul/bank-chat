package org.gundy.chat.controller;

import lombok.extern.slf4j.Slf4j;
import org.gundy.chat.skill.config.InternalSkillInterceptor;
import org.gundy.chat.skill.dto.CustomerAumResponse;
import org.gundy.chat.skill.dto.CustomerSummaryResponse;
import org.gundy.chat.skill.dto.MessagePreviewRequest;
import org.gundy.chat.skill.dto.MessagePreviewResponse;
import org.gundy.chat.skill.dto.MessageSendRequest;
import org.gundy.chat.skill.dto.MessageSendResponse;
import org.gundy.chat.skill.dto.SkillApiResponse;
import org.gundy.chat.skill.service.CustomerSkillService;
import org.gundy.chat.skill.service.MessageSkillService;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.List;

@Validated
@Slf4j
@RestController
@RequestMapping("/internal/skills")
public class SkillController {
    private final CustomerSkillService customerSkillService;
    private final MessageSkillService messageSkillService;

    public SkillController(CustomerSkillService customerSkillService,
                           MessageSkillService messageSkillService) {
        this.customerSkillService = customerSkillService;
        this.messageSkillService = messageSkillService;
    }

    @GetMapping("/customers/search")
    public ResponseEntity<SkillApiResponse<List<CustomerSummaryResponse>>> searchCustomers(
            @RequestParam("name") @NotBlank(message = "name must not be blank") String name,
            HttpServletRequest servletRequest) {
        long start = System.currentTimeMillis();
        List<CustomerSummaryResponse> data = customerSkillService.searchCustomers(name);
        logSkill("customer.search", "SUCCESS", start);
        return ResponseEntity.ok(SkillApiResponse.success(traceId(servletRequest), data));
    }

    @GetMapping("/customers/{customerId}/aum")
    public ResponseEntity<SkillApiResponse<CustomerAumResponse>> getAum(
            @PathVariable("customerId") String customerId, HttpServletRequest servletRequest) {
        long start = System.currentTimeMillis();
        CustomerAumResponse data = customerSkillService.getAum(customerId);
        logSkill("customer.aum", "SUCCESS", start);
        return ResponseEntity.ok(SkillApiResponse.success(traceId(servletRequest), data));
    }

    @PostMapping("/messages/preview")
    public ResponseEntity<SkillApiResponse<MessagePreviewResponse>> previewMessage(
            @Valid @RequestBody MessagePreviewRequest request, HttpServletRequest servletRequest) {
        applyBodyTraceId(request.getTraceId(), servletRequest);
        long start = System.currentTimeMillis();
        MessagePreviewResponse data = messageSkillService.preview(request);
        logSkill("message.preview", data.getStatus().name(), start);
        return ResponseEntity.ok(SkillApiResponse.success(traceId(servletRequest), data));
    }

    @PostMapping("/messages/send")
    public ResponseEntity<SkillApiResponse<MessageSendResponse>> sendMessage(
            @Valid @RequestBody MessageSendRequest request, HttpServletRequest servletRequest) {
        applyBodyTraceId(request.getTraceId(), servletRequest);
        long start = System.currentTimeMillis();
        MessageSendResponse data = messageSkillService.send(request);
        logSkill("message.send", data.getStatus().name(), start);
        return ResponseEntity.ok(SkillApiResponse.success(traceId(servletRequest), data));
    }

    private String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(InternalSkillInterceptor.TRACE_ID_ATTRIBUTE);
        return traceId == null ? "" : String.valueOf(traceId);
    }

    private void applyBodyTraceId(String bodyTraceId, HttpServletRequest request) {
        if (bodyTraceId != null && bodyTraceId.trim().length() > 0) {
            request.setAttribute(InternalSkillInterceptor.TRACE_ID_ATTRIBUTE, bodyTraceId);
            MDC.put("traceId", bodyTraceId);
        }
    }

    private void logSkill(String skillName, String status, long startMillis) {
        log.info("skillName={}, status={}, costMs={}", skillName, status, System.currentTimeMillis() - startMillis);
    }
}
