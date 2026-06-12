//package org.gundy.chat.service;
//
//import org.gundy.chat.entity.HistoryMessage;
//import org.springframework.stereotype.Service;
//
//import javax.annotation.PostConstruct;
//import java.time.Instant;
//import java.time.temporal.ChronoUnit;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.Executors;
//import java.util.concurrent.ScheduledExecutorService;
//import java.util.concurrent.TimeUnit;
//import java.util.stream.Collectors;
//
//@Service
//public class TempMemoryService {
//    private final Map<String, List<HistoryMessage>> memory = new ConcurrentHashMap<>();
//    private final Map<String, Instant> lastAccessTime = new ConcurrentHashMap<>();
//    private static final int MAX_HISTORY_SIZE = 6;
//    private static final long TTL_MINUTES = 30;
//
//    @PostConstruct
//    public void init() {
//        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
//        scheduler.scheduleAtFixedRate(this::cleanExpiredSessions, 1, 5, TimeUnit.MINUTES);
//    }
//
//    public List<HistoryMessage> getHistory(String sessionId) {
//        List<HistoryMessage> history = memory.get(sessionId);
//        if (history != null) {
//            lastAccessTime.put(sessionId, Instant.now());
//        }
//        return history != null ? new ArrayList<>(history) : new ArrayList<>();
//    }
//
//    public void addConversation(String sessionId, String userQuestion, String assistantAnswer) {
//        List<HistoryMessage> history = memory.computeIfAbsent(sessionId, k -> new ArrayList<>());
//        history.add(new HistoryMessage("user", userQuestion));
//        history.add(new HistoryMessage("assistant", assistantAnswer));
//        if (history.size() > MAX_HISTORY_SIZE) {
//            history.subList(0, history.size() - MAX_HISTORY_SIZE).clear();
//        }
//        lastAccessTime.put(sessionId, Instant.now());
//    }
//
//    public void clearHistory(String sessionId) {
//        memory.remove(sessionId);
//        lastAccessTime.remove(sessionId);
//    }
//
//    private void cleanExpiredSessions() {
//        Instant now = Instant.now();
//        List<String> expired = lastAccessTime.entrySet().stream()
//                .filter(entry -> entry.getValue().plus(TTL_MINUTES, ChronoUnit.MINUTES).isBefore(now))
//                .map(Map.Entry::getKey)
//                .collect(Collectors.toList());
//        expired.forEach(sessionId -> {
//            memory.remove(sessionId);
//            lastAccessTime.remove(sessionId);
//        });
//    }
//}
