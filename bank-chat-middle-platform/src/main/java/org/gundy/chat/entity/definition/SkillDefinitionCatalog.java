package org.gundy.chat.entity.definition;

import java.util.ArrayList;
import java.util.List;

public class SkillDefinitionCatalog {
    private String version;
    private List<SkillDefinition> skills = new ArrayList<SkillDefinition>();

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public List<SkillDefinition> getSkills() { return skills; }
    public void setSkills(List<SkillDefinition> skills) { this.skills = skills; }
}
