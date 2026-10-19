package vip.mate.architecture;

import org.junit.jupiter.api.Test;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.goal.model.GoalEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Goal and origin state remains available through both runtime patch styles. */
class GoalStateKeyDoubleRegistrationTest {
    @Test void allGoalKeysAndOriginSurviveReActAndPlanPatches() {
        for (boolean plan : List.of(false, true)) {
            GoalEntity goal = new GoalEntity(); goal.setTitle("objective");
            Map<String,Object> evaluation = new HashMap<>(Map.of("score", 0.5));
            ChatOrigin origin = ChatOrigin.EMPTY.withConversationId("conversation");
            RunState state = new RunState(Map.of(MateClawStateKeys.ACTIVE_GOAL, goal,
                    MateClawStateKeys.GOAL_EVALUATION_RESULT, evaluation,
                    MateClawStateKeys.GOAL_FOLLOWUP_INJECTED, true,
                    MateClawStateKeys.GOAL_FOLLOWUP_PROMPT, "continue",
                    MateClawStateKeys.GOAL_EVALUATED_THIS_RUN, true,
                    MateClawStateKeys.GOAL_HARD_CONTINUATION_COUNT, 2,
                    MateClawStateKeys.CHAT_ORIGIN, origin));
            state.applyPatch(plan ? Map.of(PlanStateKeys.PLAN_STEPS, List.of("step")) : Map.of(MateClawStateKeys.MESSAGES, List.of("reasoning")));
            RunState retained = state.snapshot(); evaluation.put("score", 1.0); goal.setTitle("external");
            state.applyPatch(plan ? Map.of(PlanStateKeys.COMPLETED_RESULTS, List.of("result")) : Map.of(MateClawStateKeys.PENDING_EVENTS, List.of("event")));
            for (RunState checked : List.of(state, retained)) {
                assertEquals("objective", checked.value(MateClawStateKeys.ACTIVE_GOAL, GoalEntity.class).orElseThrow().getTitle());
                assertEquals(Map.of("score", 0.5), checked.value(MateClawStateKeys.GOAL_EVALUATION_RESULT).orElseThrow());
                assertEquals(true, checked.value(MateClawStateKeys.GOAL_FOLLOWUP_INJECTED).orElseThrow());
                assertEquals("continue", checked.value(MateClawStateKeys.GOAL_FOLLOWUP_PROMPT).orElseThrow());
                assertEquals(true, checked.value(MateClawStateKeys.GOAL_EVALUATED_THIS_RUN).orElseThrow());
                assertEquals(2, checked.value(MateClawStateKeys.GOAL_HARD_CONTINUATION_COUNT).orElseThrow());
                assertEquals(origin, checked.value(MateClawStateKeys.CHAT_ORIGIN).orElseThrow());
            }
        }
    }
}
