package org.gundy.chat.statemachine;

import org.gundy.chat.entity.dialog.DialogState;

public interface SkillStateMachine {
    String skillName();

    boolean supports(DialogState state, String userMessage);

    SkillTransitionResult handle(String traceId, String sessionId, DialogState state, String userMessage);
}
