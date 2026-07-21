package org.gundy.chat.service;

import org.gundy.chat.entity.HistoryMessage;
import org.gundy.chat.entity.command.DialogueCommandRequest;
import org.gundy.chat.entity.command.DialogueCommandResponse;
import org.gundy.chat.entity.definition.SkillDefinition;
import org.gundy.chat.entity.definition.SlotDefinition;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DialogueCommandService {
    private static final String REDACTED_VALUE = "[SENSITIVE_SET]";

    private final RestTemplate restTemplate;
    private final SkillDefinitionRegistry registry;
    private final String commandUrl;

    public DialogueCommandService(RestTemplate restTemplate,
                                  SkillDefinitionRegistry registry,
                                  @Value("${ai.dialogue-command.url:${AI_DIALOGUE_COMMAND_URL:http://localhost:8000/ai/dialogue/commands}}") String commandUrl) {
        this.restTemplate = restTemplate;
        this.registry = registry;
        this.commandUrl = commandUrl;
    }

    public DialogueCommandResponse interpret(String traceId, String sessionId, String message,
                                             DialogState state, List<HistoryMessage> history) {
        DialogueCommandRequest request = new DialogueCommandRequest();
        request.setTraceId(traceId);
        request.setSessionId(sessionId);
        request.setMessage(message);
        request.setFlowStack(flowSnapshots(state));
        request.setCandidateSkills(skillSnapshots());
        request.setHistory(history == null ? new ArrayList<HistoryMessage>() : history);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Trace-Id", traceId);
        ResponseEntity<DialogueCommandResponse> response = restTemplate.exchange(
                commandUrl, HttpMethod.POST, new HttpEntity<DialogueCommandRequest>(request, headers),
                DialogueCommandResponse.class);
        return response.getBody();
    }

    private List<Map<String, Object>> flowSnapshots(DialogState state) {
        List<Map<String, Object>> snapshots = new ArrayList<Map<String, Object>>();
        if (state == null || state.getFlowStack() == null) return snapshots;
        for (FlowInstance flow : state.getFlowStack()) {
            SkillDefinition definition = registry.require(flow.getSkillId());
            Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("instanceId", flow.getInstanceId());
            snapshot.put("skillId", flow.getSkillId());
            snapshot.put("status", flow.getStatus());
            snapshot.put("currentStage", flow.getCurrentStage());
            snapshot.put("slots", sanitizedSlots(flow, definition));
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    private Map<String, Object> sanitizedSlots(FlowInstance flow, SkillDefinition definition) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (SlotDefinition slot : definition.getSlots()) {
            Object value = flow.getSlots().get(slot.getId());
            if (value != null) {
                if (slot.isSensitive() && slot.isShareable()) {
                    values.put(slot.getId(), "flow-slot://" + flow.getInstanceId() + "/" + slot.getId());
                } else {
                    values.put(slot.getId(), slot.isSensitive() ? REDACTED_VALUE : value);
                }
            }
        }
        return values;
    }

    private List<Map<String, Object>> skillSnapshots() {
        List<Map<String, Object>> snapshots = new ArrayList<Map<String, Object>>();
        for (SkillDefinition definition : registry.enabled()) {
            Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("id", definition.getId());
            snapshot.put("name", definition.getName());
            snapshot.put("description", definition.getDescription());
            snapshot.put("riskLevel", definition.getRiskLevel().name());
            snapshot.put("interruptPolicy", definition.getInterruptPolicy().name());
            List<String> slots = new ArrayList<String>();
            for (SlotDefinition slot : definition.getSlots()) slots.add(slot.getId());
            snapshot.put("slots", slots);
            snapshots.add(snapshot);
        }
        return snapshots;
    }
}
