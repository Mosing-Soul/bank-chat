package org.gundy.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gundy.chat.command.DialogCommandDispatcher;
import org.gundy.chat.entity.command.DialogCommand;
import org.gundy.chat.entity.command.DialogCommandType;
import org.gundy.chat.entity.command.DialogueCommandResponse;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.gundy.chat.flow.FlowEngine;
import org.gundy.chat.flow.FlowSkillHandler;
import org.gundy.chat.flow.MessageSendFlowHandler;
import org.gundy.chat.policy.ConversationPolicy;
import org.gundy.chat.skill.dto.CustomerSummaryResponse;
import org.gundy.chat.skill.dto.MessagePreviewRequest;
import org.gundy.chat.skill.dto.MessagePreviewResponse;
import org.gundy.chat.skill.dto.MessageSendRequest;
import org.gundy.chat.skill.dto.MessageSendResponse;
import org.gundy.chat.skill.enums.CustomerLevel;
import org.gundy.chat.skill.enums.OperationStatus;
import org.gundy.chat.skill.enums.RiskLevel;
import org.gundy.chat.skill.service.CustomerSkillService;
import org.gundy.chat.skill.service.MessageSkillService;
import org.gundy.chat.statemachine.SkillTransitionResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.OffsetDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DialogueOrchestrationServiceTest {
    @Test
    void confirmCommandExecutesSideEffectInTheSameTurn() {
        CustomerSkillService customerService = mock(CustomerSkillService.class);
        MessageSkillService messageService = mock(MessageSkillService.class);
        when(customerService.searchCustomers("张伟")).thenReturn(Collections.singletonList(
                new CustomerSummaryResponse("C001", "张伟", CustomerLevel.PRIVATE_BANKING, RiskLevel.C3_BALANCED, true)));
        when(messageService.preview(any(MessagePreviewRequest.class))).thenReturn(preview());
        when(messageService.send(any(MessageSendRequest.class))).thenReturn(sent());
        Fixture fixture = fixture(customerService, messageService);
        DialogState waiting = fixture.engine.handle("t1", "s1", null, "MESSAGE_SEND",
                "给张伟发送产品到期提醒").getDialogState();
        FlowInstance active = fixture.engine.activeFlow(waiting);
        assertThat(active.getCurrentStage()).isEqualTo("WAITING_CONFIRMATION");
        DialogueCommandResponse response = response(command(DialogCommandType.CONFIRM, active));
        when(fixture.commandService.interpret(eq("t2"), eq("s1"), eq("确认发送"), eq(waiting), anyList()))
                .thenReturn(response);

        SkillTransitionResult result = fixture.orchestrator.tryHandle("t2", "s1", "确认发送", waiting,
                Collections.emptyList(), null, false);

        assertThat(result.isTerminal()).isTrue();
        assertThat(active.getCurrentStage()).isEqualTo("COMPLETED");
        verify(messageService).send(any(MessageSendRequest.class));
    }

    @Test
    void noOpFallsBackToLegacyRouting() {
        Fixture fixture = fixture(mock(CustomerSkillService.class), mock(MessageSkillService.class));
        when(fixture.commandService.interpret(any(), any(), any(), any(), anyList()))
                .thenReturn(response(command(DialogCommandType.NO_OP, null)));

        assertThat(fixture.orchestrator.tryHandle("t", "s", "你好", null, Collections.emptyList(), null, false)
                .isHandled()).isFalse();
    }

    private Fixture fixture(CustomerSkillService customerService, MessageSkillService messageService) {
        SkillDefinitionRegistry registry = new SkillDefinitionRegistry(new ObjectMapper(), new DefaultResourceLoader(),
                new SkillDefinitionValidator(), "classpath:config/skill-definitions.json");
        FlowSkillHandler handler = new MessageSendFlowHandler(customerService, messageService);
        FlowEngine engine = new FlowEngine(registry, Collections.singletonList(handler));
        DialogCommandDispatcher dispatcher = new DialogCommandDispatcher(new ConversationPolicy(registry), engine, registry,
                new ObjectMapper().findAndRegisterModules());
        DialogueCommandService commandService = mock(DialogueCommandService.class);
        FlowRecoveryService recovery = new FlowRecoveryService(engine, registry, true);
        return new Fixture(engine, commandService,
                new DialogueOrchestrationService(commandService, dispatcher, engine, recovery,
                        new DialogueMetricsService(), true));
    }

    private DialogCommand command(DialogCommandType type, FlowInstance target) {
        DialogCommand command = new DialogCommand();
        command.setType(type);
        command.setConfidence(0.98D);
        if (target != null) {
            command.setTargetSkill(target.getSkillId());
            command.setTargetFlowInstanceId(target.getInstanceId());
        }
        return command;
    }

    private DialogueCommandResponse response(DialogCommand command) {
        DialogueCommandResponse response = new DialogueCommandResponse();
        response.setCommands(Collections.singletonList(command));
        return response;
    }

    private MessagePreviewResponse preview() {
        MessagePreviewResponse response = new MessagePreviewResponse();
        response.setOperationId("op-1"); response.setCustomerId("C001"); response.setCustomerName("张伟");
        response.setContent("张伟您好，产品近期到期。"); response.setStatus(OperationStatus.PENDING_CONFIRMATION);
        response.setSensitiveWords(Collections.emptyList()); response.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        response.setMock(true); return response;
    }

    private MessageSendResponse sent() {
        MessageSendResponse response = new MessageSendResponse();
        response.setOperationId("op-1"); response.setCustomerId("C001"); response.setCustomerName("张伟");
        response.setStatus(OperationStatus.SENT); response.setSentAt(OffsetDateTime.now());
        response.setChannel("MOCK"); response.setMock(true); return response;
    }

    private static class Fixture {
        final FlowEngine engine;
        final DialogueCommandService commandService;
        final DialogueOrchestrationService orchestrator;
        Fixture(FlowEngine engine, DialogueCommandService commandService, DialogueOrchestrationService orchestrator) {
            this.engine = engine; this.commandService = commandService; this.orchestrator = orchestrator;
        }
    }
}
