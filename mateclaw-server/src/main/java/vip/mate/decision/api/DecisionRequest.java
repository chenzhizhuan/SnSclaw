package vip.mate.decision.api;

import java.util.Objects;

/**
 * An immutable snapshot assembled by trusted domain code. Options define the legal domain;
 * guard and policy values must originate from server policy, never provider or client input.
 */
public record DecisionRequest(DecisionType type, DecisionScope scope, String phase, DecisionQuestion question,
                              DecisionFacts facts, DecisionValue baseline, DecisionValue guardValue, DecisionValue policyOverride) {
    public DecisionRequest {
        Objects.requireNonNull(type); Objects.requireNonNull(scope); DecisionCodes.requireCode(phase);
        Objects.requireNonNull(question); Objects.requireNonNull(facts); Objects.requireNonNull(baseline);
        if (!question.accepts(baseline) || guardValue != null && !question.accepts(guardValue)
                || policyOverride != null && !question.accepts(policyOverride)) throw new IllegalArgumentException("Value outside question domain");
        if (guardValue != null && policyOverride != null && !guardValue.equals(policyOverride)) throw new IllegalArgumentException("Policy cannot override guard");
    }
}
