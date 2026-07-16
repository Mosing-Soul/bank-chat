package org.gundy.chat.entity.config;

import java.util.ArrayList;
import java.util.List;

public class SkillConfigResponse {
    private List<SkillConfig> skills = new ArrayList<SkillConfig>();
    private List<SkillExampleConfig> quickActions = new ArrayList<SkillExampleConfig>();
    private List<SkillExampleConfig> greetings = new ArrayList<SkillExampleConfig>();

    public List<SkillConfig> getSkills() { return skills; }
    public void setSkills(List<SkillConfig> skills) { this.skills = skills; }
    public List<SkillExampleConfig> getQuickActions() { return quickActions; }
    public void setQuickActions(List<SkillExampleConfig> quickActions) { this.quickActions = quickActions; }
    public List<SkillExampleConfig> getGreetings() { return greetings; }
    public void setGreetings(List<SkillExampleConfig> greetings) { this.greetings = greetings; }
}
