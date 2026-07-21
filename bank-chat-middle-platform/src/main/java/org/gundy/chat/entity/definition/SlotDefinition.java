package org.gundy.chat.entity.definition;

import java.util.ArrayList;
import java.util.List;

public class SlotDefinition {
    private String id;
    private String type;
    private boolean required;
    private boolean sensitive;
    private boolean shareable;
    private String validationPolicy;
    private List<String> prompts = new ArrayList<String>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public boolean isSensitive() { return sensitive; }
    public void setSensitive(boolean sensitive) { this.sensitive = sensitive; }
    public boolean isShareable() { return shareable; }
    public void setShareable(boolean shareable) { this.shareable = shareable; }
    public String getValidationPolicy() { return validationPolicy; }
    public void setValidationPolicy(String validationPolicy) { this.validationPolicy = validationPolicy; }
    public List<String> getPrompts() { return prompts; }
    public void setPrompts(List<String> prompts) { this.prompts = prompts; }
}
