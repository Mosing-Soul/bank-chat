package org.gundy.chat.entity.dialog;

import java.util.ArrayList;
import java.util.List;

public class DialogUiCard {
    private String type;
    private String title;
    private List<DialogUiField> fields = new ArrayList<DialogUiField>();
    private List<DialogUiAction> actions = new ArrayList<DialogUiAction>();

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<DialogUiField> getFields() { return fields; }
    public void setFields(List<DialogUiField> fields) { this.fields = fields; }
    public List<DialogUiAction> getActions() { return actions; }
    public void setActions(List<DialogUiAction> actions) { this.actions = actions; }
}
