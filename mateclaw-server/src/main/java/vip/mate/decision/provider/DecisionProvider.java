package vip.mate.decision.provider;

import vip.mate.decision.api.DecisionRequest;

public interface DecisionProvider {
    String id();
    DecisionCapability capability();
    DecisionResult decide(DecisionRequest request);
}
