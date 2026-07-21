package org.gundy.chat.entity.definition;

import java.util.ArrayList;
import java.util.List;

public class FlowDefinition {
    private String id;
    private String initialStage;
    private List<FlowStageDefinition> stages = new ArrayList<FlowStageDefinition>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInitialStage() { return initialStage; }
    public void setInitialStage(String initialStage) { this.initialStage = initialStage; }
    public List<FlowStageDefinition> getStages() { return stages; }
    public void setStages(List<FlowStageDefinition> stages) { this.stages = stages; }
}
