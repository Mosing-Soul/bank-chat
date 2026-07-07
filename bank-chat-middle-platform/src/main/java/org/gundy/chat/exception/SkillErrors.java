package org.gundy.chat.skill.exception;

import org.springframework.http.HttpStatus;

public final class SkillErrors {
    private SkillErrors() {
    }

    public static SkillBusinessException customerNotFound(String customerId) {
        return new SkillBusinessException("CUSTOMER_NOT_FOUND",
                "Customer does not exist: " + customerId, HttpStatus.NOT_FOUND);
    }

    public static SkillBusinessException templateNotFound(String templateCode) {
        return new SkillBusinessException("MESSAGE_TEMPLATE_NOT_FOUND",
                "Message template does not exist: " + templateCode, HttpStatus.BAD_REQUEST);
    }

    public static SkillBusinessException missingTemplateVariable(String variable) {
        return new SkillBusinessException("MISSING_TEMPLATE_VARIABLE",
                "Missing required template variable: " + variable, HttpStatus.BAD_REQUEST);
    }

    public static SkillBusinessException operationNotFound(String operationId) {
        return new SkillBusinessException("OPERATION_NOT_FOUND",
                "Message operation does not exist: " + operationId, HttpStatus.NOT_FOUND);
    }

    public static SkillBusinessException operationExpired(String operationId) {
        return new SkillBusinessException("OPERATION_EXPIRED",
                "Message operation has expired: " + operationId, HttpStatus.GONE);
    }

    public static SkillBusinessException confirmationRequired() {
        return new SkillBusinessException("CONFIRMATION_REQUIRED",
                "confirmed must be true before sending", HttpStatus.BAD_REQUEST);
    }

    public static SkillBusinessException duplicateSend(String operationId) {
        return new SkillBusinessException("DUPLICATE_SEND",
                "Message operation has already been sent: " + operationId, HttpStatus.CONFLICT);
    }

    public static SkillBusinessException sensitiveWordsNeedReview() {
        return new SkillBusinessException("SENSITIVE_WORDS_NEED_REVIEW",
                "Message contains sensitive words and cannot be mock-sent without review", HttpStatus.CONFLICT);
    }
}
