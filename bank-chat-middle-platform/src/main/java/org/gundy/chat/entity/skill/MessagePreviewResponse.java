package org.gundy.chat.skill.dto;

import lombok.Data;
import org.gundy.chat.skill.enums.OperationStatus;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class MessagePreviewResponse {
    private String operationId;
    private String operationType;
    private String customerId;
    private String customerName;
    private String content;
    private List<String> sensitiveWords;
    private OperationStatus status;
    private OffsetDateTime expiresAt;
    private boolean mock;

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<String> getSensitiveWords() { return sensitiveWords; }
    public void setSensitiveWords(List<String> sensitiveWords) { this.sensitiveWords = sensitiveWords; }
    public OperationStatus getStatus() { return status; }
    public void setStatus(OperationStatus status) { this.status = status; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public boolean isMock() { return mock; }
    public void setMock(boolean mock) { this.mock = mock; }
}
