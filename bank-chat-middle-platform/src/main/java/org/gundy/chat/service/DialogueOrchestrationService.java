package org.gundy.chat.service;

import org.gundy.chat.command.DialogCommandDispatcher;
import org.gundy.chat.entity.HistoryMessage;
import org.gundy.chat.entity.command.CommandDispatchResult;
import org.gundy.chat.entity.command.CommandOutcome;
import org.gundy.chat.entity.command.DialogCommand;
import org.gundy.chat.entity.command.DialogCommandType;
import org.gundy.chat.entity.command.DialogueCommandResponse;
import org.gundy.chat.entity.dialog.DialogState;
import org.gundy.chat.entity.flow.FlowInstance;
import org.gundy.chat.flow.FlowEngine;
import org.gundy.chat.statemachine.SkillTransitionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.gundy.chat.progress.DialogueProgress;

import java.util.List;

@Service
public class DialogueOrchestrationService {
    private final DialogueCommandService commandService;
    private final DialogCommandDispatcher dispatcher;
    private final FlowEngine flowEngine;
    private final FlowRecoveryService recoveryService;
    private final DialogueMetricsService metrics;
    private final boolean enabled;

    public DialogueOrchestrationService(DialogueCommandService commandService,
                                        DialogCommandDispatcher dispatcher,
                                        FlowEngine flowEngine,
                                        FlowRecoveryService recoveryService,
                                        DialogueMetricsService metrics,
                                        @Value("${ai.dialogue-command.enabled:${AI_DIALOGUE_COMMAND_ENABLED:true}}") boolean enabled) {
        this.commandService = commandService;
        this.dispatcher = dispatcher;
        this.flowEngine = flowEngine;
        this.recoveryService = recoveryService;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public SkillTransitionResult tryHandle(String traceId, String sessionId, String userMessage,
                                           DialogState state, List<HistoryMessage> history,
                                           String requestedSkill, boolean forceSkill) {
        if (!enabled || forceSkill || hasText(requestedSkill)) return SkillTransitionResult.notHandled();
        DialogueCommandResponse interpretation;
        long interpretationStart = System.currentTimeMillis();
        try {
            DialogueProgress.report("COMMAND_UNDERSTANDING", "正在理解对话动作", "结合当前事项和最近对话判断下一步");
            interpretation = commandService.interpret(traceId, sessionId, userMessage, state, history);
        } catch (RestClientException ex) {
            metrics.fallback(traceId, "COMMAND_SERVICE_UNAVAILABLE");
            return SkillTransitionResult.notHandled();
        }
        metrics.interpretation(traceId, interpretation, System.currentTimeMillis() - interpretationStart);
        if (interpretation == null || interpretation.getCommands() == null
                || interpretation.getCommands().isEmpty() || onlyNoOp(interpretation.getCommands())) {
            metrics.fallback(traceId, "NO_ACTIONABLE_COMMAND");
            return SkillTransitionResult.notHandled();
        }

        CommandDispatchResult dispatched = dispatcher.dispatch(sessionId, state, interpretation.getCommands());
        DialogueProgress.report("POLICY_VALIDATED", "已完成流程安全校验", "正在推进可执行的办理步骤");
        metrics.dispatch(traceId, dispatched);
        if (dispatched.isClarificationRequired()) {
            return message(dispatched.getDialogState(), friendlyClarification(dispatched.getClarificationPrompt()), false);
        }
        if (!hasApplied(dispatched)) {
            return message(dispatched.getDialogState(), firstRejection(dispatched), false);
        }

        DialogState nextState = dispatched.getDialogState();
        FlowInstance active = flowEngine.activeFlow(nextState);
        if (active == null) {
            SkillTransitionResult result = recoveryService.afterTerminal(
                    message(nextState, cancellationAnswer(interpretation.getCommands()), true));
            metrics.flow(traceId, result);
            return result;
        }
        SkillTransitionResult result = recoveryService.afterTerminal(
                flowEngine.handle(traceId, sessionId, nextState, active.getSkillId(), userMessage));
        metrics.flow(traceId, result);
        return result;
    }

    private boolean onlyNoOp(List<DialogCommand> commands) {
        for (DialogCommand command : commands) if (!DialogCommandType.NO_OP.equals(command.getType())) return false;
        return true;
    }

    private boolean hasApplied(CommandDispatchResult result) {
        for (CommandOutcome outcome : result.getOutcomes()) if ("APPLIED".equals(outcome.getStatus())) return true;
        return false;
    }

    private String firstRejection(CommandDispatchResult result) {
        for (CommandOutcome outcome : result.getOutcomes()) {
            if ("REJECTED".equals(outcome.getStatus())) return "当前操作暂时无法执行，请确认后再试。";
        }
        return "请换一种方式说明需要办理的事项。";
    }

    private String friendlyClarification(String reason) {
        return "我找到了几个可能的办理方向。请告诉我您想继续当前事项，还是办理其他业务。";
    }

    private String cancellationAnswer(List<DialogCommand> commands) {
        for (DialogCommand command : commands) {
            if (DialogCommandType.CANCEL_FLOW.equals(command.getType()) || DialogCommandType.REJECT.equals(command.getType())) {
                return "已取消当前办理事项。";
            }
        }
        return "当前办理事项已结束。";
    }

    private SkillTransitionResult message(DialogState state, String answer, boolean terminal) {
        SkillTransitionResult result = new SkillTransitionResult();
        result.setHandled(true);
        result.setDialogState(state);
        result.setAnswer(answer);
        result.setTerminal(terminal);
        return result;
    }

    private boolean hasText(String value) { return value != null && value.trim().length() > 0; }
}
