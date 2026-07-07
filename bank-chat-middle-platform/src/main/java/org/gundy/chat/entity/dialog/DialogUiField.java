package org.gundy.chat.entity.dialog;

public class DialogUiField {
    private String label;
    private Object value;

    public DialogUiField() {
    }

    public DialogUiField(String label, Object value) {
        this.label = label;
        this.value = value;
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
}
