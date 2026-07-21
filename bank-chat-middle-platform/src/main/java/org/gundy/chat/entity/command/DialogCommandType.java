package org.gundy.chat.entity.command;

public enum DialogCommandType {
    START_FLOW,
    SUSPEND_FLOW,
    RESUME_FLOW,
    CANCEL_FLOW,
    SET_SLOT,
    CLEAR_SLOT,
    CONFIRM,
    REJECT,
    REQUEST_CLARIFICATION,
    NO_OP
}
