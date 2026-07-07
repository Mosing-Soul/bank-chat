package org.gundy.chat.service;

import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.statemachine.SkillStateMachine;
import org.gundy.chat.statemachine.SkillTransitionResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DialogStateMachineServiceTest {

    @Test
    void forceRequestedNonJavaSkillClearsActiveStateAndPassesThrough() {
        SkillStateMachine messageStateMachine = mock(SkillStateMachine.class);
        when(messageStateMachine.skillName()).thenReturn("MESSAGE_SEND");
        DialogStateMachineService service = new DialogStateMachineService(Collections.singletonList(messageStateMachine));

        DialogState state = activeMessageState();
        SkillTransitionResult result = service.handle("trace-1", "session-1", state,
                "\u63d0\u524d\u8d4e\u56de\u89c4\u5219", "RAG_QUERY", true);

        assertThat(result.isHandled()).isFalse();
        assertThat(result.isClearState()).isTrue();
    }

    @Test
    void normalInputKeepsActiveStateWhenThereIsNoExplicitSwitch() {
        SkillStateMachine messageStateMachine = mock(SkillStateMachine.class);
        when(messageStateMachine.skillName()).thenReturn("MESSAGE_SEND");
        SkillTransitionResult handled = new SkillTransitionResult();
        handled.setHandled(true);
        when(messageStateMachine.handle(eq("trace-1"), eq("session-1"), any(DialogState.class),
                eq("\u73b0\u5728\u9ec4\u91d1\u4ef7\u683c\u600e\u4e48\u6837\u4e86\uff0c\u4f60\u60f3\u770b\u770b\u5417")))
                .thenReturn(handled);
        DialogStateMachineService service = new DialogStateMachineService(Collections.singletonList(messageStateMachine));

        DialogState state = activeMessageState();
        SkillTransitionResult result = service.handle("trace-1", "session-1", state,
                "\u73b0\u5728\u9ec4\u91d1\u4ef7\u683c\u600e\u4e48\u6837\u4e86\uff0c\u4f60\u60f3\u770b\u770b\u5417", null, false);

        assertThat(result.isHandled()).isTrue();
        verify(messageStateMachine).handle(eq("trace-1"), eq("session-1"), eq(state),
                eq("\u73b0\u5728\u9ec4\u91d1\u4ef7\u683c\u600e\u4e48\u6837\u4e86\uff0c\u4f60\u60f3\u770b\u770b\u5417"));
    }

    private DialogState activeMessageState() {
        DialogState state = new DialogState();
        state.setSessionId("session-1");
        state.setActiveSkill("MESSAGE_SEND");
        return state;
    }
}
