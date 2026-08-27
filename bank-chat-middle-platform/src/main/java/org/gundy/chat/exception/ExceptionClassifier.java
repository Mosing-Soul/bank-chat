package org.gundy.chat.exception;

import org.springframework.dao.DataAccessException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

public final class ExceptionClassifier {
    private ExceptionClassifier() {}

    public static ErrorCode classify(Throwable throwable) {
        if (throwable instanceof ApplicationException) {
            return ((ApplicationException) throwable).getErrorCode();
        }
        if (throwable instanceof IllegalArgumentException) return ErrorCode.INVALID_REQUEST;
        if (throwable instanceof IllegalStateException) return ErrorCode.BUSINESS_STATE_CONFLICT;
        if (throwable instanceof DataAccessException) return ErrorCode.DATA_SERVICE_UNAVAILABLE;
        if (throwable instanceof ResourceAccessException) return ErrorCode.AI_SERVICE_UNAVAILABLE;
        if (throwable instanceof RestClientException) return ErrorCode.AI_SERVICE_INVALID_RESPONSE;
        return ErrorCode.INTERNAL_ERROR;
    }
}
