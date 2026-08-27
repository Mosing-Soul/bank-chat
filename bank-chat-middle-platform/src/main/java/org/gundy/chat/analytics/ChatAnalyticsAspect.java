package org.gundy.chat.analytics;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.gundy.chat.entity.ChatRequest;
import org.gundy.chat.entity.ChatResponse;
import org.gundy.chat.exception.ErrorCode;
import org.gundy.chat.exception.ExceptionClassifier;
import org.gundy.chat.service.AnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

/**
 * 埋点切面：在 ChatController.chat() 出口记录 CHAT 事件，与业务代码完全解耦。
 * 同步 /api/chat 与 SSE /api/chat/stream（内部委托 chat()）共用该切点。
 * 异常分类复用线上 ExceptionClassifier / ErrorCode 体系：
 * - 正常返回但带 error（SSE failure 等）：status 取错误码；
 * - 抛异常：status 为 ExceptionClassifier 分类出的 ErrorCode 名称；
 * - INVALID_REQUEST（客户端非法输入）与线上异常体系一致，不计入埋点。
 */
@Slf4j
@Aspect
@Component
public class ChatAnalyticsAspect {

    private final AnalyticsService analyticsService;

    public ChatAnalyticsAspect(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @Around("execution(* org.gundy.chat.controller.ChatController.chat(..))")
    public Object recordChat(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            recordCompletion(joinPoint, result, start);
            return result;
        } catch (Throwable ex) {
            recordFailure(joinPoint, ex, start);
            throw ex;
        }
    }

    private void recordCompletion(ProceedingJoinPoint joinPoint, Object result, long start) {
        try {
            ChatResponse body = result instanceof ResponseEntity
                    ? (ChatResponse) ((ResponseEntity<?>) result).getBody() : null;
            String status = body != null && body.getError() != null && body.getError().get("code") != null
                    ? String.valueOf(body.getError().get("code")) : "SUCCESS";
            analyticsService.recordChat(requestOf(joinPoint), traceIdOf(joinPoint, body), sessionIdOf(body),
                    headerOf(joinPoint, "X-Client-Id"), headerOf(joinPoint, "X-Internal-Visitor"),
                    questionOf(joinPoint), body, status, start, null);
        } catch (Exception ex) {
            log.warn("Analytics record failed: {}", ex.toString());
        }
    }

    private void recordFailure(ProceedingJoinPoint joinPoint, Throwable failure, long start) {
        try {
            ErrorCode errorCode = ExceptionClassifier.classify(failure);
            if (errorCode == ErrorCode.INVALID_REQUEST) {
                return;
            }
            analyticsService.recordChat(requestOf(joinPoint), traceIdOf(joinPoint, null), sessionIdOf(null),
                    headerOf(joinPoint, "X-Client-Id"), headerOf(joinPoint, "X-Internal-Visitor"),
                    questionOf(joinPoint), null, errorCode.name(), start, failure);
        } catch (Exception ex) {
            log.warn("Analytics record failed: {}", ex.toString());
        }
    }

    private HttpServletRequest requestOf(ProceedingJoinPoint joinPoint) {
        for (Object arg : joinPoint.getArgs()) {
            if (arg instanceof HttpServletRequest) {
                return (HttpServletRequest) arg;
            }
        }
        return null;
    }

    private String headerOf(ProceedingJoinPoint joinPoint, String name) {
        HttpServletRequest request = requestOf(joinPoint);
        return request == null ? null : request.getHeader(name);
    }

    private String questionOf(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        return args.length > 0 && args[0] instanceof ChatRequest
                ? ((ChatRequest) args[0]).effectiveMessage() : null;
    }

    private String traceIdOf(ProceedingJoinPoint joinPoint, ChatResponse body) {
        if (body != null && body.getTraceId() != null) {
            return body.getTraceId();
        }
        Object[] args = joinPoint.getArgs();
        return args.length > 1 && args[1] instanceof String ? (String) args[1] : null;
    }

    private String sessionIdOf(ChatResponse body) {
        return body == null ? null : body.getSessionId();
    }
}
