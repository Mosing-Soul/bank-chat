package org.gundy.chat.statemachine;

import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.gundy.chat.flow.CustomerAumFlowHandler;
import org.gundy.chat.flow.FlowEngine;
import org.gundy.chat.flow.FlowSkillHandler;
import org.gundy.chat.service.SkillDefinitionRegistry;
import org.gundy.chat.service.SkillDefinitionValidator;
import org.gundy.chat.skill.dto.CustomerAumResponse;
import org.gundy.chat.skill.dto.CustomerSummaryResponse;
import org.gundy.chat.skill.enums.CustomerLevel;
import org.gundy.chat.skill.enums.RiskLevel;
import org.gundy.chat.skill.service.CustomerSkillService;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerAumStateMachineTest {
    @Test
    void selectedIntentPersistsAndBareNameCompletesQuery() {
        CustomerSkillService service = mock(CustomerSkillService.class);
        CustomerAumStateMachine machine = machine(service);
        when(service.searchCustomers("张伟")).thenReturn(Collections.singletonList(customer("C001", "张伟")));
        when(service.getAum("C001")).thenReturn(aum("C001", "张伟"));
        SkillTransitionResult ask = machine.handle("t1", "s1", null, "帮我查一下客户等级");
        assertThat(ask.getDialogState().getActiveSkill()).isEqualTo(CustomerAumStateMachine.SKILL);
        assertThat(ask.getDialogState().getSkills().get(CustomerAumStateMachine.SKILL).getRequiredSlots())
                .containsExactly("customerReference");
        assertThat(ask.getDialogState().getFlowStack()).hasSize(1);
        FlowInstance activeFlow = ask.getDialogState().getFlowStack().get(0);
        assertThat(activeFlow.getFlowId()).isEqualTo("CUSTOMER_AUM_FLOW");
        assertThat(activeFlow.getCurrentStage()).isEqualTo("COLLECTING_SLOTS");
        SkillTransitionResult result = machine.handle("t2", "s1", ask.getDialogState(), "张伟");
        assertThat(result.isTerminal()).isTrue();
        assertThat(result.getAnswer()).contains("张伟").contains("AUM");
        verify(service).searchCustomers("张伟");
        verify(service).getAum("C001");
    }

    @Test
    void ambiguousOriginalQuestionDoesNotTreatBusinessTermAsCustomerName() {
        CustomerSkillService service = mock(CustomerSkillService.class);
        CustomerAumStateMachine machine = machine(service);

        SkillTransitionResult ask = machine.handle("t1", "s1", null, "帮我查一下客户等级");

        assertThat(ask.isTerminal()).isFalse();
        assertThat(ask.getAnswer()).contains("客户姓名或客户编号");
        assertThat(ask.getDialogState().getFlowStack().get(0).getSlots())
                .doesNotContainKey("customerReference");
    }

    @Test
    void irrelevantReplyStaysInSlotCollection() {
        CustomerAumStateMachine machine = machine(mock(CustomerSkillService.class));
        SkillTransitionResult ask = machine.handle("t1", "s1", null, "客户资产查询");
        SkillTransitionResult retry = machine.handle("t2", "s1", ask.getDialogState(), "我说我是谁，你说我是谁");
        assertThat(retry.isHandled()).isTrue();
        assertThat(retry.isTerminal()).isFalse();
        assertThat(retry.getDialogState().getActiveSkill()).isEqualTo(CustomerAumStateMachine.SKILL);
        assertThat(retry.getAnswer()).contains("客户姓名或客户编号").doesNotContain("办理方向");
    }

    @Test
    void naturalSelfIntroductionExtractsCustomerName() {
        CustomerSkillService service = mock(CustomerSkillService.class);
        CustomerAumStateMachine machine = machine(service);
        when(service.searchCustomers("张伟")).thenReturn(Collections.singletonList(customer("C001", "张伟")));
        when(service.getAum("C001")).thenReturn(aum("C001", "张伟"));
        DialogState state = machine.handle("t1", "s1", null, "客户资产查询").getDialogState();
        assertThat(machine.handle("t2", "s1", state, "我叫张伟，弓长张").isTerminal()).isTrue();
        verify(service).searchCustomers("张伟");
    }

    @Test
    void directMetricQuestionCompletesWithinOneFlowTurn() {
        CustomerSkillService service = mock(CustomerSkillService.class);
        when(service.searchCustomers("张伟")).thenReturn(Collections.singletonList(customer("C001", "张伟")));
        when(service.getAum("C001")).thenReturn(aum("C001", "张伟"));

        SkillTransitionResult result = machine(service).handle("t1", "s1", null, "查询张伟的AUM");

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.getDialogState().getFlowStack().get(0).getCurrentStage()).isEqualTo("COMPLETED");
        verify(service).searchCustomers("张伟");
        verify(service).getAum("C001");
    }

    @Test
    void cancelMovesRuntimeFlowToTerminalStage() {
        CustomerAumStateMachine machine = machine(mock(CustomerSkillService.class));
        DialogState state = machine.handle("t1", "s1", null, "客户资产查询").getDialogState();

        SkillTransitionResult cancelled = machine.handle("t2", "s1", state, "取消");

        assertThat(cancelled.isTerminal()).isTrue();
        assertThat(cancelled.getDialogState().getActiveSkill()).isNull();
        assertThat(cancelled.getDialogState().getFlowStack().get(0).getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getDialogState().getFlowStack().get(0).getCurrentStage()).isEqualTo("CANCELLED");
    }

    @Test
    void invalidCustomerReturnsToConfiguredCollectionStage() {
        CustomerSkillService service = mock(CustomerSkillService.class);
        when(service.searchCustomers("赵六")).thenReturn(Collections.<CustomerSummaryResponse>emptyList());
        CustomerAumStateMachine machine = machine(service);
        DialogState state = machine.handle("t1", "s1", null, "客户资产查询").getDialogState();

        SkillTransitionResult retry = machine.handle("t2", "s1", state, "赵六");

        assertThat(retry.isTerminal()).isFalse();
        assertThat(retry.getAnswer()).contains("未找到客户");
        assertThat(retry.getDialogState().getFlowStack().get(0).getCurrentStage()).isEqualTo("COLLECTING_SLOTS");
        assertThat(retry.getDialogState().getFlowStack().get(0).getSlots()).doesNotContainKey("customerReference");
    }

    private CustomerAumStateMachine machine(CustomerSkillService customerSkillService) {
        SkillDefinitionRegistry registry = new SkillDefinitionRegistry(new ObjectMapper(),
                new DefaultResourceLoader(), new SkillDefinitionValidator(),
                "classpath:config/skill-definitions.json");
        FlowSkillHandler handler = new CustomerAumFlowHandler(customerSkillService);
        FlowEngine engine = new FlowEngine(registry, Collections.singletonList(handler));
        return new CustomerAumStateMachine(engine);
    }

    private CustomerSummaryResponse customer(String id, String name) {
        return new CustomerSummaryResponse(id, name, CustomerLevel.PRIVATE_BANKING, RiskLevel.C3_BALANCED, true);
    }

    private CustomerAumResponse aum(String id, String name) {
        CustomerAumResponse response = new CustomerAumResponse();
        response.setCustomerId(id);
        response.setCustomerName(name);
        response.setTotalAum(new BigDecimal("1000000"));
        response.setCurrency("CNY");
        response.setMock(true);
        return response;
    }
}
