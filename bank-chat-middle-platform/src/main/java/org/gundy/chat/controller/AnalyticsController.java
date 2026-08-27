package org.gundy.chat.controller;

import lombok.extern.slf4j.Slf4j;
import org.gundy.chat.service.AnalyticsService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * 埋点入口：
 * - POST /api/analytics/event：前端访问上报；
 * - GET {bank.analytics.view-path}：埋点控制台页面（数据总览 / 对话记录 / 报错记录三个 Tab），
 *   页面内部调用同路径下的 /summary 与 /events 数据接口。
 */
@Slf4j
@RestController
public class AnalyticsController {
    private final AnalyticsService analyticsService;
    private final String consolePage;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
        this.consolePage = loadConsolePage();
    }

    @PostMapping("/api/analytics/event")
    public ResponseEntity<Map<String, Object>> reportEvent(@RequestBody(required = false) Map<String, Object> body,
                                                           @RequestHeader(value = "X-Client-Id", required = false) String clientId,
                                                           @RequestHeader(value = "X-Internal-Visitor", required = false) String internalVisitor,
                                                           HttpServletRequest request) {
        String sessionId = body == null || body.get("sessionId") == null ? null : String.valueOf(body.get("sessionId"));
        analyticsService.recordPageView(request, sessionId, clientId, internalVisitor);
        return ResponseEntity.ok(Collections.singletonMap("ok", true));
    }

    @GetMapping(value = "${bank.analytics.view-path:/api/analytics/admin}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> console() {
        return ResponseEntity.ok(consolePage);
    }

    @GetMapping("${bank.analytics.view-path:/api/analytics/admin}/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(analyticsService.summary());
    }

    @GetMapping("${bank.analytics.view-path:/api/analytics/admin}/events")
    public ResponseEntity<?> events(@RequestParam(value = "limit", defaultValue = "50") int limit,
                                    @RequestParam(value = "errorsOnly", defaultValue = "false") boolean errorsOnly) {
        return ResponseEntity.ok(analyticsService.recentChats(limit, errorsOnly));
    }

    private static String loadConsolePage() {
        try (InputStream in = AnalyticsController.class.getResourceAsStream("/analytics/console.html")) {
            if (in == null) {
                return "console page missing";
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("Failed to load analytics console page: {}", ex.toString());
            return "console page unavailable";
        }
    }
}
