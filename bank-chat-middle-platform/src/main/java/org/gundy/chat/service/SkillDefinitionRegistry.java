package org.gundy.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gundy.chat.entity.definition.SkillDefinition;
import org.gundy.chat.entity.definition.SkillDefinitionCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SkillDefinitionRegistry {
    private final String version;
    private final Map<String, SkillDefinition> definitions;

    public SkillDefinitionRegistry(ObjectMapper objectMapper,
                                   ResourceLoader resourceLoader,
                                   SkillDefinitionValidator validator,
                                   @Value("${bank.dialogue.skill-definitions:classpath:config/skill-definitions.json}") String location) {
        SkillDefinitionCatalog catalog = load(objectMapper, resourceLoader, location);
        validator.validate(catalog);
        this.version = catalog.getVersion();
        Map<String, SkillDefinition> values = new LinkedHashMap<String, SkillDefinition>();
        for (SkillDefinition definition : catalog.getSkills()) {
            values.put(definition.getId(), definition);
        }
        this.definitions = Collections.unmodifiableMap(values);
    }

    public String version() {
        return version;
    }

    public List<SkillDefinition> all() {
        return Collections.unmodifiableList(new ArrayList<SkillDefinition>(definitions.values()));
    }

    public List<SkillDefinition> enabled() {
        List<SkillDefinition> enabled = new ArrayList<SkillDefinition>();
        for (SkillDefinition definition : definitions.values()) {
            if (definition.isEnabled()) {
                enabled.add(definition);
            }
        }
        return Collections.unmodifiableList(enabled);
    }

    public SkillDefinition find(String skillId) {
        if (skillId == null) {
            return null;
        }
        return definitions.get(skillId.trim().toUpperCase());
    }

    public SkillDefinition require(String skillId) {
        SkillDefinition definition = find(skillId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown skill definition: " + skillId);
        }
        return definition;
    }

    private SkillDefinitionCatalog load(ObjectMapper objectMapper, ResourceLoader resourceLoader, String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Skill definition resource does not exist: " + location);
        }
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, SkillDefinitionCatalog.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load skill definitions from " + location, ex);
        }
    }
}
