package org.gundy.chat.entity.command;

import org.gundy.chat.entity.dialog.DialogState;

import java.util.ArrayList;
import java.util.List;

public class CommandDispatchResult {
    private DialogState dialogState;
    private List<CommandOutcome> outcomes = new ArrayList<CommandOutcome>();
    private boolean clarificationRequired;
    private String clarificationPrompt;

    public DialogState getDialogState() { return dialogState; }
    public void setDialogState(DialogState dialogState) { this.dialogState = dialogState; }
    public List<CommandOutcome> getOutcomes() { return outcomes; }
    public void setOutcomes(List<CommandOutcome> outcomes) { this.outcomes = outcomes; }
    public boolean isClarificationRequired() { return clarificationRequired; }
    public void setClarificationRequired(boolean clarificationRequired) { this.clarificationRequired = clarificationRequired; }
    public String getClarificationPrompt() { return clarificationPrompt; }
    public void setClarificationPrompt(String clarificationPrompt) { this.clarificationPrompt = clarificationPrompt; }
}
