package org.gundy.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.gundy.chat.flow.FlowEngine;
import org.gundy.chat.statemachine.SkillTransitionResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class FlowRecoveryServiceTest {
    @Test
    void sideEffectFlowRequiresExplicitResume() {
        Fixture fixture = fixture();
        DialogState state = fixture.engine.startFlow("s1", null, "MESSAGE_SEND");
        FlowInstance message = fixture.engine.activeFlow(state);
        fixture.engine.suspendFlow(state, message);

        SkillTransitionResult result = fixture.recovery.afterTerminal(terminal(state, "查询完成。"));

        assertThat(message.getStatus()).isEqualTo("SUSPENDED");
        assertThat(result.getAnswer()).contains("如需继续请明确告诉我");
    }

    @Test
    void readOnlyFlowMayResumeAutomatically() {
        Fixture fixture = fixture();
        DialogState state = fixture.engine.startFlow("s1", null, "CUSTOMER_AUM");
        FlowInstance aum = fixture.engine.activeFlow(state);
        fixture.engine.suspendFlow(state, aum);

        SkillTransitionResult result = fixture.recovery.afterTerminal(terminal(state, "行情查询完成。"));

        assertThat(aum.getStatus()).isEqualTo("ACTIVE");
        assertThat(state.getActiveSkill()).isEqualTo("CUSTOMER_AUM");
        assertThat(result.getAnswer()).contains("已返回之前暂停的客户资产查询");
    }

    private SkillTransitionResult terminal(DialogState state, String answer) {
        SkillTransitionResult result = new SkillTransitionResult();
        result.setHandled(true); result.setTerminal(true); result.setDialogState(state); result.setAnswer(answer);
        return result;
    }

    private Fixture fixture() {
        SkillDefinitionRegistry registry = new SkillDefinitionRegistry(new ObjectMapper(), new DefaultResourceLoader(),
                new SkillDefinitionValidator(), "classpath:config/skill-definitions.json");
        FlowEngine engine = new FlowEngine(registry, Collections.emptyList());
        return new Fixture(engine, new FlowRecoveryService(engine, registry, true));
    }

    private static class Fixture {
        final FlowEngine engine; final FlowRecoveryService recovery;
        Fixture(FlowEngine engine, FlowRecoveryService recovery) { this.engine = engine; this.recovery = recovery; }
    }
}
