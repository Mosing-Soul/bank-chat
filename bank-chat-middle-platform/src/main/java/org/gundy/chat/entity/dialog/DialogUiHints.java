package org.gundy.chat.entity.dialog;

import java.util.ArrayList;
import java.util.List;

public class DialogUiHints {
    private String replyMode;
    private String summary;
    private String prompt;
    private String inputHint;
    private List<DialogUiAction> quickActions = new ArrayList<DialogUiAction>();
    private List<DialogUiCard> cards = new ArrayList<DialogUiCard>();

    public String getReplyMode() { return replyMode; }
    public void setReplyMode(String replyMode) { this.replyMode = replyMode; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getInputHint() { return inputHint; }
    public void setInputHint(String inputHint) { this.inputHint = inputHint; }
    public List<DialogUiAction> getQuickActions() { return quickActions; }
    public void setQuickActions(List<DialogUiAction> quickActions) { this.quickActions = quickActions; }
    public List<DialogUiCard> getCards() { return cards; }
    public void setCards(List<DialogUiCard> cards) { this.cards = cards; }
}
