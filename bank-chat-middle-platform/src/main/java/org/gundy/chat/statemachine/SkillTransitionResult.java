package org.gundy.chat.statemachine;

import org.gundy.chat.entity.dialog.DialogState;

import java.util.Map;

public class SkillTransitionResult {
    private boolean handled;
    private boolean terminal;
    private boolean clearState;
    private boolean requiresConfirmation;
    private String answer;
    private DialogState dialogState;
    private Map<String, Object> data;
    private Map<String, Object> confirmation;

    public boolean isHandled() { return handled; }
    public void setHandled(boolean handled) { this.handled = handled; }
    public boolean isTerminal() { return terminal; }
    public void setTerminal(boolean terminal) { this.terminal = terminal; }
    public boolean isClearState() { return clearState; }
    public void setClearState(boolean clearState) { this.clearState = clearState; }
    public boolean isRequiresConfirmation() { return requiresConfirmation; }
    public void setRequiresConfirmation(boolean requiresConfirmation) { this.requiresConfirmation = requiresConfirmation; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public DialogState getDialogState() { return dialogState; }
    public void setDialogState(DialogState dialogState) { this.dialogState = dialogState; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
    public Map<String, Object> getConfirmation() { return confirmation; }
    public void setConfirmation(Map<String, Object> confirmation) { this.confirmation = confirmation; }

    public static SkillTransitionResult notHandled() {
        SkillTransitionResult result = new SkillTransitionResult();
        result.setHandled(false);
        return result;
    }
}
