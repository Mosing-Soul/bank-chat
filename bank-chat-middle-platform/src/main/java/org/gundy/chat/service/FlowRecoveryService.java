package org.gundy.chat.service;

import org.gundy.chat.entity.definition.SkillRiskLevel;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.gundy.chat.flow.FlowEngine;
import org.gundy.chat.statemachine.SkillTransitionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FlowRecoveryService {
    private final FlowEngine flowEngine;
    private final SkillDefinitionRegistry registry;
    private final boolean autoResumeReadOnly;

    public FlowRecoveryService(FlowEngine flowEngine, SkillDefinitionRegistry registry,
                               @Value("${ai.dialogue-command.auto-resume-read-only:true}") boolean autoResumeReadOnly) {
        this.flowEngine = flowEngine;
        this.registry = registry;
        this.autoResumeReadOnly = autoResumeReadOnly;
    }

    public SkillTransitionResult afterTerminal(SkillTransitionResult result) {
        if (result == null || !result.isTerminal() || result.getDialogState() == null) return result;
        FlowInstance suspended = latestSuspended(result.getDialogState());
        if (suspended == null) return result;
        SkillRiskLevel risk = registry.require(suspended.getSkillId()).getRiskLevel();
        if (autoResumeReadOnly && !SkillRiskLevel.EXTERNAL_SIDE_EFFECT.equals(risk)) {
            flowEngine.resumeFlow(result.getDialogState(), suspended);
            result.setAnswer(append(result.getAnswer(), "已返回之前暂停的" + registry.require(suspended.getSkillId()).getName() + "。"));
        } else {
            result.setAnswer(append(result.getAnswer(), "之前的" + registry.require(suspended.getSkillId()).getName()
                    + "仍为暂停状态，如需继续请明确告诉我。"));
        }
        return result;
    }

    private FlowInstance latestSuspended(DialogState state) {
        if (state.getFlowStack() == null) return null;
        for (int i = state.getFlowStack().size() - 1; i >= 0; i--) {
            if ("SUSPENDED".equals(state.getFlowStack().get(i).getStatus())) return state.getFlowStack().get(i);
        }
        return null;
    }

    private String append(String answer, String suffix) {
        return answer == null || answer.trim().length() == 0 ? suffix : answer + " " + suffix;
    }
}
