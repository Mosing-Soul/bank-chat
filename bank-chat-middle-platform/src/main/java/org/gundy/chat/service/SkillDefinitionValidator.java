package org.gundy.chat.service;

import org.gundy.chat.entity.definition.FlowDefinition;
import org.gundy.chat.entity.definition.FlowStageDefinition;
import org.gundy.chat.entity.definition.SkillDefinition;
import org.gundy.chat.entity.definition.SkillDefinitionCatalog;
import org.gundy.chat.entity.definition.SkillRiskLevel;
import org.gundy.chat.entity.definition.SlotDefinition;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class SkillDefinitionValidator {

    public void validate(SkillDefinitionCatalog catalog) {
        require(catalog != null, "skill definition catalog is required");
        require(hasText(catalog.getVersion()), "catalog version is required");
        require(catalog.getSkills() != null && !catalog.getSkills().isEmpty(), "at least one skill is required");

        Set<String> skillIds = new HashSet<String>();
        Set<String> flowIds = new HashSet<String>();
        for (SkillDefinition skill : catalog.getSkills()) {
            validateSkill(skill, skillIds, flowIds);
        }
    }

    private void validateSkill(SkillDefinition skill, Set<String> skillIds, Set<String> flowIds) {
        require(skill != null, "skill definition cannot be null");
        require(hasText(skill.getId()), "skill id is required");
        require(skillIds.add(skill.getId()), "duplicate skill id: " + skill.getId());
        require(hasText(skill.getName()), "skill name is required: " + skill.getId());
        require(hasText(skill.getDescription()), "skill description is required: " + skill.getId());
        require(skill.getRiskLevel() != null, "risk level is required: " + skill.getId());
        require(skill.getInterruptPolicy() != null, "interrupt policy is required: " + skill.getId());
        require(hasText(skill.getExecutor()), "executor is required: " + skill.getId());
        if (SkillRiskLevel.EXTERNAL_SIDE_EFFECT.equals(skill.getRiskLevel())) {
            require(skill.isConfirmationRequired(), "side-effect skill must require confirmation: " + skill.getId());
        }

        Set<String> slotIds = validateSlots(skill);
        validateFlow(skill, slotIds, flowIds);
    }

    private Set<String> validateSlots(SkillDefinition skill) {
        Set<String> slotIds = new HashSet<String>();
        List<SlotDefinition> slots = skill.getSlots();
        require(slots != null, "slots cannot be null: " + skill.getId());
        for (SlotDefinition slot : slots) {
            require(slot != null && hasText(slot.getId()), "slot id is required: " + skill.getId());
            require(slotIds.add(slot.getId()), "duplicate slot id " + slot.getId() + " in " + skill.getId());
            require(hasText(slot.getType()), "slot type is required: " + skill.getId() + "." + slot.getId());
            if (slot.isRequired()) {
                require(slot.getPrompts() != null && !slot.getPrompts().isEmpty(),
                        "required slot needs at least one prompt: " + skill.getId() + "." + slot.getId());
            }
        }
        return slotIds;
    }

    private void validateFlow(SkillDefinition skill, Set<String> slotIds, Set<String> flowIds) {
        FlowDefinition flow = skill.getFlow();
        require(flow != null, "flow is required: " + skill.getId());
        require(hasText(flow.getId()), "flow id is required: " + skill.getId());
        require(flowIds.add(flow.getId()), "duplicate flow id: " + flow.getId());
        require(hasText(flow.getInitialStage()), "initial stage is required: " + flow.getId());
        require(flow.getStages() != null && !flow.getStages().isEmpty(), "flow stages are required: " + flow.getId());

        Set<String> stageIds = new HashSet<String>();
        Set<String> collectedSlots = new HashSet<String>();
        boolean hasTerminalStage = false;
        for (FlowStageDefinition stage : flow.getStages()) {
            require(stage != null && hasText(stage.getId()), "stage id is required: " + flow.getId());
            require(stageIds.add(stage.getId()), "duplicate stage id " + stage.getId() + " in " + flow.getId());
            require(hasText(stage.getType()), "stage type is required: " + flow.getId() + "." + stage.getId());
            if (stage.getRequiredSlots() != null) {
                for (String slotId : stage.getRequiredSlots()) {
                    require(slotIds.contains(slotId), "unknown slot " + slotId + " in " + flow.getId() + "." + stage.getId());
                    collectedSlots.add(slotId);
                }
            }
            hasTerminalStage = hasTerminalStage || stage.isTerminal();
        }
        require(stageIds.contains(flow.getInitialStage()), "initial stage does not exist: " + flow.getId());
        require(hasTerminalStage, "flow needs at least one terminal stage: " + flow.getId());
        for (SlotDefinition slot : skill.getSlots()) {
            if (slot.isRequired()) {
                require(collectedSlots.contains(slot.getId()),
                        "required slot is not collected by any stage: " + skill.getId() + "." + slot.getId());
            }
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid skill definition: " + message);
        }
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
