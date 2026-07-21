package org.gundy.chat.entity.definition;

import java.util.ArrayList;
import java.util.List;

public class FlowStageDefinition {
    private String id;
    private String type;
    private List<String> requiredSlots = new ArrayList<String>();
    private boolean terminal;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public List<String> getRequiredSlots() { return requiredSlots; }
    public void setRequiredSlots(List<String> requiredSlots) { this.requiredSlots = requiredSlots; }
    public boolean isTerminal() { return terminal; }
    public void setTerminal(boolean terminal) { this.terminal = terminal; }
}
