package org.gundy.chat.skill.model;

import lombok.Data;
import org.gundy.chat.skill.enums.OperationStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Data
public class PendingMessageOperation {
    private final String operationId;
    private final String customerId;
    private final String customerName;
    private final String content;
    private final List<String> sensitiveWords;
    private final OffsetDateTime expiresAt;
    private volatile OperationStatus status;
    private final AtomicBoolean sent = new AtomicBoolean(false);

    public PendingMessageOperation(String operationId, String customerId, String customerName,
                                   String content, List<String> sensitiveWords,
                                   OffsetDateTime expiresAt, OperationStatus status) {
        this.operationId = operationId;
        this.customerId = customerId;
        this.customerName = customerName;
        this.content = content;
        this.sensitiveWords = sensitiveWords;
        this.expiresAt = expiresAt;
        this.status = status;
    }

    public String getOperationId() {
        return operationId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getContent() {
        return content;
    }

    public List<String> getSensitiveWords() {
        return sensitiveWords;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OperationStatus getStatus() {
        return status;
    }

    public void setStatus(OperationStatus status) {
        this.status = status;
    }

    public boolean markSentOnce() {
        return sent.compareAndSet(false, true);
    }
}
