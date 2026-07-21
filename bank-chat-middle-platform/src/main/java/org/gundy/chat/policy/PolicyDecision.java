package org.gundy.chat.policy;

public class PolicyDecision {
    public enum Verdict { ALLOW, DENY, CLARIFY }
    public enum ExistingFlowAction { NONE, SUSPEND, CANCEL }

    private Verdict verdict;
    private ExistingFlowAction existingFlowAction = ExistingFlowAction.NONE;
    private String reason;

    public static PolicyDecision allow(String reason) {
        return allow(reason, ExistingFlowAction.NONE);
    }

    public static PolicyDecision allow(String reason, ExistingFlowAction action) {
        PolicyDecision decision = new PolicyDecision();
        decision.setVerdict(Verdict.ALLOW);
        decision.setExistingFlowAction(action);
        decision.setReason(reason);
        return decision;
    }

    public static PolicyDecision deny(String reason) {
        PolicyDecision decision = new PolicyDecision();
        decision.setVerdict(Verdict.DENY);
        decision.setReason(reason);
        return decision;
    }

    public static PolicyDecision clarify(String reason) {
        PolicyDecision decision = new PolicyDecision();
        decision.setVerdict(Verdict.CLARIFY);
        decision.setReason(reason);
        return decision;
    }

    public Verdict getVerdict() { return verdict; }
    public void setVerdict(Verdict verdict) { this.verdict = verdict; }
    public ExistingFlowAction getExistingFlowAction() { return existingFlowAction; }
    public void setExistingFlowAction(ExistingFlowAction existingFlowAction) { this.existingFlowAction = existingFlowAction; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
