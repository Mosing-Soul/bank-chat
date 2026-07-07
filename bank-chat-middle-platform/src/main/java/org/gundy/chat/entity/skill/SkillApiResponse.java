package org.gundy.chat.skill.dto;

import lombok.Data;

@Data
public class SkillApiResponse<T> {
    private boolean success;
    private String traceId;
    private T data;
    private SkillError error;

    public static <T> SkillApiResponse<T> success(String traceId, T data) {
        SkillApiResponse<T> response = new SkillApiResponse<T>();
        response.setSuccess(true);
        response.setTraceId(traceId);
        response.setData(data);
        return response;
    }

    public static <T> SkillApiResponse<T> error(String traceId, String code, String message) {
        SkillApiResponse<T> response = new SkillApiResponse<T>();
        response.setSuccess(false);
        response.setTraceId(traceId);
        response.setError(new SkillError(code, message));
        return response;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public SkillError getError() {
        return error;
    }

    public void setError(SkillError error) {
        this.error = error;
    }
}
