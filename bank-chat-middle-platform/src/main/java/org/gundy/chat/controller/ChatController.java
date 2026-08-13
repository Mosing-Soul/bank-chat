package org.gundy.chat.controller;

import lombok.extern.slf4j.Slf4j;
import org.gundy.chat.entity.ChatRequest;
import org.gundy.chat.entity.ChatResponse;
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

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatApplicationService chatApplicationService;

    public ChatController(ChatApplicationService chatApplicationService) {
        this.chatApplicationService = chatApplicationService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request,
                                             @RequestHeader(value = "X-Trace-Id", required = false) String requestTraceId) {
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
            return ResponseEntity.ok(chatApplicationService.handle(
                    traceId, sessionId, userMessage, request));
        } catch (ResourceAccessException ex) {
            log.warn("python chat timeout or unavailable, durationMs={}", System.currentTimeMillis() - start);
            return ResponseEntity.ok(ChatResponse.friendlyError(
                    traceId, sessionId, "AI 服务响应超时或不可用，请稍后再试。"));
        } catch (RestClientException ex) {
            log.warn("python chat call failed, durationMs={}", System.currentTimeMillis() - start);
            return ResponseEntity.ok(ChatResponse.friendlyError(
                    traceId, sessionId, "AI 服务调用失败，请稍后再试。"));
        } finally {
            MDC.remove("traceId");
        }
    }

    private String safeSessionId(ChatRequest request) {
        return request != null && hasText(request.getSessionId())
                ? request.getSessionId() : UUID.randomUUID().toString();
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
