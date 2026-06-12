package org.gundy.chat.service;

import org.gundy.chat.entity.HistoryMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class MemoryService {

    @Autowired
    private RedisTemplate<String, List<HistoryMessage>> redisTemplate;

    private static final String MEMORY_KEY_PREFIX = "chat:memory:";
    private static final int MAX_HISTORY_SIZE = 6;   // 保留最近6条消息（3轮问答）
    private static final long TTL_SECONDS = 1800;    // 30分钟

    /**
     * 获取会话历史消息
     */
    public List<HistoryMessage> getHistory(String sessionId) {
        String key = MEMORY_KEY_PREFIX + sessionId;
        List<HistoryMessage> history = redisTemplate.opsForValue().get(key);
        return history != null ? history : new ArrayList<>();
    }

    /**
     * 追加新的消息对（用户问题 + 助手回答）
     */
    public void addConversation(String sessionId, String userQuestion, String assistantAnswer) {
        String key = MEMORY_KEY_PREFIX + sessionId;
        List<HistoryMessage> history = getHistory(sessionId);
        // 添加用户消息
        history.add(new HistoryMessage("user", userQuestion));
        // 添加助手消息
        history.add(new HistoryMessage("assistant", assistantAnswer));
        // 只保留最近 MAX_HISTORY_SIZE 条
        if (history.size() > MAX_HISTORY_SIZE) {
            history = history.subList(history.size() - MAX_HISTORY_SIZE, history.size());
        }
        redisTemplate.opsForValue().set(key, history, Duration.ofSeconds(TTL_SECONDS));
    }

    /**
     * 清空会话（可选）
     */
    public void clearHistory(String sessionId) {
        String key = MEMORY_KEY_PREFIX + sessionId;
        redisTemplate.delete(key);
    }
}