package vip.mate.decision.api;

import java.util.List;
import java.util.HashSet;

public sealed interface DecisionQuestion {
    String version();
    String description();
    boolean accepts(DecisionValue value);
    record Option(String code, String description) {
        public Option { DecisionCodes.requireCode(code); DecisionCodes.description(description); }
    }
    record Choice(String version, String description, List<Option> options) implements DecisionQuestion {
        public Choice {
            DecisionCodes.requireCode(version); DecisionCodes.description(description); options = List.copyOf(options);
            if (options.isEmpty() || options.size() > 64 || new HashSet<>(options.stream().map(Option::code).toList()).size() != options.size())
                throw new IllegalArgumentException("Invalid options");
        }
        public boolean accepts(DecisionValue value) { return value instanceof DecisionValue.Choice c && options.stream().anyMatch(o -> o.code().equals(c.code())); }
    }
    record BooleanQuestion(String version, String description) implements DecisionQuestion {
        public BooleanQuestion { DecisionCodes.requireCode(version); DecisionCodes.description(description); }
        public boolean accepts(DecisionValue value) { return value instanceof DecisionValue.BooleanValue; }
    }
    record Score(String version, String description, double min, double max) implements DecisionQuestion {
        public Score {
            DecisionCodes.requireCode(version); DecisionCodes.description(description);
            if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) throw new IllegalArgumentException("Invalid score range");
        }
        public boolean accepts(DecisionValue value) { return value instanceof DecisionValue.Score s && s.value() >= min && s.value() <= max; }
    }
}
