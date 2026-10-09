package vip.mate.decision.record;

import vip.mate.decision.api.*;

public record DecisionRecord(String id, DecisionType type, DecisionScope scope, String phase, String questionVersion,
                             DecisionMode mode, String provider, String providerVersion, String valueKind,
                             String baseline, String proposed, String effective, Double confidence, String reason, String overrideReason,
                             long elapsedMs) {
    public String policyVersion() { return "v1"; }
}
