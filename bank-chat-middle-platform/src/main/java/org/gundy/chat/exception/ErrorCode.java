package org.gundy.chat.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求内容不完整或格式不正确，请检查后重试。", false),
    BUSINESS_STATE_CONFLICT(HttpStatus.CONFLICT, "当前操作状态已变化，请刷新后重试。", false),
    AI_SERVICE_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务响应较慢，请稍后重试。", true),
    AI_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务暂时不可用，请稍后重试。", true),
    AI_SERVICE_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "AI 服务返回异常，请稍后重试。", true),
    DATA_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "数据服务暂时不可用，请稍后重试。", true),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统暂时无法处理该请求，请稍后重试。", false);

    private final HttpStatus status;
    private final String message;
    private final boolean retryable;

    ErrorCode(HttpStatus status, String message, boolean retryable) {
        this.status = status;
        this.message = message;
        this.retryable = retryable;
    }

    public HttpStatus getStatus() { return status; }
    public String getMessage() { return message; }
    public boolean isRetryable() { return retryable; }
}
