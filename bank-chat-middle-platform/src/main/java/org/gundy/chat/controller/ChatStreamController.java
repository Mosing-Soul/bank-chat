package org.gundy.chat.controller;

import lombok.extern.slf4j.Slf4j;
import org.gundy.chat.entity.ChatRequest;
import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.exception.ErrorCode;
import org.gundy.chat.exception.ExceptionClassifier;
import org.gundy.chat.progress.DialogueProgress;
import org.gundy.chat.web.TraceContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatStreamController {
    private final ChatController chatController;

    public ChatStreamController(ChatController chatController) { this.chatController = chatController; }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request,
                             @RequestHeader(value = "X-Trace-Id", required = false) String requestTraceId) {
        final String traceId = TraceContext.currentOrCreate(requestTraceId);
        final String sessionId = request != null && request.getSessionId() != null
                ? request.getSessionId() : "";
        final SseEmitter emitter = new SseEmitter(125000L);
        CompletableFuture.runAsync(() -> {
            DialogueProgress.install(event -> send(emitter, "progress", event));
            try {
                DialogueProgress.report("REQUEST_RECEIVED", "已接收问题", "请求已进入对话处理流程");
                ResponseEntity<ChatResponse> response = chatController.chat(request, traceId);
                send(emitter, "result", response.getBody());
                emitter.complete();
            } catch (Exception ex) {
                ErrorCode errorCode = ExceptionClassifier.classify(ex);
                log.error("SSE chat failed, traceId={}, code={}", traceId, errorCode.name(), ex);
                send(emitter, "result", ChatResponse.failure(traceId, sessionId, errorCode));
                emitter.complete();
            } finally {
                DialogueProgress.clear();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException ignored) {
            // Client disconnects are handled by the emitter lifecycle.
        }
    }
}
