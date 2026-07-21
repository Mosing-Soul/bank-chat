package org.gundy.chat.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gundy.chat.entity.command.CommandDispatchResult;
import org.gundy.chat.entity.command.DialogCommand;
import org.gundy.chat.entity.command.DialogCommandType;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.gundy.chat.flow.CustomerAumFlowHandler;
import org.gundy.chat.flow.FlowEngine;
import org.gundy.chat.flow.FlowSkillHandler;
import org.gundy.chat.policy.ConversationPolicy;
import org.gundy.chat.service.SkillDefinitionRegistry;
import org.gundy.chat.service.SkillDefinitionValidator;
import org.gundy.chat.skill.service.CustomerSkillService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DialogCommandDispatcherTest {

    @Test
    void startsFlowAndAppliesDeclaredInitialSlots() {
        Fixture fixture = fixture();
        DialogCommand start = start("CUSTOMER_AUM");
        start.getSlots().put("customerReference", "张伟");

        CommandDispatchResult result = fixture.dispatcher.dispatch("s1", null, Collections.singletonList(start));

        assertThat(result.getOutcomes()).extracting("status").containsExactly("APPLIED");
        FlowInstance active = fixture.engine.activeFlow(result.getDialogState());
        assertThat(active.getSkillId()).isEqualTo("CUSTOMER_AUM");
        assertThat(active.getSlots()).containsEntry("customerReference", "张伟");
    }

    @Test
    void startingNewFlowSuspendsSuspendableCurrentFlow() {
        Fixture fixture = fixture();
        DialogState state = fixture.dispatcher.dispatch("s1", null,
                Collections.singletonList(start("CUSTOMER_AUM"))).getDialogState();
        FlowInstance customer = fixture.engine.activeFlow(state);

        CommandDispatchResult result = fixture.dispatcher.dispatch("s1", state,
                Collections.singletonList(start("GOLD_PRICE")));

        assertThat(customer.getStatus()).isEqualTo("SUSPENDED");
        assertThat(fixture.engine.activeFlow(result.getDialogState()).getSkillId()).isEqualTo("GOLD_PRICE");
        assertThat(result.getDialogState().getFlowStack()).hasSize(2);
    }

    @Test
    void resumingOldFlowCancelsReplaceableActiveFlow() {
        Fixture fixture = fixture();
        DialogState state = fixture.dispatcher.dispatch("s1", null,
                Collections.singletonList(start("CUSTOMER_AUM"))).getDialogState();
        FlowInstance customer = fixture.engine.activeFlow(state);
        state = fixture.dispatcher.dispatch("s1", state,
                Collections.singletonList(start("GOLD_PRICE"))).getDialogState();
        FlowInstance gold = fixture.engine.activeFlow(state);

        DialogCommand resume = command(DialogCommandType.RESUME_FLOW, "CUSTOMER_AUM");
        resume.setTargetFlowInstanceId(customer.getInstanceId());
        CommandDispatchResult result = fixture.dispatcher.dispatch("s1", state, Collections.singletonList(resume));

        assertThat(gold.getStatus()).isEqualTo("CANCELLED");
        assertThat(customer.getStatus()).isEqualTo("ACTIVE");
        assertThat(fixture.engine.activeFlow(result.getDialogState()).getInstanceId()).isEqualTo(customer.getInstanceId());
    }

    @Test
    void rejectsUnknownSlotWithoutSuspendingCurrentFlow() {
        Fixture fixture = fixture();
        DialogState state = fixture.dispatcher.dispatch("s1", null,
                Collections.singletonList(start("CUSTOMER_AUM"))).getDialogState();
        FlowInstance customer = fixture.engine.activeFlow(state);
        DialogCommand invalidStart = start("GOLD_PRICE");
        invalidStart.getSlots().put("notDeclared", "x");

        CommandDispatchResult result = fixture.dispatcher.dispatch("s1", state, Collections.singletonList(invalidStart));

        assertThat(result.getOutcomes()).extracting("status").containsExactly("REJECTED");
        assertThat(customer.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getDialogState().getFlowStack()).hasSize(1);
    }

    @Test
    void sideEffectExecutionCannotBeInterrupted() {
        Fixture fixture = fixture();
        DialogState state = fixture.dispatcher.dispatch("s1", null,
                Collections.singletonList(start("MESSAGE_SEND"))).getDialogState();
        FlowInstance message = fixture.engine.activeFlow(state);
        message.setCurrentStage("EXECUTING");

        CommandDispatchResult result = fixture.dispatcher.dispatch("s1", state,
                Collections.singletonList(start("CUSTOMER_AUM")));

        assertThat(result.getOutcomes()).extracting("status").containsExactly("REJECTED");
        assertThat(message.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getDialogState().getFlowStack()).hasSize(1);
    }

    @Test
    void lowConfidenceCommandRequestsClarificationWithoutMutation() {
        Fixture fixture = fixture();
        DialogCommand start = start("CUSTOMER_AUM");
        start.setConfidence(0.42D);

        CommandDispatchResult result = fixture.dispatcher.dispatch("s1", null, Collections.singletonList(start));

        assertThat(result.isClarificationRequired()).isTrue();
        assertThat(result.getDialogState()).isNull();
        assertThat(result.getOutcomes()).extracting("status").containsExactly("CLARIFICATION_REQUIRED");
    }

    @Test
    void explicitConfirmAdvancesOnlyTheWaitingFlow() {
        Fixture fixture = fixture();
        DialogState state = fixture.dispatcher.dispatch("s1", null,
                Collections.singletonList(start("MESSAGE_SEND"))).getDialogState();
        FlowInstance message = fixture.engine.activeFlow(state);
        message.setCurrentStage("WAITING_CONFIRMATION");

        CommandDispatchResult result = fixture.dispatcher.dispatch("s1", state,
                Collections.singletonList(command(DialogCommandType.CONFIRM, "MESSAGE_SEND")));

        assertThat(result.getOutcomes()).extracting("status").containsExactly("APPLIED");
        assertThat(message.getCurrentStage()).isEqualTo("EXECUTING");
        assertThat(message.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void safelyResolvesShareableCustomerReferenceAcrossFlows() {
        Fixture fixture = fixture();
        DialogState state = fixture.dispatcher.dispatch("s1", null,
                Collections.singletonList(start("MESSAGE_SEND"))).getDialogState();
        FlowInstance message = fixture.engine.activeFlow(state);
        fixture.engine.setSlot(state, message, "customerReference", "张伟");
        DialogCommand startAum = start("CUSTOMER_AUM");
        startAum.getSlots().put("customerReference",
                "flow-slot://" + message.getInstanceId() + "/customerReference");

        CommandDispatchResult result = fixture.dispatcher.dispatch("s1", state, Collections.singletonList(startAum));

        assertThat(result.getOutcomes()).extracting("status").containsExactly("APPLIED");
        assertThat(message.getStatus()).isEqualTo("SUSPENDED");
        assertThat(fixture.engine.activeFlow(result.getDialogState()).getSlots())
                .containsEntry("customerReference", "张伟");
    }

    @Test
    void rejectsNonShareableReferenceBeforeMutatingCurrentFlow() {
        Fixture fixture = fixture();
        DialogState state = fixture.dispatcher.dispatch("s1", null,
                Collections.singletonList(start("MESSAGE_SEND"))).getDialogState();
        FlowInstance message = fixture.engine.activeFlow(state);
        fixture.engine.setSlot(state, message, "messagePurpose", "到期提醒");
        DialogCommand startRag = start("RAG_QUERY");
        startRag.getSlots().put("question", "flow-slot://" + message.getInstanceId() + "/messagePurpose");

        CommandDispatchResult result = fixture.dispatcher.dispatch("s1", state, Collections.singletonList(startRag));

        assertThat(result.getOutcomes()).extracting("status").containsExactly("REJECTED");
        assertThat(message.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getDialogState().getFlowStack()).hasSize(1);
    }

    @Test
    void rejectsWholeCommandBatchWithoutPartialMutation() {
        Fixture fixture = fixture();
        DialogState state = fixture.dispatcher.dispatch("s1", null,
                Collections.singletonList(start("CUSTOMER_AUM"))).getDialogState();
        FlowInstance customer = fixture.engine.activeFlow(state);
        DialogCommand suspend = command(DialogCommandType.SUSPEND_FLOW, "CUSTOMER_AUM");
        DialogCommand invalid = command(DialogCommandType.SET_SLOT, "CUSTOMER_AUM");
        invalid.setSlot("notDeclared");
        invalid.setValue("x");

        CommandDispatchResult result = fixture.dispatcher.dispatch("s1", state, Arrays.asList(suspend, invalid));

        assertThat(result.getOutcomes()).extracting("status").containsExactly("APPLIED", "REJECTED");
        assertThat(customer.getStatus()).isEqualTo("ACTIVE");
        assertThat(state.getActiveFlowId()).isEqualTo(customer.getInstanceId());
    }

    private DialogCommand start(String skill) {
        return command(DialogCommandType.START_FLOW, skill);
    }

    private DialogCommand command(DialogCommandType type, String skill) {
        DialogCommand command = new DialogCommand();
        command.setType(type);
        command.setTargetSkill(skill);
        command.setConfidence(0.95D);
        return command;
    }

    private Fixture fixture() {
        SkillDefinitionRegistry registry = new SkillDefinitionRegistry(new ObjectMapper(),
                new DefaultResourceLoader(), new SkillDefinitionValidator(),
                "classpath:config/skill-definitions.json");
        FlowSkillHandler handler = new CustomerAumFlowHandler(mock(CustomerSkillService.class));
        FlowEngine engine = new FlowEngine(registry, Collections.singletonList(handler));
        ConversationPolicy policy = new ConversationPolicy(registry);
        return new Fixture(engine, new DialogCommandDispatcher(policy, engine, registry,
                new ObjectMapper().findAndRegisterModules()));
    }

    private static class Fixture {
        private final FlowEngine engine;
        private final DialogCommandDispatcher dispatcher;

        private Fixture(FlowEngine engine, DialogCommandDispatcher dispatcher) {
            this.engine = engine;
            this.dispatcher = dispatcher;
        }
    }
}
