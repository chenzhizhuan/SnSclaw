package vip.mate.agent.runtime.state;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import vip.mate.agent.GraphEventPublisher;
import vip.mate.goal.model.GoalEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class RunStateTest {
    @Test
    void eventPayloadAndMessageMetadataAreDetached() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("value", "before");
        var event = new GraphEventPublisher.GraphEvent("update", payload, 1L);
        var message =
                AssistantMessage.builder()
                        .content("answer")
                        .properties(Map.of("nested", payload))
                        .build();
        RunState state =
                new RunState(
                        Map.of("pending_events", List.of(event), "messages", List.of(message)));
        payload.put("value", "after");
        var retainedEvent =
                (GraphEventPublisher.GraphEvent)
                        state.value("pending_events", List.class).orElseThrow().get(0);
        var retainedMessage =
                (AssistantMessage) state.value("messages", List.class).orElseThrow().get(0);
        assertEquals("before", retainedEvent.data().get("value"));
        assertEquals(
                "before", ((Map<?, ?>) retainedMessage.getMetadata().get("nested")).get("value"));
    }

    @Test
    void appendsKnownHistoryKeysAndReplacesOtherValues() {
        RunState state =
                new RunState(
                        Map.of("messages", List.of("one"), "observation_history", List.of("old")));
        state.applyPatch(
                Map.of(
                        "messages",
                        List.of("two"),
                        "pending_events",
                        "event",
                        "completed_results",
                        List.of("result"),
                        "observation_history",
                        List.of("new")));
        assertEquals(List.of("one", "two"), state.value("messages", List.of()));
        assertEquals(List.of("event"), state.value("pending_events", List.of()));
        assertEquals(List.of("result"), state.value("completed_results", List.of()));
        assertEquals(List.of("new"), state.value("observation_history", List.of()));
    }

    @Test
    void explicitReplacementAndNullClearWorkForAppendKeys() {
        RunState state = new RunState(Map.of("messages", List.of("old")));
        state.replace("messages", List.of("new"));
        assertEquals(List.of("new"), state.value("messages", List.of()));
        Map<String, Object> patch = new HashMap<>();
        patch.put("messages", null);
        state.applyPatch(patch);
        assertEquals(List.of("new"), state.value("messages", List.of()));
        state.replace("messages", null);
        assertTrue(state.value("messages").isEmpty());
    }

    @Test
    void appendPatchPreservesVerifiedMergeContract() {
        for (Object incoming : new Object[] {"single", List.of("list"), new String[] {"array"}}) {
            String added =
                    incoming instanceof String text
                            ? text
                            : incoming instanceof List<?> list
                                    ? (String) list.get(0)
                                    : ((String[]) incoming)[0];
            RunState normal = new RunState(Map.of("messages", List.of("old")));
            RunState malformed = new RunState(Map.of("messages", "previous scalar"));
            normal.applyPatch(Map.of("messages", incoming));
            malformed.applyPatch(Map.of("messages", incoming));
            assertEquals(List.of("old", added), normal.value("messages", List.of()));
            assertEquals(List.of(added), malformed.value("messages", List.of()));
        }
        RunState normal = new RunState(Map.of("messages", List.of("old")));
        normal.applyPatch(Map.of("messages", List.of()));
        assertEquals(List.of("old"), normal.value("messages", List.of()));
        Map<String, Object> patch = new HashMap<>();
        patch.put("messages", null);
        normal.applyPatch(patch);
        assertEquals(List.of("old"), normal.value("messages", List.of()));
        RunState malformed = new RunState(Map.of("messages", "previous scalar"));
        malformed.applyPatch(patch);
        assertEquals("previous scalar", malformed.value("messages", ""));
        malformed.applyPatch(Map.of("messages", List.of()));
        assertEquals(List.of(), malformed.value("messages", List.of()));
    }

    @Test
    void replacementNullAndImmutableConstructorPreserveVerifiedContract() {
        Map<String, Object> input = Map.of("messages", List.of("old"), "answer", "old");
        RunState state = new RunState(input);
        state.applyPatch(Map.of("messages", List.of("new"), "answer", "new"));
        assertEquals(List.of("old", "new"), state.value("messages", List.of()));
        assertEquals("new", state.value("answer", ""));
        Map<String, Object> patch = new HashMap<>();
        patch.put("answer", null);
        state.applyPatch(patch);
        assertTrue(state.value("answer").isEmpty());
        assertEquals(Map.of("messages", List.of("old"), "answer", "old"), input);
    }

    @Test
    void mutableGoalAndPlanStepsAreDetachedAcrossInputsReadsAndSnapshots() {
        GoalEntity goal = new GoalEntity();
        goal.setTitle("initial");
        goal.setTurnsUsed(1);
        List<String> steps = new ArrayList<>(List.of("first"));
        RunState first = new RunState(Map.of("active_goal", goal, "plan_steps", steps));
        RunState second = new RunState(Map.of("active_goal", goal, "plan_steps", steps));
        RunState snapshot = first.snapshot();
        goal.setTitle("external");
        steps.add("external");
        GoalEntity read = first.value("active_goal", GoalEntity.class).orElseThrow();
        assertEquals("initial", read.getTitle());
        read.setTurnsUsed(9);
        assertEquals(1, first.value("active_goal", GoalEntity.class).orElseThrow().getTurnsUsed());
        assertEquals(1, second.value("active_goal", GoalEntity.class).orElseThrow().getTurnsUsed());
        assertEquals(
                "initial",
                snapshot.value("active_goal", GoalEntity.class).orElseThrow().getTitle());
        assertEquals(List.of("first"), snapshot.value("plan_steps", List.of()));
    }

    @Test
    void nestedInputAndReturnedValuesCannotMutateOwnedState() {
        List<String> values = new ArrayList<>(List.of("one"));
        Map<String, Object> input = new HashMap<>();
        input.put("nested", Map.of("values", values));
        RunState state = new RunState(input);
        values.add("outside");
        input.clear();
        Map<?, ?> nested = state.value("nested", Map.class).orElseThrow();
        assertEquals(List.of("one"), nested.get("values"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((List<?>) nested.get("values")).clear());
    }

    @Test
    void snapshotRetainsHistoryAfterFurtherPatches() {
        RunState state = new RunState(Map.of("messages", List.of("one")));
        RunStep step = new RunStep("reasoning", state.snapshot());
        state.applyPatch(Map.of("messages", List.of("two")));
        assertEquals(List.of("one"), step.state().value("messages", List.of()));
        assertEquals(List.of("one", "two"), state.value("messages", List.of()));
    }

    @Test
    void independentConcurrentRunsNeverShareHistory() {
        Map<String, Object> seed = Map.of("messages", new ArrayList<>(List.of("seed")));
        RunState first = new RunState(seed);
        RunState second = new RunState(seed);
        CompletableFuture.allOf(
                        CompletableFuture.runAsync(
                                () -> first.applyPatch(Map.of("messages", List.of("first")))),
                        CompletableFuture.runAsync(
                                () -> second.applyPatch(Map.of("messages", List.of("second")))))
                .join();
        assertEquals(List.of("seed", "first"), first.value("messages", List.of()));
        assertEquals(List.of("seed", "second"), second.value("messages", List.of()));
        assertEquals(List.of("seed"), seed.get("messages"));
    }

    @Test
    void runStepVersionsCurrentSnapshotsAndRejectsUnknownFormats() {
        RunState state = new RunState(Map.of("messages", List.of("initial")));
        RunStep emitted = new RunStep("reasoning", state);
        assertEquals(1, emitted.snapshotVersion());
        RunStep restored = new RunStep(emitted.snapshotVersion(), emitted.node(), emitted.state());
        state.applyPatch(Map.of("messages", List.of("later")));
        assertEquals(List.of("initial"), restored.state().value("messages", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RunStep(2, "reasoning", state));
        assertThrows(IllegalArgumentException.class, () -> new RunStep(0, "reasoning", state));
    }

    @Test
    void typedLookupAndDefaultMatchBusinessAccessorContract() {
        RunState state = new RunState(Map.of("count", 3));
        assertEquals(3, state.value("count", 0));
        assertEquals("fallback", state.value("missing", "fallback"));
        assertTrue(state.value("count", String.class).isEmpty());
        assertEquals(3, state.value("count", Integer.class).orElseThrow());
    }
}
