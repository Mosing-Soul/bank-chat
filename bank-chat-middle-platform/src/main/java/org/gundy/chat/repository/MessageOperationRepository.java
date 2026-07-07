package org.gundy.chat.skill.repository;

import org.gundy.chat.skill.model.PendingMessageOperation;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class MessageOperationRepository {
    private final ConcurrentHashMap<String, PendingMessageOperation> operations =
            new ConcurrentHashMap<String, PendingMessageOperation>();

    public void save(PendingMessageOperation operation) {
        operations.put(operation.getOperationId(), operation);
    }

    public PendingMessageOperation findById(String operationId) {
        return operations.get(operationId);
    }

    public void cleanupExpired() {
        OffsetDateTime now = OffsetDateTime.now();
        Iterator<Map.Entry<String, PendingMessageOperation>> iterator = operations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PendingMessageOperation> entry = iterator.next();
            if (entry.getValue().getExpiresAt().isBefore(now)) {
                iterator.remove();
            }
        }
    }
}
