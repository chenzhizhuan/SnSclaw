package vip.mate.decision.api;

public sealed interface DecisionValue {
    record Choice(String code) implements DecisionValue {
        public Choice { DecisionCodes.requireCode(code); }
    }
    record BooleanValue(boolean value) implements DecisionValue {}
    record Score(double value) implements DecisionValue {
        public Score { if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite score"); }
    }
    default String encoded() {
        return switch (this) {
            case Choice c -> c.code();
            case BooleanValue b -> Boolean.toString(b.value());
            case Score s -> Double.toString(s.value());
        };
    }
}
