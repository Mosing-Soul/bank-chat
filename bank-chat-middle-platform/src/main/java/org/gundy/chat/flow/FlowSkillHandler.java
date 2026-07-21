package org.gundy.chat.flow;

import java.util.Map;

public interface FlowSkillHandler {
    String skillId();

    Map<String, Object> extractSlots(FlowContext context, String userMessage);

    FlowValidationResult validate(FlowContext context);

    FlowExecutionResult execute(FlowContext context);

    default FlowConfirmationResult prepareConfirmation(FlowContext context) {
        throw new IllegalStateException("Confirmation is not supported by skill " + skillId());
    }

    default boolean isConfirmationRevision(FlowContext context, String userMessage) { return false; }
}
