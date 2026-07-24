package org.gundy.chat.service;

import org.gundy.chat.entity.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiChatServiceTest {
    @Test
    void simplifiedGoldInvocationSendsEmptyCollectionsInsteadOfNull() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AiChatService service = new AiChatService(restTemplate, "http://localhost:8000/ai/chat/invoke");

        server.expect(requestTo("http://localhost:8000/ai/chat/invoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"history\":[]")))
                .andExpect(content().string(containsString("\"entities\":{}")))
                .andExpect(content().string(containsString("\"skillExamples\":{}")))
                .andExpect(content().string(not(containsString("\"entities\":null"))))
                .andRespond(withSuccess("{\"traceId\":\"t1\",\"sessionId\":\"s1\","
                        + "\"intent\":\"EXTERNAL_API_QUERY\",\"confidence\":0.98,"
                        + "\"answer\":\"gold answer\"}", MediaType.APPLICATION_JSON));

        ChatResponse response = service.invoke("t1", "s1", "黄金价格", null, "GOLD_PRICE", true);

        org.assertj.core.api.Assertions.assertThat(response.getAnswer()).isEqualTo("gold answer");
        server.verify();
    }
}
