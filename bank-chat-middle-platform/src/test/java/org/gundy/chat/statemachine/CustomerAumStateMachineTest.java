package org.gundy.chat.statemachine;

import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.skill.dto.CustomerAumResponse;
import org.gundy.chat.skill.dto.CustomerSummaryResponse;
import org.gundy.chat.skill.enums.CustomerLevel;
import org.gundy.chat.skill.enums.RiskLevel;
import org.gundy.chat.skill.service.CustomerSkillService;
import org.junit.jupiter.api.Test;

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
        CustomerAumStateMachine machine = new CustomerAumStateMachine(service);
        when(service.searchCustomers("张伟")).thenReturn(Collections.singletonList(customer("C001", "张伟")));
        when(service.getAum("C001")).thenReturn(aum("C001", "张伟"));
        SkillTransitionResult ask = machine.handle("t1", "s1", null, "帮我查一下客户等级");
        assertThat(ask.getDialogState().getActiveSkill()).isEqualTo(CustomerAumStateMachine.SKILL);
        assertThat(ask.getDialogState().getSkills().get(CustomerAumStateMachine.SKILL).getRequiredSlots())
                .containsExactly("customerNameOrId");
        SkillTransitionResult result = machine.handle("t2", "s1", ask.getDialogState(), "张伟");
        assertThat(result.isTerminal()).isTrue();
        assertThat(result.getAnswer()).contains("张伟").contains("AUM");
        verify(service).searchCustomers("张伟");
        verify(service).getAum("C001");
    }

    @Test
    void ambiguousOriginalQuestionDoesNotTreatBusinessTermAsCustomerName() {
        CustomerSkillService service = mock(CustomerSkillService.class);
        CustomerAumStateMachine machine = new CustomerAumStateMachine(service);

        SkillTransitionResult ask = machine.handle("t1", "s1", null, "帮我查一下客户等级");

        assertThat(ask.isTerminal()).isFalse();
        assertThat(ask.getAnswer()).contains("客户姓名或客户编号");
        assertThat(ask.getDialogState().getSkills().get(CustomerAumStateMachine.SKILL).getSlots())
                .doesNotContainKey("customerName");
    }

    @Test
    void irrelevantReplyStaysInSlotCollection() {
        CustomerAumStateMachine machine = new CustomerAumStateMachine(mock(CustomerSkillService.class));
        SkillTransitionResult ask = machine.handle("t1", "s1", null, "客户资产查询");
        SkillTransitionResult retry = machine.handle("t2", "s1", ask.getDialogState(), "我说我是谁，你说我是谁");
        assertThat(retry.isHandled()).isTrue();
        assertThat(retry.isTerminal()).isFalse();
        assertThat(retry.getDialogState().getActiveSkill()).isEqualTo(CustomerAumStateMachine.SKILL);
        assertThat(retry.getAnswer()).contains("客户姓名或编号").doesNotContain("办理方向");
    }

    @Test
    void naturalSelfIntroductionExtractsCustomerName() {
        CustomerSkillService service = mock(CustomerSkillService.class);
        CustomerAumStateMachine machine = new CustomerAumStateMachine(service);
        when(service.searchCustomers("张伟")).thenReturn(Collections.singletonList(customer("C001", "张伟")));
        when(service.getAum("C001")).thenReturn(aum("C001", "张伟"));
        DialogState state = machine.handle("t1", "s1", null, "客户资产查询").getDialogState();
        assertThat(machine.handle("t2", "s1", state, "我叫张伟，弓长张").isTerminal()).isTrue();
        verify(service).searchCustomers("张伟");
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
