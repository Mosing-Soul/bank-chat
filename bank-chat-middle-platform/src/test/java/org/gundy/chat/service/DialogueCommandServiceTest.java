package org.gundy.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gundy.chat.entity.command.DialogCommandType;
import org.gundy.chat.entity.command.DialogueCommandResponse;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DialogueCommandServiceTest {

    @Test
    void sendsSkillContextButRedactsSensitiveSlotValues() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        SkillDefinitionRegistry registry = new SkillDefinitionRegistry(new ObjectMapper(),
                new DefaultResourceLoader(), new SkillDefinitionValidator(),
                "classpath:config/skill-definitions.json");
        DialogueCommandService service = new DialogueCommandService(restTemplate, registry,
                "http://localhost:8000/ai/dialogue/commands");

        server.expect(requestTo("http://localhost:8000/ai/dialogue/commands"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("flow-slot://msg-1/customerReference")))
                .andExpect(content().string(not(containsString("张伟"))))
                .andExpect(content().string(containsString("产品到期提醒")))
                .andRespond(withSuccess("{\"traceId\":\"t1\",\"sessionId\":\"s1\","
                        + "\"commands\":[{\"type\":\"REQUEST_CLARIFICATION\",\"confidence\":0.8}],"
                        + "\"modelUsed\":false,\"reason\":\"test\"}", MediaType.APPLICATION_JSON));

        DialogueCommandResponse response = service.interpret("t1", "s1", "换个业务",
                messageState(), new ArrayList<org.gundy.chat.entity.HistoryMessage>());

        assertThat(response.getCommands()).hasSize(1);
        assertThat(response.getCommands().get(0).getType()).isEqualTo(DialogCommandType.REQUEST_CLARIFICATION);
        server.verify();
    }

    private DialogState messageState() {
        FlowInstance flow = new FlowInstance();
        flow.setInstanceId("msg-1");
        flow.setFlowId("MESSAGE_SEND_FLOW");
        flow.setSkillId("MESSAGE_SEND");
        flow.setStatus("ACTIVE");
        flow.setCurrentStage("WAITING_CONFIRMATION");
        flow.getSlots().put("customerReference", "张伟");
        flow.getSlots().put("messagePurpose", "产品到期提醒");
        DialogState state = new DialogState();
        state.getFlowStack().add(flow);
        state.setActiveSkill("MESSAGE_SEND");
        state.setActiveFlowId("msg-1");
        return state;
    }
}
