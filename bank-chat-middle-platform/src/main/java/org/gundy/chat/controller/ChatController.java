package org.gundy.chat.controller;

import org.gundy.chat.entity.ChatRequest;
import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.exception.ApplicationException;
import org.gundy.chat.exception.ErrorCode;
import org.gundy.chat.service.ChatApplicationService;
import org.gundy.chat.web.TraceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatApplicationService chatApplicationService;

    public ChatController(ChatApplicationService chatApplicationService) {
        this.chatApplicationService = chatApplicationService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request,
                                             @RequestHeader(value = "X-Trace-Id", required = false) String requestTraceId,
                                             HttpServletRequest httpRequest) {
        String traceId = TraceContext.currentOrCreate(requestTraceId);
        String sessionId = safeSessionId(request);
        String userMessage = request == null ? null : request.effectiveMessage();
        if (!hasText(userMessage)) throw new ApplicationException(ErrorCode.INVALID_REQUEST);
        return ResponseEntity.ok(chatApplicationService.handle(traceId, sessionId, userMessage, request));
    }

    private String safeSessionId(ChatRequest request) {
        return request != null && hasText(request.getSessionId())
                ? request.getSessionId() : UUID.randomUUID().toString();
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
