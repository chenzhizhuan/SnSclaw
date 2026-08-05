package vip.mate.agent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.agent.ContentKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the routing decision for {@code STREAMED_CONTENT} deltas emitted by
 * {@link StateGraphReActAgent} during the structured-stream loop.
 *
 * <p>Background — issue #120 follow-up: the original fix routed every per-iteration
 * {@code STREAMED_CONTENT} via {@link AgentService.StreamDelta#segmentOnly}, which
 * keeps the persisted {@code mate_message.content} clean of mid-loop "我来…" preamble.
 * That broke the evidence-insufficient terminal turn though
 * ({@code ReasoningNode.java:617}): there {@code FINAL_ANSWER} is just a short
 * "[证据不足]" warning while {@code STREAMED_CONTENT} carries the actual answer body
 * the user/UI need to see. Single-segment renderers (copy / TTS / history reload)
 * read {@code content}, not segments, so {@code segmentOnly} for that case would
 * shrink the visible message to the warning alone.
 *
 * <p>The helper is also the single assignment point of {@link ContentKind}: the
 * graph knows definitively whether the completion carried tool calls and whether
 * any observation preceded the text this turn, so downstream consumers read the
 * tag instead of re-deriving the category from stream structure.
 */
class StateGraphReActAgentStreamedContentDeltaTest {

    @Test
    @DisplayName("iteration 0 with tool calls → segmentOnly + PRE_TOOL_NARRATION — provisional rehearsal")
    void preToolNarration_taggedProvisional() {
        AgentService.StreamDelta d = StateGraphReActAgent.streamedContentDelta(
                /* isFinalAnswerTurn */ false,
                /* carriesToolCalls */ true,
                /* iteration */ 0,
                "先加载 skill，然后逐个查询。");

        assertTrue(d.persistenceOnly(),
                "segmentOnly implies persistenceOnly — no re-broadcast (NodeStreamingChatHelper already pushed it)");
        assertTrue(d.segmentOnly(),
                "intermediate narration MUST set segmentOnly so content.append is skipped");
        assertEquals(ContentKind.PRE_TOOL_NARRATION, d.kind(),
                "text alongside tool calls with zero observations this turn is not grounded — provisional");
        assertEquals("先加载 skill，然后逐个查询。", d.content());
    }

    @Test
    @DisplayName("iteration ≥1 narration → segmentOnly + GROUNDED_NARRATION even when the completion issues more tool calls")
    void postObservationNarration_taggedGrounded() {
        AgentService.StreamDelta d = StateGraphReActAgent.streamedContentDelta(
                false, /* carriesToolCalls */ true, /* iteration */ 1, "第一间空闲，继续查下一间。");

        assertTrue(d.segmentOnly());
        assertEquals(ContentKind.GROUNDED_NARRATION, d.kind(),
                "an observation already happened this turn — the narration is grounded and never replaced");
    }

    @Test
    @DisplayName("iteration 0 without tool calls (non-terminal) → GROUNDED_NARRATION — conservative, never replaced")
    void noToolCallCompletion_taggedGrounded() {
        AgentService.StreamDelta d = StateGraphReActAgent.streamedContentDelta(
                false, /* carriesToolCalls */ false, /* iteration */ 0, "narrative without tools");

        assertEquals(ContentKind.GROUNDED_NARRATION, d.kind(),
                "a completion that closed without tool calls has no later observation to defer to — keep it");
    }

    @Test
    @DisplayName("evidence-insufficient terminal turn (FINAL_ANSWER set) → persistOnly + FINAL_ANSWER kind")
    void evidenceInsufficientFinalTurn_routedToPersistOnly() {
        // Regression: STREAMED_CONTENT here is the rejected answer body; FINAL_ANSWER
        // is only the "[证据不足]" warning. Persisting the streamed body keeps
        // mate_message.content readable through single-segment renderers.
        AgentService.StreamDelta d = StateGraphReActAgent.streamedContentDelta(
                /* isFinalAnswerTurn */ true, false, 2,
                "The answer is 42. References: [1] [2] [3].");

        assertTrue(d.persistenceOnly(),
                "persistOnly suppresses re-broadcast — content was already streamed live");
        assertFalse(d.segmentOnly(),
                "persistOnly variant MUST NOT set segmentOnly — content.append needs to run");
        assertEquals(ContentKind.FINAL_ANSWER, d.kind());
        assertEquals("The answer is 42. References: [1] [2] [3].", d.content());
    }

    @Test
    @DisplayName("finalAnswer factory carries FINAL_ANSWER kind in both broadcast flavors")
    void finalAnswerFactory_taggedFinal() {
        AgentService.StreamDelta streamed = AgentService.StreamDelta.finalAnswer("done", true);
        assertTrue(streamed.persistenceOnly(), "already-streamed answer must not re-broadcast");
        assertEquals(ContentKind.FINAL_ANSWER, streamed.kind());

        AgentService.StreamDelta fresh = AgentService.StreamDelta.finalAnswer("done", false);
        assertFalse(fresh.persistenceOnly(), "un-streamed answer still broadcasts");
        assertEquals(ContentKind.FINAL_ANSWER, fresh.kind());
    }

    @Test
    @DisplayName("legacy factories keep kind null — pre-tag producers stay distinguishable")
    void legacyFactories_kindNull() {
        assertNull(AgentService.StreamDelta.segmentOnly("x", null).kind());
        assertNull(AgentService.StreamDelta.persistOnly("x", null).kind());
        assertNull(new AgentService.StreamDelta("x", null).kind());
    }

    @Test
    @DisplayName("both flavors leave thinking null — STREAMED_CONTENT routing only carries text content")
    void thinkingFieldNeverSet() {
        assertNull(StateGraphReActAgent.streamedContentDelta(false, true, 0, "x").thinking());
        assertNull(StateGraphReActAgent.streamedContentDelta(true, false, 1, "x").thinking());
    }

    @Test
    @DisplayName("kind-carrying delta is followed by a segment_kind broadcast event")
    void kindDeltaEmitsSegmentKindEvent() {
        java.util.List<AgentService.StreamDelta> deltas = new java.util.ArrayList<>();
        StateGraphReActAgent.addWithKindEvent(deltas,
                StateGraphReActAgent.streamedContentDelta(false, true, 0, "先查询。"));

        assertEquals(2, deltas.size());
        AgentService.StreamDelta event = deltas.get(1);
        assertTrue(event.isEvent());
        assertEquals("segment_kind", event.eventType());
        assertEquals("pre_tool_narration", event.eventData().get("kind"));
    }

    @Test
    @DisplayName("untagged delta emits no segment_kind event")
    void untaggedDeltaEmitsNoEvent() {
        java.util.List<AgentService.StreamDelta> deltas = new java.util.ArrayList<>();
        StateGraphReActAgent.addWithKindEvent(deltas, AgentService.StreamDelta.segmentOnly("x", null));

        assertEquals(1, deltas.size());
        assertFalse(deltas.get(0).isEvent());
    }
}
