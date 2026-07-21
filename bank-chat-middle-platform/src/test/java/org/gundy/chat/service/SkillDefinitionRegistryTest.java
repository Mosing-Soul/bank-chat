package org.gundy.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gundy.chat.entity.definition.InterruptPolicy;
import org.gundy.chat.entity.definition.SkillDefinition;
import org.gundy.chat.entity.definition.SkillDefinitionCatalog;
import org.gundy.chat.entity.definition.SkillRiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillDefinitionRegistryTest {
    private static final String LOCATION = "classpath:config/skill-definitions.json";

    @Test
    void loadsTheFourRuntimeSkillDefinitions() {
        SkillDefinitionRegistry registry = registry();

        assertThat(registry.version()).isEqualTo("1.0");
        assertThat(registry.all()).extracting(SkillDefinition::getId)
                .containsExactly("CUSTOMER_AUM", "GOLD_PRICE", "RAG_QUERY", "MESSAGE_SEND");

        SkillDefinition customerAum = registry.require("customer_aum");
        assertThat(customerAum.getRiskLevel()).isEqualTo(SkillRiskLevel.READ_ONLY);
        assertThat(customerAum.getInterruptPolicy()).isEqualTo(InterruptPolicy.SUSPENDABLE);
        assertThat(customerAum.getSlots()).extracting("id").containsExactly("customerReference");
        assertThat(customerAum.getFlow().getInitialStage()).isEqualTo("COLLECTING_SLOTS");

        SkillDefinition messageSend = registry.require("MESSAGE_SEND");
        assertThat(messageSend.getRiskLevel()).isEqualTo(SkillRiskLevel.EXTERNAL_SIDE_EFFECT);
        assertThat(messageSend.isConfirmationRequired()).isTrue();
        assertThat(messageSend.getInterruptPolicy()).isEqualTo(InterruptPolicy.CONFIRM_OR_SUSPEND);
        assertThat(messageSend.getFlow().getStages()).extracting("id")
                .contains("WAITING_CONFIRMATION", "EXECUTING", "COMPLETED");
    }

    @Test
    void rejectsSideEffectSkillWithoutConfirmation() throws Exception {
        SkillDefinitionCatalog catalog = loadCatalog();
        registrySkill(catalog, "MESSAGE_SEND").setConfirmationRequired(false);

        assertThatThrownBy(() -> new SkillDefinitionValidator().validate(catalog))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("side-effect skill must require confirmation");
    }

    @Test
    void rejectsStageThatReferencesUnknownSlot() throws Exception {
        SkillDefinitionCatalog catalog = loadCatalog();
        registrySkill(catalog, "CUSTOMER_AUM").getFlow().getStages().get(0)
                .getRequiredSlots().add("unknownSlot");

        assertThatThrownBy(() -> new SkillDefinitionValidator().validate(catalog))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown slot unknownSlot");
    }

    @Test
    void unknownSkillFailsWithClearMessage() {
        assertThatThrownBy(() -> registry().require("NO_SUCH_SKILL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown skill definition");
    }

    private SkillDefinitionRegistry registry() {
        return new SkillDefinitionRegistry(new ObjectMapper(), new DefaultResourceLoader(),
                new SkillDefinitionValidator(), LOCATION);
    }

    private SkillDefinitionCatalog loadCatalog() throws Exception {
        try (InputStream input = new DefaultResourceLoader().getResource(LOCATION).getInputStream()) {
            return new ObjectMapper().readValue(input, SkillDefinitionCatalog.class);
        }
    }

    private SkillDefinition registrySkill(SkillDefinitionCatalog catalog, String id) {
        for (SkillDefinition skill : catalog.getSkills()) {
            if (id.equals(skill.getId())) {
                return skill;
            }
        }
        throw new IllegalArgumentException(id);
    }
}
