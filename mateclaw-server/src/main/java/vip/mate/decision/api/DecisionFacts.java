package vip.mate.decision.api;

import java.util.List;
import java.util.Map;

public record DecisionFacts(Map<String, Boolean> flags, Map<String, Double> numbers, List<String> evidence) {
    public DecisionFacts(Map<String, Boolean> flags, Map<String, Double> numbers) { this(flags, numbers, List.of()); }
    public DecisionFacts {
        flags = Map.copyOf(flags); numbers = Map.copyOf(numbers); evidence = List.copyOf(evidence);
        if (flags.size() + numbers.size() > 64 || evidence.size() > 8 || evidence.stream().mapToInt(String::length).sum() > 8192)
            throw new IllegalArgumentException("Facts exceed limits");
        flags.keySet().forEach(DecisionCodes::requireCode); numbers.keySet().forEach(DecisionCodes::requireCode);
        if (numbers.values().stream().anyMatch(n -> !Double.isFinite(n))) throw new IllegalArgumentException("Nonfinite fact");
    }
}
