package vip.mate.decision.api;

public final class DecisionCodes {
    private DecisionCodes() {}
    public static String requireCode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,80}")) throw new IllegalArgumentException("Invalid decision code");
        return value;
    }
    public static String description(String value) {
        if (value == null || value.isBlank() || value.length() > 2048) throw new IllegalArgumentException("Invalid description size");
        return value;
    }
}
