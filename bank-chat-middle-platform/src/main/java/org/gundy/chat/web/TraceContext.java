package org.gundy.chat.web;

import org.slf4j.MDC;

import java.util.UUID;

public final class TraceContext {
    public static final String MDC_KEY = "traceId";

    private TraceContext() {}

    public static String currentOrCreate(String requestedTraceId) {
        if (hasText(requestedTraceId)) return requestedTraceId.trim();
        String current = MDC.get(MDC_KEY);
        return hasText(current) ? current : UUID.randomUUID().toString();
    }

    private static boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
