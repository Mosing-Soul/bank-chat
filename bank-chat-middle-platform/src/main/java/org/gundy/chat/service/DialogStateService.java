package org.gundy.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gundy.chat.entity.dialog.DialogState;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class DialogStateService {
    private static final String STATE_KEY_PREFIX = "chat:dialog-state:";
    private static final long TTL_SECONDS = 1800;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public DialogStateService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public DialogState getState(String sessionId) {
        String raw = redisTemplate.opsForValue().get(STATE_KEY_PREFIX + sessionId);
        if (raw == null || raw.trim().length() == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, DialogState.class);
        } catch (Exception ex) {
            redisTemplate.delete(STATE_KEY_PREFIX + sessionId);
            return null;
        }
    }

    public void saveState(String sessionId, DialogState state) {
        if (state == null) {
            clearState(sessionId);
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    STATE_KEY_PREFIX + sessionId,
                    objectMapper.writeValueAsString(state),
                    Duration.ofSeconds(TTL_SECONDS));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize dialog state", ex);
        }
    }

    public void clearState(String sessionId) {
        redisTemplate.delete(STATE_KEY_PREFIX + sessionId);
    }
}
