package org.gundy.chat.skill.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

@Component
public class InternalSkillInterceptor implements HandlerInterceptor {
    public static final String TRACE_ID_ATTRIBUTE = "skillTraceId";

    private final String apiKey;
    private final String apiKeyHeader;

    public InternalSkillInterceptor(@Value("${bank.skills.internal.api-key}") String apiKey,
                                    @Value("${bank.skills.internal.api-key-header:X-Internal-Api-Key}") String apiKeyHeader) {
        this.apiKey = apiKey;
        this.apiKeyHeader = apiKeyHeader;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.trim().length() == 0) {
            traceId = UUID.randomUUID().toString();
        }
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        MDC.put("traceId", traceId);

        String providedKey = request.getHeader(apiKeyHeader);
        if (!constantTimeEquals(apiKey, providedKey)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"traceId\":\"" + traceId
                    + "\",\"error\":{\"code\":\"INTERNAL_API_UNAUTHORIZED\",\"message\":\"Invalid internal API key\"}}");
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        MDC.remove("traceId");
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        int result = expected.length() ^ actual.length();
        int max = Math.max(expected.length(), actual.length());
        for (int i = 0; i < max; i++) {
            char left = i < expected.length() ? expected.charAt(i) : 0;
            char right = i < actual.length() ? actual.charAt(i) : 0;
            result |= left ^ right;
        }
        return result == 0;
    }
}
