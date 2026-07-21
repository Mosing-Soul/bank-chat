package org.gundy.chat.entity.definition;

import java.util.ArrayList;
import java.util.List;

public class SkillDefinition {
    private String id;
    private String name;
    private String description;
    private boolean enabled = true;
    private SkillRiskLevel riskLevel;
    private InterruptPolicy interruptPolicy;
    private boolean confirmationRequired;
    private String executor;
    private List<String> domains = new ArrayList<String>();
    private List<SlotDefinition> slots = new ArrayList<SlotDefinition>();
    private FlowDefinition flow;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public SkillRiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(SkillRiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public InterruptPolicy getInterruptPolicy() { return interruptPolicy; }
    public void setInterruptPolicy(InterruptPolicy interruptPolicy) { this.interruptPolicy = interruptPolicy; }
    public boolean isConfirmationRequired() { return confirmationRequired; }
    public void setConfirmationRequired(boolean confirmationRequired) { this.confirmationRequired = confirmationRequired; }
    public String getExecutor() { return executor; }
    public void setExecutor(String executor) { this.executor = executor; }
    public List<String> getDomains() { return domains; }
    public void setDomains(List<String> domains) { this.domains = domains; }
    public List<SlotDefinition> getSlots() { return slots; }
    public void setSlots(List<SlotDefinition> slots) { this.slots = slots; }
    public FlowDefinition getFlow() { return flow; }
    public void setFlow(FlowDefinition flow) { this.flow = flow; }
}
