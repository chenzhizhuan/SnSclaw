package vip.mate.architecture;

import org.junit.jupiter.api.Test;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Skill disclosure containers remain isolated and retained in either runtime. */
class SkillStateKeyDoubleRegistrationTest {
    @Test void skillDisclosureSurvivesBothPatchStylesAndReadMergeWrite() {
        for (boolean plan : List.of(false, true)) {
            Set<String> skills = new HashSet<>(Set.of("initial"));
            Set<String> tools = new HashSet<>(Set.of("tool"));
            RunState state = new RunState(Map.of(MateClawStateKeys.LOADED_SKILLS, skills, MateClawStateKeys.ENABLED_EXTENSION_TOOLS, tools));
            state.applyPatch(plan ? Map.of(PlanStateKeys.PLAN_STEPS, List.of("step")) : Map.of(MateClawStateKeys.MESSAGES, List.of("reasoning")));
            RunState retained = state.snapshot(); skills.clear(); tools.clear();
            Set<String> merged = new HashSet<>(state.<Set<String>>value(MateClawStateKeys.LOADED_SKILLS).orElseThrow());
            merged.add("loaded"); state.applyPatch(Map.of(MateClawStateKeys.LOADED_SKILLS, merged)); merged.clear();
            state.applyPatch(plan ? Map.of(PlanStateKeys.COMPLETED_RESULTS, List.of("result")) : Map.of(MateClawStateKeys.PENDING_EVENTS, List.of("event")));
            assertEquals(Set.of("initial", "loaded"), state.value(MateClawStateKeys.LOADED_SKILLS).orElseThrow());
            assertEquals(Set.of("tool"), state.value(MateClawStateKeys.ENABLED_EXTENSION_TOOLS).orElseThrow());
            assertEquals(Set.of("initial"), retained.value(MateClawStateKeys.LOADED_SKILLS).orElseThrow());
            assertEquals(Set.of("tool"), retained.value(MateClawStateKeys.ENABLED_EXTENSION_TOOLS).orElseThrow());
            assertThrows(UnsupportedOperationException.class, () -> state.<Set<String>>value(MateClawStateKeys.LOADED_SKILLS).orElseThrow().clear());
        }
    }
}
