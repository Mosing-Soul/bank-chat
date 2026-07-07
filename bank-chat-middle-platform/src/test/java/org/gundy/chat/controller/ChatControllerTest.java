package org.gundy.chat.controller;

import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.entity.HistoryMessage;
import org.gundy.chat.entity.intent.IntentRouteResult;
import org.gundy.chat.service.DialogStateMachineService;
import org.gundy.chat.service.DialogStateService;
import org.gundy.chat.service.AiChatService;
import org.gundy.chat.service.IntentRouterService;
import org.gundy.chat.service.MemoryService;
import org.gundy.chat.statemachine.SkillTransitionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {
    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @MockBean
    private MemoryService memoryService;

    @MockBean
    private AiChatService aiChatService;

    @MockBean
    private DialogStateService dialogStateService;

    @MockBean
    private DialogStateMachineService dialogStateMachineService;

    @MockBean
    private IntentRouterService intentRouterService;

    @Test
    void chatInvokesPythonAndReturnsFields() throws Exception {
        when(intentRouterService.route(any(), anyString(), isNull(), anyBoolean())).thenReturn(noRoute());
        when(dialogStateMachineService.handle(anyString(), anyString(), any(), anyString(), isNull(), anyBoolean()))
                .thenReturn(SkillTransitionResult.notHandled());
        when(memoryService.getHistory("s1")).thenReturn(new ArrayList<HistoryMessage>());
        ChatResponse response = ChatResponse.friendlyError("trace-1", "s1", "ok");
        response.setIntent("EXTERNAL_API_QUERY");
        response.setConfidence(0.96D);
        response.setAnswer("gold answer");
        when(aiChatService.invoke(eq("trace-1"), eq("s1"), eq("现在黄金价格是多少？"), anyList(),
                isNull(), eq(false), isNull(), eq(0.0D), isNull())).thenReturn(response);

        mockMvc.perform(post("/api/chat")
                        .header("X-Trace-Id", "trace-1")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\",\"message\":\"现在黄金价格是多少？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId", is("trace-1")))
                .andExpect(jsonPath("$.sessionId", is("s1")))
                .andExpect(jsonPath("$.intent", is("EXTERNAL_API_QUERY")))
                .andExpect(jsonPath("$.answer", is("gold answer")));

        verify(memoryService).addConversation("s1", "现在黄金价格是多少？", "gold answer");
    }

    @Test
    void chatGeneratesTraceIdWhenMissing() throws Exception {
        when(intentRouterService.route(any(), anyString(), isNull(), anyBoolean())).thenReturn(noRoute());
        when(dialogStateMachineService.handle(anyString(), anyString(), any(), anyString(), isNull(), anyBoolean()))
                .thenReturn(SkillTransitionResult.notHandled());
        when(memoryService.getHistory("s1")).thenReturn(new ArrayList<HistoryMessage>());
        ChatResponse response = ChatResponse.friendlyError("", "s1", "ok");
        response.setAnswer("answer");
        when(aiChatService.invoke(anyString(), eq("s1"), eq("你好"), anyList(),
                isNull(), eq(false), isNull(), eq(0.0D), isNull())).thenReturn(response);

        mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\",\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void pythonTimeoutReturnsFriendlyError() throws Exception {
        when(intentRouterService.route(any(), anyString(), isNull(), anyBoolean())).thenReturn(noRoute());
        when(dialogStateMachineService.handle(anyString(), anyString(), any(), anyString(), isNull(), anyBoolean()))
                .thenReturn(SkillTransitionResult.notHandled());
        when(memoryService.getHistory("s1")).thenReturn(new ArrayList<HistoryMessage>());
        when(aiChatService.invoke(eq("trace-timeout"), eq("s1"), eq("你好"), anyList(),
                isNull(), eq(false), isNull(), eq(0.0D), isNull()))
                .thenThrow(new ResourceAccessException("timeout"));

        mockMvc.perform(post("/api/chat")
                        .header("X-Trace-Id", "trace-timeout")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\",\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("AI 服务响应超时或不可用，请稍后再试。")));
    }

    @Test
    void forcedExternalSkillClearsStateAndInvokesPythonWithoutHistory() throws Exception {
        when(intentRouterService.route(any(), eq("黄金价格"), eq("GOLD_PRICE"), eq(true))).thenReturn(frontendRoute("GOLD_PRICE"));
        SkillTransitionResult passThrough = SkillTransitionResult.notHandled();
        passThrough.setClearState(true);
        when(dialogStateMachineService.handle(anyString(), eq("s1"), any(), eq("黄金价格"), eq("GOLD_PRICE"), eq(true)))
                .thenReturn(passThrough);
        ChatResponse response = ChatResponse.friendlyError("trace-force", "s1", "ok");
        response.setIntent("EXTERNAL_API_QUERY");
        response.setAnswer("gold answer");
        when(aiChatService.invoke(eq("trace-force"), eq("s1"), eq("黄金价格"), eq(new ArrayList<HistoryMessage>()),
                eq("GOLD_PRICE"), eq(true), eq("GOLD_PRICE"), eq(0.99D), isNull())).thenReturn(response);

        mockMvc.perform(post("/api/chat")
                        .header("X-Trace-Id", "trace-force")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\",\"message\":\"黄金价格\",\"requestedSkill\":\"GOLD_PRICE\",\"forceSkill\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent", is("EXTERNAL_API_QUERY")))
                .andExpect(jsonPath("$.answer", is("gold answer")));

        verify(dialogStateService).clearState("s1");
        verify(aiChatService).invoke(eq("trace-force"), eq("s1"), eq("黄金价格"), eq(new ArrayList<HistoryMessage>()),
                eq("GOLD_PRICE"), eq(true), eq("GOLD_PRICE"), eq(0.99D), isNull());
    }

    @Test
    void routerForcesInstitutionKnowledgeQuestionToRag() throws Exception {
        IntentRouteResult route = new IntentRouteResult();
        route.setRequestedSkill("RAG_QUERY");
        route.setForceSkill(true);
        route.setClearHistory(true);
        route.setConfidence(0.92D);
        route.setReason("institution knowledge query");
        when(intentRouterService.route(any(), eq("招行的客户等级是怎么样的"), isNull(), eq(false))).thenReturn(route);
        SkillTransitionResult passThrough = SkillTransitionResult.notHandled();
        passThrough.setClearState(true);
        when(dialogStateMachineService.handle(anyString(), eq("s1"), any(), eq("招行的客户等级是怎么样的"),
                eq("RAG_QUERY"), eq(true))).thenReturn(passThrough);
        ChatResponse response = ChatResponse.friendlyError("trace-rag", "s1", "ok");
        response.setIntent("KNOWLEDGE_QA");
        response.setAnswer("rag answer");
        when(aiChatService.invoke(eq("trace-rag"), eq("s1"), eq("招行的客户等级是怎么样的"),
                eq(new ArrayList<HistoryMessage>()), eq("RAG_QUERY"), eq(true), eq("RAG_QUERY"), eq(0.92D), isNull()))
                .thenReturn(response);

        mockMvc.perform(post("/api/chat")
                        .header("X-Trace-Id", "trace-rag")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\",\"message\":\"招行的客户等级是怎么样的\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent", is("KNOWLEDGE_QA")))
                .andExpect(jsonPath("$.answer", is("rag answer")));
    }

    @Test
    void blankMessageReturnsFriendlyBadRequest() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\",\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.answer", is("请输入要咨询或办理的内容。")));
    }

    private IntentRouteResult noRoute() {
        IntentRouteResult result = new IntentRouteResult();
        result.setConfidence(0.0D);
        result.setReason("no deterministic route");
        return result;
    }

    private IntentRouteResult frontendRoute(String skill) {
        IntentRouteResult result = new IntentRouteResult();
        result.setRequestedSkill(skill);
        result.setForceSkill(true);
        result.setClearHistory(true);
        result.setConfidence(0.99D);
        result.setReason("frontend requested skill");
        return result;
    }
}
