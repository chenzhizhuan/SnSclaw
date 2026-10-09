package vip.mate.decision.provider;

import java.util.Set;
import vip.mate.decision.api.*;

public record DecisionCapability(Set<Kind> kinds, Set<String> languages, int maxOptions, int maxEvidenceCharacters) {
    public enum Kind { CHOICE, BOOLEAN, SCORE }
    public DecisionCapability {
        kinds = Set.copyOf(kinds); languages = Set.copyOf(languages);
        if (maxOptions < 1 || maxEvidenceCharacters < 0) throw new IllegalArgumentException("Invalid capability limits");
    }
    public static DecisionCapability standard() { return new DecisionCapability(Set.of(Kind.values()), Set.of("und"), 64, 8192); }
    public boolean supports(DecisionRequest request) {
        var kind = switch (request.question()) {
            case DecisionQuestion.Choice ignored -> Kind.CHOICE;
            case DecisionQuestion.BooleanQuestion ignored -> Kind.BOOLEAN;
            case DecisionQuestion.Score ignored -> Kind.SCORE;
        };
        return kinds.contains(kind) && (!(request.question() instanceof DecisionQuestion.Choice c) || c.options().size() <= maxOptions)
                && request.facts().evidence().stream().mapToInt(String::length).sum() <= maxEvidenceCharacters;
    }
}
