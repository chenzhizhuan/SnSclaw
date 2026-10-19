package vip.mate.architecture;

import org.junit.jupiter.api.Test;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** All declared business keys survive unrelated node patches without registration. */
class StateKeyRegistrationCoverageTest {
    @Test void allCommonAndPlanKeysSurvivePatchesAndSnapshots() throws Exception {
        Set<String> append = Set.of(MateClawStateKeys.MESSAGES, MateClawStateKeys.PENDING_EVENTS, PlanStateKeys.COMPLETED_RESULTS);
        for (Class<?> constants : List.of(MateClawStateKeys.class, PlanStateKeys.class)) {
            Map<String,Object> initial = new LinkedHashMap<>();
            for (var field : constants.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers) || field.getType() != String.class || field.getName().endsWith("_NODE")) continue;
                String key = (String) field.get(null);
                initial.put(key, append.contains(key) ? List.of("initial-" + key) : "initial-" + key);
            }
            assertTrue(initial.size() > 10);
            RunState state = new RunState(initial);
            state.applyPatch(Map.of("unrelated_node_result", "first"));
            RunState retained = state.snapshot();
            state.applyPatch(Map.of("unrelated_node_result", "second"));
            initial.forEach((key,value) -> { assertEquals(value, state.value(key).orElseThrow(), key); assertEquals(value, retained.value(key).orElseThrow(), key); });
            initial.forEach((key,value) -> {
                state.applyPatch(Map.of(key, append.contains(key) ? List.of("next") : "next"));
                assertEquals(append.contains(key) ? List.of("initial-" + key, "next") : "next", state.value(key).orElseThrow(), key);
                assertEquals(value, retained.value(key).orElseThrow(), key);
            });
        }
    }
}
