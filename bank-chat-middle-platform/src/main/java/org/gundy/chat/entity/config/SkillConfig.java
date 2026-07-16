package org.gundy.chat.entity.config;

import java.util.ArrayList;
import java.util.List;

public class SkillConfig {
    private String skillCode;
    private String skillName;
    private String description;
    private boolean enabled = true;
    private boolean frontendVisible = true;
    private boolean forceWhenClicked = true;
    private int fallbackPriority;
    private String clarificationText;
    private List<SkillExampleConfig> examples = new ArrayList<SkillExampleConfig>();

    public SkillConfig() {
    }

    public SkillConfig(String skillCode, String skillName, String description, boolean enabled,
                       boolean frontendVisible, boolean forceWhenClicked, int fallbackPriority,
                       String clarificationText) {
        this.skillCode = skillCode;
        this.skillName = skillName;
        this.description = description;
        this.enabled = enabled;
        this.frontendVisible = frontendVisible;
        this.forceWhenClicked = forceWhenClicked;
        this.fallbackPriority = fallbackPriority;
        this.clarificationText = clarificationText;
    }

    public String getSkillCode() { return skillCode; }
    public void setSkillCode(String skillCode) { this.skillCode = skillCode; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isFrontendVisible() { return frontendVisible; }
    public void setFrontendVisible(boolean frontendVisible) { this.frontendVisible = frontendVisible; }
    public boolean isForceWhenClicked() { return forceWhenClicked; }
    public void setForceWhenClicked(boolean forceWhenClicked) { this.forceWhenClicked = forceWhenClicked; }
    public int getFallbackPriority() { return fallbackPriority; }
    public void setFallbackPriority(int fallbackPriority) { this.fallbackPriority = fallbackPriority; }
    public String getClarificationText() { return clarificationText; }
    public void setClarificationText(String clarificationText) { this.clarificationText = clarificationText; }
    public List<SkillExampleConfig> getExamples() { return examples; }
    public void setExamples(List<SkillExampleConfig> examples) { this.examples = examples; }
}
