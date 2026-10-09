package vip.mate.decision.provider;

import vip.mate.decision.api.DecisionValue;

public record DecisionResult(Status status, DecisionValue value, Double confidence, String version) {
    public enum Status { PROPOSED, BASELINE, UNAVAILABLE, ABSTAIN }
    public static DecisionResult proposed(DecisionValue value, double confidence, String version) { return new DecisionResult(Status.PROPOSED, value, confidence, version); }
    public static DecisionResult unavailable() { return new DecisionResult(Status.UNAVAILABLE, null, null, "stub-v1"); }
}
