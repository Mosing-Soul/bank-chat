package org.gundy.chat.controller;

import org.gundy.chat.entity.ChatRequest;
import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.progress.DialogueProgress;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/chat")
public class ChatStreamController {
    private final ChatController chatController;

    public ChatStreamController(ChatController chatController) { this.chatController = chatController; }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request,
                             @RequestHeader(value = "X-Trace-Id", required = false) String requestTraceId) {
        final String traceId = requestTraceId == null || requestTraceId.trim().length() == 0
                ? UUID.randomUUID().toString() : requestTraceId;
        final SseEmitter emitter = new SseEmitter(35000L);
        CompletableFuture.runAsync(() -> {
            DialogueProgress.install(event -> send(emitter, "progress", event));
            try {
                DialogueProgress.report("REQUEST_RECEIVED", "正在处理您的请求", "已安全接收本次对话");
                ResponseEntity<ChatResponse> response = chatController.chat(request, traceId);
                send(emitter, "result", response.getBody());
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
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
