package org.gundy.chat.controller;

import org.gundy.chat.entity.ChatRequest;
import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.entity.HistoryMessage;
import org.gundy.chat.entity.RagResponse;
import org.gundy.chat.service.MemoryService;
import org.gundy.chat.service.RagService;
//import org.gundy.chat.service.TempMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private MemoryService memoryService;

//    @Autowired
//    private TempMemoryService memoryService;

    @Autowired
    private RagService ragService;   // 原有的调用 Python 服务

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        String question = request.getQuestion();

        // 1. 获取历史对话
        List<HistoryMessage> history = memoryService.getHistory(sessionId);

        // 2. 调用 Python RAG 服务（携带历史）
        RagResponse ragResp = ragService.query(question, sessionId, history);

        // 3. 保存新对话
        memoryService.addConversation(sessionId, question, ragResp.getAnswer());

        // 4. 返回
        return ResponseEntity.ok(new ChatResponse(ragResp.getAnswer(), ragResp.getSources(), sessionId));
    }
}