package org.gundy.chat.controller;

import lombok.extern.slf4j.Slf4j;
import org.gundy.chat.entity.ChatRequest;
import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.service.AnalyticsService;
import org.gundy.chat.service.ChatApplicationService;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import javax.servlet.http.HttpServletRequest;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatApplicationService chatApplicationService;
    private final AnalyticsService analyticsService;

    public ChatController(ChatApplicationService chatApplicationService, AnalyticsService analyticsService) {
        this.chatApplicationService = chatApplicationService;
        this.analyticsService = analyticsService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request,
                                             @RequestHeader(value = "X-Trace-Id", required = false) String requestTraceId,
                                             @RequestHeader(value = "X-Client-Id", required = false) String clientId,
                                             @RequestHeader(value = "X-Internal-Visitor", required = false) String internalVisitor,
                                             HttpServletRequest httpRequest) {
        String traceId = hasText(requestTraceId) ? requestTraceId : UUID.randomUUID().toString();
        String sessionId = safeSessionId(request);
        MDC.put("traceId", traceId);
        long start = System.currentTimeMillis();
        try {
            String userMessage = request.effectiveMessage();
            if (!hasText(userMessage)) {
                return ResponseEntity.badRequest().body(ChatResponse.friendlyError(
                        traceId, sessionId, "请输入要咨询或办理的内容。"));
            }
            ChatResponse response = chatApplicationService.handle(
                    traceId, sessionId, userMessage, request);
            analyticsService.recordChat(httpRequest, traceId, sessionId, clientId, internalVisitor,
                    userMessage, response, response.getError() == null ? "SUCCESS" : "ERROR", start, null);
            return ResponseEntity.ok(response);
        } catch (ResourceAccessException ex) {
            log.warn("python chat timeout or unavailable, durationMs={}", System.currentTimeMillis() - start);
            ChatResponse response = ChatResponse.friendlyError(
                    traceId, sessionId, "AI 服务响应超时或不可用，请稍后再试。");
            analyticsService.recordChat(httpRequest, traceId, sessionId, clientId, internalVisitor,
                    safeMessage(request), response, "TIMEOUT", start, ex);
            return ResponseEntity.ok(response);
        } catch (RestClientException ex) {
            log.warn("python chat call failed, durationMs={}", System.currentTimeMillis() - start);
            ChatResponse response = ChatResponse.friendlyError(
                    traceId, sessionId, "AI 服务调用失败，请稍后再试。");
            analyticsService.recordChat(httpRequest, traceId, sessionId, clientId, internalVisitor,
                    safeMessage(request), response, "CALL_FAILED", start, ex);
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            analyticsService.recordChat(httpRequest, traceId, sessionId, clientId, internalVisitor,
                    safeMessage(request), null, "EXCEPTION", start, ex);
            throw ex;
        } finally {
            MDC.remove("traceId");
        }
    }

    private String safeMessage(ChatRequest request) {
        return request == null ? null : request.effectiveMessage();
    }

    private String safeSessionId(ChatRequest request) {
        return request != null && hasText(request.getSessionId())
                ? request.getSessionId() : UUID.randomUUID().toString();
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
