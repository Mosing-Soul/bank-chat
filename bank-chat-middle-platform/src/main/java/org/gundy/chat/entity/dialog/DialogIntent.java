package org.gundy.chat.entity.dialog;

public class DialogIntent {
    private String current;
    private Double confidence;
    private String source;

    public String getCurrent() { return current; }
    public void setCurrent(String current) { this.current = current; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
