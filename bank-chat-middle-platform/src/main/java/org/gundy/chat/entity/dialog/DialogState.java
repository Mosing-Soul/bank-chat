package org.gundy.chat.entity.dialog;

import java.util.LinkedHashMap;
import java.util.Map;

public class DialogState {
    private String version = "1.0";
    private String sessionId;
    private String status = "ACTIVE";
    private String mode = "SINGLE_SKILL";
    private String activeSkill;
    private String activeFlowId;
    private DialogIntent intent;
    private Map<String, SkillDialogState> skills = new LinkedHashMap<String, SkillDialogState>();
    private Map<String, Object> plan;
    private DialogUiHints ui;
    private String updatedAt;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getActiveSkill() { return activeSkill; }
    public void setActiveSkill(String activeSkill) { this.activeSkill = activeSkill; }
    public String getActiveFlowId() { return activeFlowId; }
    public void setActiveFlowId(String activeFlowId) { this.activeFlowId = activeFlowId; }
    public DialogIntent getIntent() { return intent; }
    public void setIntent(DialogIntent intent) { this.intent = intent; }
    public Map<String, SkillDialogState> getSkills() { return skills; }
    public void setSkills(Map<String, SkillDialogState> skills) { this.skills = skills; }
    public Map<String, Object> getPlan() { return plan; }
    public void setPlan(Map<String, Object> plan) { this.plan = plan; }
    public DialogUiHints getUi() { return ui; }
    public void setUi(DialogUiHints ui) { this.ui = ui; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
