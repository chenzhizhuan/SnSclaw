package vip.mate.decision.provider;

import org.springframework.stereotype.Component;
import vip.mate.decision.api.DecisionRequest;

@Component
public class RuleDecisionProvider implements DecisionProvider {
    public String id() { return "rule"; }
    public DecisionCapability capability() { return DecisionCapability.standard(); }
    public DecisionResult decide(DecisionRequest request) {
        return new DecisionResult(DecisionResult.Status.BASELINE, request.baseline(), null, "rule-v1");
    }
}
