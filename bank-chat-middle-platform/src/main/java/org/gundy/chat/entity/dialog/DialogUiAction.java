package org.gundy.chat.entity.dialog;

public class DialogUiAction {
    private String label;
    private String action;
    private String variant;

    public DialogUiAction() {
    }

    public DialogUiAction(String label, String action, String variant) {
        this.label = label;
        this.action = action;
        this.variant = variant;
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getVariant() { return variant; }
    public void setVariant(String variant) { this.variant = variant; }
}
