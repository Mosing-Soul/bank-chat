package org.gundy.chat.skill.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.Map;

@Data
public class MessagePreviewRequest {
    private String traceId;

    @NotBlank(message = "customerId must not be blank")
    private String customerId;

    @NotBlank(message = "templateCode must not be blank")
    private String templateCode;

    @NotNull(message = "variables must not be null")
    private Map<String, String> variables;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables; }
}
