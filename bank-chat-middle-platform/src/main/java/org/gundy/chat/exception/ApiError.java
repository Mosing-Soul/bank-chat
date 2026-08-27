package org.gundy.chat.exception;

public class ApiError {
    private final String code;
    private final String message;
    private final String traceId;
    private final boolean retryable;

    public ApiError(ErrorCode errorCode, String traceId) {
        this.code = errorCode.name();
        this.message = errorCode.getMessage();
        this.traceId = traceId;
        this.retryable = errorCode.isRetryable();
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getTraceId() { return traceId; }
    public boolean isRetryable() { return retryable; }
}
