package org.gundy.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.gundy.chat.entity.AnalyticsEvent;
import org.gundy.chat.entity.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量埋点服务：访问与问答数据异步写入本地 SQLite，不影响对话主流程。
 * 白名单（命中任意一条即不落库）：internal 访客标记、clientId 名单、IP 前缀名单。
 */
@Slf4j
@Service
public class AnalyticsService {

    private static final int MAX_TEXT_LENGTH = 4000;

    private final boolean enabled;
    private final String sqlitePath;
    private final Set<String> whitelistedClientIds;
    private final List<String> whitelistedIpPrefixes;
    private final ExecutorService writer;

    public AnalyticsService(@Value("${bank.analytics.enabled:true}") boolean enabled,
                            @Value("${bank.analytics.sqlite-path:data/bank-chat-analytics.db}") String sqlitePath,
                            @Value("${bank.analytics.whitelist.client-ids:}") String clientIdWhitelist,
                            @Value("${bank.analytics.whitelist.ip-prefixes:127.0.0.1,0:0:0:0:0:0:0:1}") String ipPrefixWhitelist) {
        this.enabled = enabled;
        this.sqlitePath = sqlitePath;
        this.whitelistedClientIds = splitToSet(clientIdWhitelist);
        this.whitelistedIpPrefixes = new ArrayList<>(splitToSet(ipPrefixWhitelist));
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "analytics-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("analytics disabled");
            return;
        }
        try {
            Path dbPath = Paths.get(sqlitePath);
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("CREATE TABLE IF NOT EXISTS analytics_event (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "event_type TEXT NOT NULL," +
                        "status TEXT," +
                        "session_id TEXT," +
                        "client_id TEXT," +
                        "trace_id TEXT," +
                        "ip TEXT," +
                        "user_agent TEXT," +
                        "intent TEXT," +
                        "question TEXT," +
                        "answer TEXT," +
                        "error_message TEXT," +
                        "duration_ms INTEGER," +
                        "created_at TEXT NOT NULL)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_analytics_event_created_at ON analytics_event (created_at)");
            }
        } catch (Exception ex) {
            log.warn("Failed to initialize analytics store: {}", ex.toString());
        }
    }

    @PreDestroy
    public void shutdown() {
        writer.shutdownNow();
    }

    public void recordPageView(HttpServletRequest httpRequest, String sessionId, String clientId, String internalVisitor) {
        if (!enabled) {
            return;
        }
        AnalyticsEvent event = new AnalyticsEvent();
        event.setEventType("PAGE_VIEW");
        event.setSessionId(sessionId);
        event.setClientId(clientId);
        event.setInternalVisitor(parseBool(internalVisitor));
        event.setIp(clientIp(httpRequest));
        event.setUserAgent(header(httpRequest, "User-Agent"));
        record(event);
    }

    public void recordChat(HttpServletRequest httpRequest, String traceId, String sessionId,
                           String clientId, String internalVisitor, String question,
                           ChatResponse response, String status, long startMs, Throwable failure) {
        if (!enabled) {
            return;
        }
        AnalyticsEvent event = new AnalyticsEvent();
        event.setEventType("CHAT");
        event.setStatus(status);
        event.setSessionId(sessionId);
        event.setClientId(clientId);
        event.setInternalVisitor(parseBool(internalVisitor));
        event.setTraceId(traceId);
        event.setIp(clientIp(httpRequest));
        event.setUserAgent(header(httpRequest, "User-Agent"));
        event.setQuestion(question);
        event.setDurationMs(System.currentTimeMillis() - startMs);
        if (response != null) {
            event.setIntent(response.getIntent());
            event.setAnswer(response.getAnswer());
            Map<String, Object> error = response.getError();
            if (error != null) {
                Object code = error.get("code");
                event.setErrorMessage(truncate((code == null ? "" : code + ": ") + error.get("message"), 1000));
            }
        }
        if (failure != null) {
            event.setErrorMessage(truncate(failure.toString(), 1000));
        }
        record(event);
    }

    public void record(AnalyticsEvent event) {
        if (!enabled || event == null) {
            return;
        }
        if (isWhitelisted(event.getClientId(), event.getIp(), event.isInternalVisitor())) {
            return;
        }
        event.setCreatedAt(Instant.now().toString());
        writer.execute(() -> insert(event));
    }

    public boolean isWhitelisted(String clientId, String ip, boolean internalVisitor) {
        if (internalVisitor) {
            return true;
        }
        if (clientId != null && whitelistedClientIds.contains(clientId)) {
            return true;
        }
        if (ip != null) {
            for (String prefix : whitelistedIpPrefixes) {
                if (ip.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        if (!enabled) {
            return result;
        }
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            Map<String, Object> totals = new LinkedHashMap<>();
            try (ResultSet rs = statement.executeQuery(
                    "SELECT SUM(CASE WHEN event_type='PAGE_VIEW' THEN 1 ELSE 0 END) AS pageViews," +
                    " COUNT(DISTINCT CASE WHEN event_type='PAGE_VIEW' THEN client_id END) AS visitors," +
                    " SUM(CASE WHEN event_type='CHAT' THEN 1 ELSE 0 END) AS chats," +
                    " SUM(CASE WHEN event_type='CHAT' AND status != 'SUCCESS' THEN 1 ELSE 0 END) AS failedChats" +
                    " FROM analytics_event")) {
                if (rs.next()) {
                    totals.put("pageViews", rs.getInt("pageViews"));
                    totals.put("uniqueVisitors", rs.getInt("visitors"));
                    totals.put("chats", rs.getInt("chats"));
                    totals.put("failedChats", rs.getInt("failedChats"));
                }
            }
            List<Map<String, Object>> daily = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery(
                    "SELECT substr(created_at, 1, 10) AS day," +
                    " SUM(CASE WHEN event_type='PAGE_VIEW' THEN 1 ELSE 0 END) AS pageViews," +
                    " COUNT(DISTINCT CASE WHEN event_type='PAGE_VIEW' THEN client_id END) AS visitors," +
                    " SUM(CASE WHEN event_type='CHAT' THEN 1 ELSE 0 END) AS chats," +
                    " SUM(CASE WHEN event_type='CHAT' AND status != 'SUCCESS' THEN 1 ELSE 0 END) AS failedChats" +
                    " FROM analytics_event GROUP BY day ORDER BY day DESC LIMIT 30")) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("day", rs.getString("day"));
                    row.put("pageViews", rs.getInt("pageViews"));
                    row.put("visitors", rs.getInt("visitors"));
                    row.put("chats", rs.getInt("chats"));
                    row.put("failedChats", rs.getInt("failedChats"));
                    daily.add(row);
                }
            }
            result.put("totals", totals);
            result.put("daily", daily);
        } catch (Exception ex) {
            result.put("error", ex.toString());
        }
        return result;
    }

    public List<Map<String, Object>> recentChats(int limit, boolean errorsOnly) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!enabled) {
            return rows;
        }
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        String sql = "SELECT id, created_at, status, session_id, client_id, trace_id, intent, question, answer, error_message, duration_ms" +
                " FROM analytics_event WHERE event_type = 'CHAT'" +
                (errorsOnly ? " AND status != 'SUCCESS'" : "") +
                " ORDER BY id DESC LIMIT ?";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, safeLimit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("createdAt", rs.getString("created_at"));
                    row.put("status", rs.getString("status"));
                    row.put("sessionId", rs.getString("session_id"));
                    row.put("clientId", rs.getString("client_id"));
                    row.put("traceId", rs.getString("trace_id"));
                    row.put("intent", rs.getString("intent"));
                    row.put("question", rs.getString("question"));
                    row.put("answer", rs.getString("answer"));
                    row.put("errorMessage", rs.getString("error_message"));
                    row.put("durationMs", rs.getLong("duration_ms"));
                    rows.add(row);
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to query analytics events: {}", ex.toString());
        }
        return rows;
    }

    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && forwarded.trim().length() > 0) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && realIp.trim().length() > 0) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private void insert(AnalyticsEvent event) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO analytics_event (event_type, status, session_id, client_id, trace_id, ip, user_agent," +
                     " intent, question, answer, error_message, duration_ms, created_at)" +
                     " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, event.getEventType());
            statement.setString(2, event.getStatus());
            statement.setString(3, truncate(event.getSessionId(), 64));
            statement.setString(4, truncate(event.getClientId(), 64));
            statement.setString(5, truncate(event.getTraceId(), 64));
            statement.setString(6, truncate(event.getIp(), 64));
            statement.setString(7, truncate(event.getUserAgent(), 500));
            statement.setString(8, truncate(event.getIntent(), 64));
            statement.setString(9, truncate(event.getQuestion(), MAX_TEXT_LENGTH));
            statement.setString(10, truncate(event.getAnswer(), MAX_TEXT_LENGTH));
            statement.setString(11, truncate(event.getErrorMessage(), 1000));
            if (event.getDurationMs() == null) {
                statement.setNull(12, Types.INTEGER);
            } else {
                statement.setLong(12, event.getDurationMs());
            }
            statement.setString(13, event.getCreatedAt());
            statement.executeUpdate();
        } catch (Exception ex) {
            log.warn("Failed to insert analytics event: {}", ex.toString());
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
    }

    private static String header(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    private static boolean parseBool(String value) {
        return "true".equalsIgnoreCase(value);
    }

    private static Set<String> splitToSet(String value) {
        Set<String> values = new HashSet<>();
        if (value == null) {
            return values;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (trimmed.length() > 0) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
