package org.gundy.chat.skill.exception;

import org.gundy.chat.skill.config.InternalSkillInterceptor;
import org.gundy.chat.skill.dto.SkillApiResponse;
import org.gundy.chat.controller.SkillController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import javax.validation.ConstraintViolationException;

@RestControllerAdvice(assignableTypes = SkillController.class)
public class InternalSkillExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(InternalSkillExceptionHandler.class);

    @ExceptionHandler(SkillBusinessException.class)
    public ResponseEntity<SkillApiResponse<Object>> handleBusiness(SkillBusinessException ex,
                                                                    HttpServletRequest request) {
        log.warn("skillPath={}, status={}, errorCode={}", request.getRequestURI(), ex.getHttpStatus().value(), ex.getCode());
        return ResponseEntity.status(ex.getHttpStatus())
                .body(SkillApiResponse.<Object>error(traceId(request), ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<SkillApiResponse<Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                     HttpServletRequest request) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? "Invalid request" : fieldError.getDefaultMessage();
        log.warn("skillPath={}, status={}, errorCode={}", request.getRequestURI(), HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(SkillApiResponse.<Object>error(traceId(request), "VALIDATION_FAILED", message));
    }

    @ExceptionHandler({ConstraintViolationException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<SkillApiResponse<Object>> handleBadRequest(Exception ex, HttpServletRequest request) {
        log.warn("skillPath={}, status={}, errorCode={}", request.getRequestURI(), HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(SkillApiResponse.<Object>error(traceId(request), "VALIDATION_FAILED", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<SkillApiResponse<Object>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("skillPath={}, status={}, errorCode={}", request.getRequestURI(), HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL_SKILL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(SkillApiResponse.<Object>error(traceId(request), "INTERNAL_SKILL_ERROR", "Internal skill error"));
    }

    private String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(InternalSkillInterceptor.TRACE_ID_ATTRIBUTE);
        return traceId == null ? "" : String.valueOf(traceId);
    }
}
