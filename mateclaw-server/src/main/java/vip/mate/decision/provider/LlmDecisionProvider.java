package vip.mate.decision.provider;

import org.springframework.stereotype.Component;
import vip.mate.decision.api.DecisionRequest;

@Component
public class LlmDecisionProvider implements DecisionProvider {
    public String id() { return "llm"; }
    public DecisionCapability capability() { return DecisionCapability.standard(); }
    public DecisionResult decide(DecisionRequest request) { return DecisionResult.unavailable(); }
}
