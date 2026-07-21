package org.gundy.chat.service;

import org.gundy.chat.entity.command.CommandDispatchResult;
import org.gundy.chat.entity.command.CommandOutcome;
import org.gundy.chat.entity.command.DialogueCommandResponse;
import org.gundy.chat.entity.flow.FlowInstance;
import org.gundy.chat.statemachine.SkillTransitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class DialogueMetricsService {
    private static final Logger AUDIT = LoggerFactory.getLogger("DIALOGUE_AUDIT");
    private final AtomicLong interpreted = new AtomicLong();
    private final AtomicLong modelUsed = new AtomicLong();
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong clarified = new AtomicLong();
    private final AtomicLong fallback = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();

    public void interpretation(String traceId, DialogueCommandResponse response, long durationMs) {
        interpreted.incrementAndGet();
        if (response != null && response.isModelUsed()) modelUsed.incrementAndGet();
        AUDIT.info("event=command_interpretation traceId={} modelUsed={} commandCount={} durationMs={}", traceId,
                response != null && response.isModelUsed(), response == null || response.getCommands() == null
                        ? 0 : response.getCommands().size(), durationMs);
    }

    public void dispatch(String traceId, CommandDispatchResult result) {
        if (result != null) for (CommandOutcome outcome : result.getOutcomes()) {
            if ("APPLIED".equals(outcome.getStatus())) applied.incrementAndGet();
            if ("REJECTED".equals(outcome.getStatus())) rejected.incrementAndGet();
            if ("CLARIFICATION_REQUIRED".equals(outcome.getStatus())) clarified.incrementAndGet();
            AUDIT.info("event=command_dispatch traceId={} type={} status={} flowInstanceId={}", traceId,
                    outcome.getType(), outcome.getStatus(), outcome.getFlowInstanceId());
        }
    }

    public void fallback(String traceId, String reason) {
        fallback.incrementAndGet();
        AUDIT.info("event=command_fallback traceId={} reason={}", traceId, reason);
    }

    public void flow(String traceId, SkillTransitionResult result) {
        if (result != null && result.isTerminal()) completed.incrementAndGet();
        FlowInstance active = result == null || result.getDialogState() == null ? null
                : latestActive(result.getDialogState().getFlowStack());
        AUDIT.info("event=flow_transition traceId={} terminal={} activeSkill={} stage={}", traceId,
                result != null && result.isTerminal(), active == null ? null : active.getSkillId(),
                active == null ? null : active.getCurrentStage());
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> values = new LinkedHashMap<String, Long>();
        values.put("interpreted", interpreted.get()); values.put("modelUsed", modelUsed.get());
        values.put("applied", applied.get()); values.put("rejected", rejected.get());
        values.put("clarified", clarified.get()); values.put("fallback", fallback.get());
        values.put("completed", completed.get()); return values;
    }

    private FlowInstance latestActive(java.util.List<FlowInstance> flows) {
        if (flows == null) return null;
        for (int i = flows.size() - 1; i >= 0; i--) if ("ACTIVE".equals(flows.get(i).getStatus())) return flows.get(i);
        return null;
    }
}
