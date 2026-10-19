package vip.mate.agent.runtime.plan;

import static org.junit.jupiter.api.Assertions.*;

import static vip.mate.agent.graph.plan.state.PlanStateKeys.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

import org.junit.jupiter.api.Test;

import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.agent.runtime.state.RunStep;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class ExplicitPlanRuntimeTest {
    @Test
    void executesStepsReplansAndEvaluatesGoalInBusinessOrder() {
        AtomicInteger steps = new AtomicInteger();
        var runtime =
                new ExplicitPlanRuntime(
                        new ExplicitPlanRuntime.Policies(
                                state -> Map.of("generation", state.value("generation", 0) + 1),
                                state ->
                                        state.value("generation", 0) == 3
                                                ? PLAN_SUMMARY_NODE
                                                : STEP_EXECUTION_NODE,
                                state -> Map.of("step", steps.incrementAndGet()),
                                state ->
                                        state.value("step", 0) == 1
                                                ? STEP_EXECUTION_NODE
                                                : state.value("step", 0) == 2
                                                        ? PLAN_GENERATION_NODE
                                                        : PLAN_SUMMARY_NODE,
                                state -> Map.of(FINAL_SUMMARY, "answer"),
                                state -> Map.of(DIRECT_ANSWER, "direct"),
                                state ->
                                        state.value("goal", 0) == 0
                                                ? GOAL_EVALUATION_NODE
                                                : ExplicitPlanRuntime.END,
                                state -> Map.of("goal", state.value("goal", 0) + 1),
                                state -> PLAN_GENERATION_NODE),
                        20);
        List<RunStep> output = runtime.stream(Map.of()).collectList().block();
        assertNotNull(output);
        assertEquals(
                List.of(
                        PLAN_GENERATION_NODE,
                        STEP_EXECUTION_NODE,
                        STEP_EXECUTION_NODE,
                        PLAN_GENERATION_NODE,
                        STEP_EXECUTION_NODE,
                        PLAN_SUMMARY_NODE,
                        GOAL_EVALUATION_NODE,
                        PLAN_GENERATION_NODE,
                        PLAN_SUMMARY_NODE),
                output.stream().map(RunStep::node).toList());
        assertEquals(3, steps.get());
        assertEquals(1, output.getLast().state().value("goal", 0));
        assertFalse(output.getFirst().state().value("step").isPresent());
    }

    @Test
    void directAnswersSkipStepsAndEachSubscriptionStartsFreshScopedState() {
        AtomicInteger forbidden = new AtomicInteger();
        var runtime =
                new ExplicitPlanRuntime(
                        new ExplicitPlanRuntime.Policies(
                                state -> {
                                    assertEquals("own", ChatOriginHolder.get().conversationId());
                                    return Map.of("round", state.value("round", 0) + 1);
                                },
                                state -> DIRECT_ANSWER_NODE,
                                state -> {
                                    forbidden.incrementAndGet();
                                    return Map.of();
                                },
                                state -> ExplicitPlanRuntime.END,
                                state -> Map.of(),
                                state -> Map.of(DIRECT_ANSWER, "direct"),
                                state -> ExplicitPlanRuntime.END,
                                state -> Map.of(),
                                state -> ExplicitPlanRuntime.END),
                        5);
        var stream =
                runtime.stream(Map.of(CHAT_ORIGIN, ChatOrigin.EMPTY.withConversationId("own")));
        for (int i = 0; i < 2; i++) {
            List<RunStep> result = stream.collectList().block();
            assertEquals(
                    List.of(PLAN_GENERATION_NODE, DIRECT_ANSWER_NODE),
                    result.stream().map(RunStep::node).toList());
            assertEquals(1, result.getLast().state().value("round", 0));
        }
        assertEquals(0, forbidden.get());
        assertEquals(ChatOrigin.EMPTY, ChatOriginHolder.get());
    }

    @Test
    void finiteCeilingFailsExplicitlyBeforeExecutingAnotherStep() {
        AtomicInteger calls = new AtomicInteger();
        var runtime =
                new ExplicitPlanRuntime(
                        new ExplicitPlanRuntime.Policies(
                                state -> Map.of(),
                                state -> STEP_EXECUTION_NODE,
                                state -> {
                                    calls.incrementAndGet();
                                    return Map.of();
                                },
                                state -> STEP_EXECUTION_NODE,
                                state -> Map.of(),
                                state -> Map.of(),
                                state -> ExplicitPlanRuntime.END,
                                state -> Map.of(),
                                state -> ExplicitPlanRuntime.END),
                        3);
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> runtime.stream(Map.of()).collectList().block());
        assertTrue(failure.getMessage().contains("ceiling"));
        assertEquals(2, calls.get());
    }
}
