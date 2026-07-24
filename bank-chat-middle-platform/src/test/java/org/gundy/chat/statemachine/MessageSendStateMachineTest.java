package org.gundy.chat.statemachine;

import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.flow.FlowEngine;
import org.gundy.chat.flow.FlowSkillHandler;
import org.gundy.chat.flow.MessageSendFlowHandler;
import org.gundy.chat.service.SkillDefinitionRegistry;
import org.gundy.chat.service.SkillDefinitionValidator;
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
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class MessageSendStateMachineTest {

    @Test
    void completesMessageSendFlowWithConfirmation() {
        CustomerSkillService customerSkillService = mock(CustomerSkillService.class);
        MessageSkillService messageSkillService = mock(MessageSkillService.class);
        MessageSendStateMachine stateMachine = machine(customerSkillService, messageSkillService);

        when(customerSkillService.searchCustomers("\u5f20\u4f1f")).thenReturn(Collections.singletonList(
                new CustomerSummaryResponse("C001", "\u5f20\u4f1f", CustomerLevel.PRIVATE_BANKING, RiskLevel.C3_BALANCED, true)
        ));
        when(messageSkillService.preview(any(MessagePreviewRequest.class))).thenReturn(preview());
        when(messageSkillService.send(any(MessageSendRequest.class))).thenReturn(sent());

        SkillTransitionResult previewResult = stateMachine.handle("trace-1", "session-1", null,
                "给张伟生成产品到期提醒");

        assertThat(previewResult.isHandled()).isTrue();
        assertThat(previewResult.isRequiresConfirmation()).isTrue();
        assertThat(previewResult.isTerminal()).isFalse();
        assertThat(previewResult.getDialogState().getActiveSkill()).isEqualTo(MessageSendStateMachine.SKILL);
        assertThat(previewResult.getConfirmation()).containsEntry("operationId", "op-001");

        SkillTransitionResult sendResult = stateMachine.handle("trace-2", "session-1",
                previewResult.getDialogState(), "\u786e\u8ba4\u53d1\u9001");

        assertThat(sendResult.isHandled()).isTrue();
        assertThat(sendResult.isTerminal()).isTrue();
        assertThat(sendResult.getDialogState().getActiveSkill()).isNull();
        assertThat(sendResult.getData()).containsKey("messageSend");
        verify(customerSkillService).searchCustomers(eq("\u5f20\u4f1f"));
        verify(messageSkillService).preview(any(MessagePreviewRequest.class));
        verify(messageSkillService).send(any(MessageSendRequest.class));
    }

    @Test
    void fillsPurposeAfterCustomerOnlyMessageSendRequest() {
        CustomerSkillService customerSkillService = mock(CustomerSkillService.class);
        MessageSkillService messageSkillService = mock(MessageSkillService.class);
        MessageSendStateMachine stateMachine = machine(customerSkillService, messageSkillService);

        when(customerSkillService.searchCustomers("\u5f20\u4f1f")).thenReturn(Collections.singletonList(
                new CustomerSummaryResponse("C001", "\u5f20\u4f1f", CustomerLevel.PRIVATE_BANKING, RiskLevel.C3_BALANCED, true)
        ));
        when(messageSkillService.preview(any(MessagePreviewRequest.class))).thenReturn(preview());

        SkillTransitionResult askPurposeResult = stateMachine.handle("trace-1", "session-1", null,
                "\u7ed9\u5f20\u4f1f\u53d1\u6d88\u606f");

        assertThat(askPurposeResult.isHandled()).isTrue();
        assertThat(askPurposeResult.isRequiresConfirmation()).isFalse();
        assertThat(askPurposeResult.getDialogState().getActiveSkill()).isEqualTo(MessageSendStateMachine.SKILL);
        assertThat(askPurposeResult.getDialogState().getSkills().get(MessageSendStateMachine.SKILL).getSlots())
                .containsEntry("customerReference", "\u5f20\u4f1f");

        SkillTransitionResult previewResult = stateMachine.handle("trace-2", "session-1",
                askPurposeResult.getDialogState(), "\u4ea7\u54c1\u5230\u671f\u63d0\u9192");

        assertThat(previewResult.isHandled()).isTrue();
        assertThat(previewResult.isRequiresConfirmation()).isTrue();
        assertThat(previewResult.getConfirmation()).containsEntry("customerName", "\u5f20\u4f1f");
        verify(customerSkillService).searchCustomers(eq("\u5f20\u4f1f"));
        verify(messageSkillService).preview(any(MessagePreviewRequest.class));
    }

    @Test
    void revisionCreatesANewPreviewWithoutSending() {
        CustomerSkillService customerSkillService = mock(CustomerSkillService.class);
        MessageSkillService messageSkillService = mock(MessageSkillService.class);
        MessageSendStateMachine stateMachine = machine(customerSkillService, messageSkillService);
        when(customerSkillService.searchCustomers("张伟")).thenReturn(Collections.singletonList(
                new CustomerSummaryResponse("C001", "张伟", CustomerLevel.PRIVATE_BANKING, RiskLevel.C3_BALANCED, true)));
        when(messageSkillService.preview(any(MessagePreviewRequest.class))).thenReturn(preview());

        SkillTransitionResult first = stateMachine.handle("trace-1", "session-1", null,
                "给张伟发送产品到期提醒");
        SkillTransitionResult revised = stateMachine.handle("trace-2", "session-1", first.getDialogState(),
                "修改为请尽快联系客户经理");

        assertThat(revised.isRequiresConfirmation()).isTrue();
        assertThat(revised.isTerminal()).isFalse();
        assertThat(revised.getDialogState().getFlowStack().get(0).getSlots())
                .containsEntry("messagePurpose", "请尽快联系客户经理");
        verify(messageSkillService, times(2)).preview(any(MessagePreviewRequest.class));
        verify(messageSkillService, never()).send(any(MessageSendRequest.class));
    }

    private MessagePreviewResponse preview() {
        MessagePreviewResponse response = new MessagePreviewResponse();
        response.setOperationId("op-001");
        response.setOperationType("MESSAGE_SEND");
        response.setCustomerId("C001");
        response.setCustomerName("\u5f20\u4f1f");
        response.setContent("\u5f20\u4f1f\u60a8\u597d\uff0c\u60a8\u7684\u4ea7\u54c1\u8fd1\u671f\u5230\u671f\u3002");
        response.setSensitiveWords(Collections.emptyList());
        response.setStatus(OperationStatus.PENDING_CONFIRMATION);
        response.setExpiresAt(OffsetDateTime.now(ZoneOffset.ofHours(8)).plusMinutes(10));
        response.setMock(true);
        return response;
    }

    private MessageSendStateMachine machine(CustomerSkillService customerService, MessageSkillService messageService) {
        SkillDefinitionRegistry registry = new SkillDefinitionRegistry(new ObjectMapper(),
                new DefaultResourceLoader(), new SkillDefinitionValidator(),
                "classpath:config/skill-definitions.json");
        FlowSkillHandler handler = new MessageSendFlowHandler(customerService, messageService);
        FlowEngine engine = new FlowEngine(registry, Collections.singletonList(handler));
        return new MessageSendStateMachine(engine);
    }

    private MessageSendResponse sent() {
        MessageSendResponse response = new MessageSendResponse();
        response.setOperationId("op-001");
        response.setCustomerId("C001");
        response.setCustomerName("\u5f20\u4f1f");
        response.setStatus(OperationStatus.SENT);
        response.setSentAt(OffsetDateTime.now(ZoneOffset.ofHours(8)));
        response.setChannel("MOCK_ENTERPRISE_WECHAT");
        response.setDataSource("MOCK_MESSAGE_GATEWAY_NO_REAL_SEND");
        response.setMock(true);
        return response;
    }
}
