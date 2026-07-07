package org.gundy.chat.skill.service;

import org.gundy.chat.skill.dto.MessagePreviewRequest;
import org.gundy.chat.skill.dto.MessagePreviewResponse;
import org.gundy.chat.skill.dto.MessageSendRequest;
import org.gundy.chat.skill.dto.MessageSendResponse;
import org.gundy.chat.skill.enums.OperationStatus;
import org.gundy.chat.skill.exception.SkillErrors;
import org.gundy.chat.skill.model.MockCustomer;
import org.gundy.chat.skill.model.PendingMessageOperation;
import org.gundy.chat.skill.repository.MessageOperationRepository;
import org.gundy.chat.skill.repository.MessageTemplateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MessageSkillService {
    private final CustomerSkillService customerSkillService;
    private final MessageTemplateRepository templateRepository;
    private final MessageOperationRepository operationRepository;
    private final Duration operationTtl;

    public MessageSkillService(CustomerSkillService customerSkillService,
                               MessageTemplateRepository templateRepository,
                               MessageOperationRepository operationRepository,
                               @Value("${bank.skills.internal.message-operation-ttl:PT10M}") Duration operationTtl) {
        this.customerSkillService = customerSkillService;
        this.templateRepository = templateRepository;
        this.operationRepository = operationRepository;
        this.operationTtl = operationTtl;
    }

    public MessagePreviewResponse preview(MessagePreviewRequest request) {
        MockCustomer customer = customerSkillService.requireCustomer(request.getCustomerId());
        String template = templateRepository.findTemplate(request.getTemplateCode());
        if (template == null) {
            throw SkillErrors.templateNotFound(request.getTemplateCode());
        }
        validateVariables(request.getVariables(), request.getTemplateCode());
        String content = render(template, customer.getCustomerName(), request.getVariables());
        List<String> sensitiveWords = scanSensitiveWords(content);
        OperationStatus status = sensitiveWords.isEmpty()
                ? OperationStatus.PENDING_CONFIRMATION : OperationStatus.NEEDS_REVIEW;
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).plus(operationTtl);
        String operationId = UUID.randomUUID().toString();
        PendingMessageOperation operation = new PendingMessageOperation(operationId, customer.getCustomerId(),
                customer.getCustomerName(), content, sensitiveWords, expiresAt, status);
        operationRepository.save(operation);
        return toPreviewResponse(operation);
    }

    public MessageSendResponse send(MessageSendRequest request) {
        if (!Boolean.TRUE.equals(request.getConfirmed())) {
            throw SkillErrors.confirmationRequired();
        }
        PendingMessageOperation operation = operationRepository.findById(request.getOperationId());
        if (operation == null) {
            throw SkillErrors.operationNotFound(request.getOperationId());
        }
        if (!operation.getExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.ofHours(8)))) {
            operation.setStatus(OperationStatus.EXPIRED);
            throw SkillErrors.operationExpired(request.getOperationId());
        }
        if (!operation.getSensitiveWords().isEmpty()) {
            operation.setStatus(OperationStatus.NEEDS_REVIEW);
            throw SkillErrors.sensitiveWordsNeedReview();
        }
        if (!operation.markSentOnce()) {
            throw SkillErrors.duplicateSend(request.getOperationId());
        }
        operation.setStatus(OperationStatus.SENT);
        MessageSendResponse response = new MessageSendResponse();
        response.setOperationId(operation.getOperationId());
        response.setCustomerId(operation.getCustomerId());
        response.setCustomerName(operation.getCustomerName());
        response.setStatus(OperationStatus.SENT);
        response.setSentAt(OffsetDateTime.now(ZoneOffset.ofHours(8)));
        response.setChannel("MOCK_ENTERPRISE_WECHAT");
        response.setDataSource("MOCK_MESSAGE_GATEWAY_NO_REAL_SEND");
        response.setMock(true);
        return response;
    }

    private void validateVariables(Map<String, String> variables, String templateCode) {
        for (String variable : templateRepository.requiredVariables(templateCode)) {
            String value = variables.get(variable);
            if (value == null || value.trim().length() == 0) {
                throw SkillErrors.missingTemplateVariable(variable);
            }
        }
    }

    private String render(String template, String customerName, Map<String, String> variables) {
        String content = template.replace("{customerName}", customerName);
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            content = content.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return content;
    }

    private List<String> scanSensitiveWords(String content) {
        List<String> result = new ArrayList<String>();
        for (String word : templateRepository.sensitiveWords()) {
            if (content.contains(word)) {
                result.add(word);
            }
        }
        return result;
    }

    private MessagePreviewResponse toPreviewResponse(PendingMessageOperation operation) {
        MessagePreviewResponse response = new MessagePreviewResponse();
        response.setOperationId(operation.getOperationId());
        response.setOperationType("MESSAGE_SEND");
        response.setCustomerId(operation.getCustomerId());
        response.setCustomerName(operation.getCustomerName());
        response.setContent(operation.getContent());
        response.setSensitiveWords(operation.getSensitiveWords());
        response.setStatus(operation.getStatus());
        response.setExpiresAt(operation.getExpiresAt());
        response.setMock(true);
        return response;
    }
}
