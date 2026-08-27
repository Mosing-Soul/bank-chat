package org.gundy.chat.exception;

import lombok.extern.slf4j.Slf4j;
import org.gundy.chat.web.TraceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> handleInvalidRequest(Exception exception) {
        return response(ErrorCode.INVALID_REQUEST, exception);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handle(Exception exception) {
        return response(ExceptionClassifier.classify(exception), exception);
    }

    private ResponseEntity<ApiError> response(ErrorCode errorCode, Exception exception) {
        String traceId = TraceContext.currentOrCreate(null);
        if (errorCode.getStatus().is5xxServerError()) {
            log.error("request failed, code={}", errorCode.name(), exception);
        } else {
            log.warn("request rejected, code={}, exception={}", errorCode.name(), exception.getClass().getSimpleName());
        }
        return ResponseEntity.status(errorCode.getStatus()).body(new ApiError(errorCode, traceId));
    }
}
