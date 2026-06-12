package org.gundy.chat.service;

import org.gundy.chat.entity.HistoryMessage;
import org.gundy.chat.entity.RagRequest;
import org.gundy.chat.entity.RagResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class RagService {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${rag.python.url:http://localhost:8000/rag/query}")
    private String pythonRagUrl;

    public RagResponse query(String question, String sessionId, List<HistoryMessage> history) {
        RagRequest request = new RagRequest();
        request.setQuestion(question);
        request.setSessionId(sessionId);
        request.setHistory(history);

        // 调用 Python 服务
        return restTemplate.postForObject(pythonRagUrl, request, RagResponse.class);
    }
}