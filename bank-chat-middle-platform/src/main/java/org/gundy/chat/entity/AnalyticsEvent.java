package org.gundy.chat.entity;

import lombok.Data;

@Data
public class AnalyticsEvent {
    private String eventType;
    private String status;
    private String sessionId;
    private String clientId;
    private boolean internalVisitor;
    private String traceId;
    private String ip;
    private String userAgent;
    private String intent;
    private String question;
    private String answer;
    private String errorMessage;
    private Long durationMs;
    private String createdAt;
}
