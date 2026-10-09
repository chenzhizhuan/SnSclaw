package vip.mate.decision;

import org.junit.jupiter.api.Test;
import vip.mate.decision.api.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class DecisionContractTest {
    @Test void typedQuestionsRejectWrongKindsAndOutOfRangeValues() {
        var choice = new DecisionQuestion.Choice("v1", "Choose action", List.of(new DecisionQuestion.Option("CONTINUE", "Continue")));
        assertTrue(choice.accepts(new DecisionValue.Choice("CONTINUE")));
        assertFalse(choice.accepts(new DecisionValue.BooleanValue(true)));
        assertFalse(choice.accepts(new DecisionValue.Choice("COMPLETE")));
        assertFalse(new DecisionQuestion.Score("v1", "Score", 0, 1).accepts(new DecisionValue.Score(2)));
        assertThrows(IllegalArgumentException.class, () -> new DecisionValue.Score(Double.NaN));
    }
    @Test void factsAreImmutableAndRejectUnboundedOrNonFiniteData() {
        var flags = new HashMap<String, Boolean>();
        flags.put("eligible", true);
        var facts = new DecisionFacts(flags, Map.of("attempts", 2d));
        flags.clear();
        assertTrue(facts.flags().get("eligible"));
        assertThrows(UnsupportedOperationException.class, () -> facts.flags().clear());
        assertThrows(IllegalArgumentException.class, () -> new DecisionFacts(Map.of(), Map.of("bad", Double.NaN)));
    }
    @Test void limitsRejectInsteadOfSilentlyTruncatingAndGuardsCannotBeOverridden() {
        assertThrows(IllegalArgumentException.class, () -> new DecisionFacts(Map.of(), Map.of(), List.of("x".repeat(8193))));
        assertThrows(IllegalArgumentException.class, () -> new DecisionQuestion.Choice("v1", "Choose", List.of(
                new DecisionQuestion.Option("A", "first"), new DecisionQuestion.Option("A", "second"))));
        var r = DecisionServiceTest.request(null);
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest(r.type(), r.scope(), r.phase(), r.question(), r.facts(),
                r.baseline(), r.baseline(), new DecisionValue.Choice("DEFER")));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest(r.type(), r.scope(), r.phase(), r.question(), r.facts(),
                r.baseline(), null, new DecisionValue.Choice("DELETE")));
    }

}
