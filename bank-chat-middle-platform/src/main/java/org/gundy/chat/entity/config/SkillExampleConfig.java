package org.gundy.chat.entity.config;

public class SkillExampleConfig {
    private String exampleId;
    private String skillCode;
    private String text;
    private String displayText;
    private String icon;
    private double confidence;
    private boolean showOnHome;
    private boolean quickAction;
    private boolean greeting;
    private boolean forceWhenClicked = true;
    private int sortOrder;

    public SkillExampleConfig() {
    }

    public SkillExampleConfig(String exampleId, String skillCode, String text, String displayText, String icon,
                              double confidence, boolean showOnHome, boolean quickAction, boolean greeting,
                              boolean forceWhenClicked, int sortOrder) {
        this.exampleId = exampleId;
        this.skillCode = skillCode;
        this.text = text;
        this.displayText = displayText;
        this.icon = icon;
        this.confidence = confidence;
        this.showOnHome = showOnHome;
        this.quickAction = quickAction;
        this.greeting = greeting;
        this.forceWhenClicked = forceWhenClicked;
        this.sortOrder = sortOrder;
    }

    public String getExampleId() { return exampleId; }
    public void setExampleId(String exampleId) { this.exampleId = exampleId; }
    public String getSkillCode() { return skillCode; }
    public void setSkillCode(String skillCode) { this.skillCode = skillCode; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getDisplayText() { return displayText; }
    public void setDisplayText(String displayText) { this.displayText = displayText; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public boolean isShowOnHome() { return showOnHome; }
    public void setShowOnHome(boolean showOnHome) { this.showOnHome = showOnHome; }
    public boolean isQuickAction() { return quickAction; }
    public void setQuickAction(boolean quickAction) { this.quickAction = quickAction; }
    public boolean isGreeting() { return greeting; }
    public void setGreeting(boolean greeting) { this.greeting = greeting; }
    public boolean isForceWhenClicked() { return forceWhenClicked; }
    public void setForceWhenClicked(boolean forceWhenClicked) { this.forceWhenClicked = forceWhenClicked; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
