package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.llm.model.ModelProviderEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Exercises provider reasoning policies on the final JSON request. */
class PatchReasoningContentTest {

    // ---------- Fixtures ----------

    private static ModelProviderEntity provider(String id) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        return p;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode message(String role, String text) {
        return JSON.createObjectNode().put("role", role).put("content", text);
    }

    private static ObjectNode user(String text) { return message("user", text); }
    private static ObjectNode system(String text) { return message("system", text); }
    private static ObjectNode assistantPlain(String text) { return message("assistant", text); }

    private static ObjectNode assistantToolCall(String text, String reasoningContent) {
        ObjectNode message = assistantPlain(text);
        message.putArray("tool_calls").addObject().put("id", "call_1").put("type", "function");
        if (reasoningContent != null) message.put("reasoning_content", reasoningContent);
        return message;
    }

    private static ObjectNode request(List<ObjectNode> messages, String user) {
        return request(messages, user, "test-model");
    }

    private static ObjectNode request(List<ObjectNode> messages, String user, String model) {
        ObjectNode request = JSON.createObjectNode().put("model", model);
        request.set("messages", JSON.valueToTree(messages));
        if (user != null) request.put("user", user);
        return request;
    }

    private static ObjectNode rewrite(ObjectNode request, ModelProviderEntity provider) {
        return OpenAiRequestRewriter.rewrite(request, provider, false);
    }

    @BeforeEach
    void clearRelay() {
        AssistantThinkingRelay.clearAll();
        ReasoningContentCache.clear();
    }

    @AfterEach
    void clearRelayAfter() {
        AssistantThinkingRelay.clearAll();
        ReasoningContentCache.clear();
    }

    // ---------- No-relay, no-thinking-mode path ----------

    @Test
    @DisplayName("No relay token + no thinking signals → request content passes through unchanged")
    void noop_whenNoRelayAndNoThinkingMode() {
        ObjectNode req = request(List.of(
                system("sys"),
                user("q1"),
                assistantToolCall("", null)     // no reasoning_content anywhere → not thinking mode
        ), "caller-user-1");

        // model is "test-model" which maps to STANDARD family → requiresReasoningContentPatch returns false
        ObjectNode out = rewrite(req, provider("deepseek"));
        assertEquals(req, out, "no thinking signal → no rebuild");
        assertEquals("caller-user-1", out.path("user").asText(null), "user field untouched");
    }

    @Test
    @DisplayName("Leaked relay token (no entry in map) + no thinking signals → strips token, rebuilds user")
    void stripsLeakedToken_whenNoEntryNoThinking() {
        // Prefix-shaped but not actually stashed — simulates consumer running after
        // producer's finally already discarded. take() returns null; isToken() still true.
        String fakeToken = AssistantThinkingRelay.TOKEN_PREFIX + "orphan-uuid";
        ObjectNode req = request(List.of(
                user("q1"),
                assistantPlain("hi")
        ), fakeToken);

        ObjectNode out = rewrite(req, provider("openai"));
        assertNotSame(req, out, "rebuild expected to strip leaked token");
        assertNull(out.path("user").asText(null), "leaked token must be sanitized to null");
    }

    // ---------- sanitizedUser restoration from RelayEntry ----------

    @Test
    @DisplayName("sanitizedUser is restored from RelayEntry.originalUser")
    void sanitizedUser_restoredFromRelayEntry() {
        List<String> thinkings = List.of("", "in-turn-think");
        String token = AssistantThinkingRelay.stash(thinkings, "original-caller-42");

        ObjectNode req = request(List.of(
                assistantPlain("prior-assistant"),  // i=0, position 0 in thinkings → ""
                user("q1"),
                assistantToolCall("a1", null)        // i=2, position 1 in thinkings → "in-turn-think"
        ), token);

        ObjectNode out = rewrite(req, provider("deepseek"));

        assertEquals("original-caller-42", out.path("user").asText(null), "sanitizedUser must equal entry.originalUser()");
        assertEquals("in-turn-think", out.at("/messages/2/reasoning_content").asText(null),
                "in-turn assistant (i=2 > lastUserIdx=1) should receive the real thinking");
    }

    // ---------- lastUserIdx scope ----------

    @Test
    @DisplayName("DEEPSEEK patchCrossTurn=true: prior-turn assistants get ' ' fallback so multi-turn doesn't 400")
    void crossTurnAssistants_patchedWithSpace_deepseek() {
        // [sys, U1, A1(tool_call, no-rc), U2, A2(tool_call, no-rc)]
        // lastUserIdx = 3 (U2)
        // Relay thinkings: [null for A1, "real-a2" for A2]
        //
        // DeepSeek (since 2026-04) requires reasoning_content on EVERY assistant
        // in the request — prior-turn included. Without patchCrossTurn, A1 stays
        // null and DeepSeek 400s on every multi-turn conversation. With it, A1
        // gets the same " " fallback in-turn assistants get.
        List<String> thinkings = Arrays.asList("", "real-a2");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ObjectNode req = request(List.of(
                system("sys"),
                user("q1"),
                assistantToolCall("a1", null),       // i=2, cross-turn (2 <= 3)
                user("q2"),
                assistantToolCall("a2", null)        // i=4, in-turn (4 > 3)
        ), token);

        ObjectNode out = rewrite(req, provider("deepseek"));

        assertEquals(" ", out.at("/messages/2/reasoning_content").asText(null),
                "cross-turn A1 gets ' ' fallback so DeepSeek thinking-mode validation passes");
        assertEquals("real-a2", out.at("/messages/4/reasoning_content").asText(null),
                "in-turn A2 (i=4 > lastUserIdx=3) receives the real relay value");
    }

    @Test
    @DisplayName("Iterator stays aligned: cross-turn consumes '' positions so in-turn gets correct thinking")
    void iteratorAlignment_acrossCrossTurnAndInTurn() {
        // [U1, A1(no-rc), A2(no-rc), U2, A3(no-rc), A4(no-rc)]
        // lastUserIdx = 3 (U2). Producer extraction order = A1,A2,A3,A4.
        // Relay: ["","" (cross-turn, stripped already), "real-a3", "real-a4"]
        List<String> thinkings = List.of("", "", "real-a3", "real-a4");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ObjectNode req = request(List.of(
                user("q1"),
                assistantToolCall("a1", null),   // i=1 cross-turn
                assistantToolCall("a2", null),   // i=2 cross-turn
                user("q2"),
                assistantToolCall("a3", null),   // i=4 in-turn
                assistantToolCall("a4", null)    // i=5 in-turn
        ), token);

        ObjectNode out = rewrite(req, provider("deepseek"));

        // DEEPSEEK patchCrossTurn=true: cross-turn now also gets ' ' fallback.
        // Iterator alignment is preserved: A1/A2 consume the empty entries '',
        // A3/A4 consume their real values in correct positions.
        assertEquals(" ", out.at("/messages/1/reasoning_content").asText(null), "A1 cross-turn ' ' fallback");
        assertEquals(" ", out.at("/messages/2/reasoning_content").asText(null), "A2 cross-turn ' ' fallback");
        assertEquals("real-a3", out.at("/messages/4/reasoning_content").asText(null), "A3 in-turn gets real-a3 (not real-a4)");
        assertEquals("real-a4", out.at("/messages/5/reasoning_content").asText(null), "A4 in-turn gets real-a4");
    }

    // ---------- FallbackPolicy × emptyFallback ----------

    @Test
    @DisplayName("DEEPSEEK policy: relay empty for in-turn tool_call → reasoning_content gets ' ' fallback")
    void deepseek_relayEmpty_fallsBackToSpace() {
        // 72bd33dc switched DEEPSEEK from emptyFallback=null (force explicit 400)
        // to " " — null kept self-replicating 400s every multi-tool turn that
        // crossed a summarizing boundary. Aligning with KIMI/OPENAI tolerance.
        List<String> thinkings = List.of("");  // one assistant, no real thinking
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ObjectNode req = request(List.of(
                user("q1"),
                assistantToolCall("a1", null)  // in-turn
        ), token);

        ObjectNode out = rewrite(req, provider("deepseek"));

        assertEquals(" ", out.at("/messages/1/reasoning_content").asText(null),
                "DeepSeek: ' ' fallback restores forward progress when relay has no real value");
    }

    @Test
    @DisplayName("KIMI policy: relay empty for in-turn tool_call → ' ' injected (legacy tolerance)")
    void kimi_relayEmpty_injectsSpace() {
        // Kimi path is triggered by the model-family check; use model name that maps to KIMI_THINKING.
        List<String> thinkings = List.of("");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        List<ObjectNode> msgs = List.of(
                user("q1"),
                assistantToolCall("a1", null)
        );
        ObjectNode req = request(msgs, token, "kimi-k2.5");

        ObjectNode out = rewrite(req, provider("kimi-cn"));

        assertEquals(" ", out.at("/messages/1/reasoning_content").asText(null),
                "Kimi tolerates ' ' — preserve legacy behavior");
    }

    @Test
    @DisplayName("Unknown provider uses DEFAULT policy: ' ' injected (legacy tolerance, not noop)")
    void defaultPolicy_unknownProvider_injectsSpace() {
        List<String> thinkings = List.of("");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        // Use a model that triggers requiresReasoningContentPatch so thinking mode is active
        List<ObjectNode> msgs = List.of(
                user("q1"),
                assistantToolCall("a1", null)
        );
        ObjectNode req = request(msgs, token, "deepseek-reasoner");

        ObjectNode out = rewrite(req, provider("custom-gateway"));

        assertEquals(" ", out.at("/messages/1/reasoning_content").asText(null),
                "DEFAULT keeps legacy ' ' for unrecognized providers — avoid regressing self-hosted backends");
    }

    // ---------- FallbackPolicy × patchNonToolCall ----------

    @Test
    @DisplayName("DEEPSEEK policy patches non-tool_call in-turn assistants too (patchNonToolCall=true)")
    void deepseek_patchesNonToolCallAssistant() {
        List<String> thinkings = List.of("thinking-for-plain");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ObjectNode req = request(List.of(
                user("q1"),
                assistantPlain("plain answer")  // no tool_calls
        ), token);

        ObjectNode out = rewrite(req, provider("deepseek"));

        assertEquals("thinking-for-plain", out.at("/messages/1/reasoning_content").asText(null),
                "DeepSeek contract requires reasoning_content even on non-tool_call assistants when in thinking mode");
    }

    @Test
    @DisplayName("KIMI policy leaves non-tool_call assistants alone (patchNonToolCall=false)")
    void kimi_skipsNonToolCallAssistant() {
        List<String> thinkings = List.of("would-not-be-used");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        List<ObjectNode> msgs = List.of(
                user("q1"),
                assistantPlain("plain answer")
        );
        ObjectNode req = request(msgs, token, "kimi-k2.5");

        ObjectNode out = rewrite(req, provider("kimi-cn"));

        assertNull(out.at("/messages/1/reasoning_content").asText(null),
                "Kimi only patches tool_call assistants; plain assistants are untouched");
    }

    // ---------- Preserve pre-existing real values ----------

    @Test
    @DisplayName("Assistant that already has real reasoning_content is left alone")
    void existingRealValue_preserved() {
        List<String> thinkings = List.of("would-overwrite");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ObjectNode req = request(List.of(
                user("q1"),
                assistantToolCall("a1", "pre-existing-real-thinking")  // already has a value
        ), token);

        ObjectNode out = rewrite(req, provider("deepseek"));

        assertEquals("pre-existing-real-thinking", out.at("/messages/1/reasoning_content").asText(null),
                "non-blank existing reasoning_content must not be overwritten by relay");
    }

    // ---------- Edge: empty messages ----------

    @Test
    @DisplayName("Empty messages list: no-op, preserves request content")
    void emptyMessages_noop() {
        ObjectNode req = request(List.of(), null);
        assertEquals(req, rewrite(req, provider("deepseek")));
    }

    @Test
    @DisplayName("Null messages: no-op, preserves request content")
    void nullMessages_noop() {
        ObjectNode req = request(null, null, "m");
        assertEquals(req, rewrite(req, provider("deepseek")));
    }

    // ---------- Fewer relay entries than assistants: defensive policy fallback ----------

    @Test
    @DisplayName("Relay shorter than assistant count: extra in-turn assistants fall back to policy")
    void relayShorterThanAssistants_fallsBack() {
        // Producer extracted 1 entry but there are 2 in-turn tool_call assistants
        // (e.g. one was added after relay stash — shouldn't happen but be defensive).
        List<String> thinkings = List.of("real-1");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ObjectNode req = request(new ArrayList<>(List.of(
                user("q1"),
                assistantToolCall("a1", null),
                assistantToolCall("a2", null)
        )), token);

        ObjectNode out = rewrite(req, provider("deepseek"));

        assertEquals("real-1", out.at("/messages/1/reasoning_content").asText(null));
        assertEquals(" ", out.at("/messages/2/reasoning_content").asText(null),
                "DEEPSEEK with emptyFallback=' ' (post-72bd33dc): missing real values get the same tolerant fallback");
    }

    // ---------- patchCrossTurn policy (2026-04-29) ----------

    @Test
    @DisplayName("KIMI / OPENAI / DEFAULT do NOT patch cross-turn — only DEEPSEEK does")
    void crossTurnPatching_isDeepseekOnly() {
        // Same shape as crossTurnAssistants_patchedWithSpace_deepseek but with
        // KIMI provider — KIMI's contract resets thinking across user turns,
        // so prior-turn assistants must remain null. Pinning this here protects
        // against accidentally flipping patchCrossTurn=true for all providers.
        List<String> thinkings = Arrays.asList("", "real-a2");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ObjectNode req = request(List.of(
                user("q1"),
                assistantToolCall("a1", null),       // i=1, cross-turn (1 <= 2)
                user("q2"),
                assistantToolCall("a2", null)        // i=3, in-turn (3 > 2)
        ), token);

        ObjectNode out = rewrite(req, provider("kimi-cn"));

        assertNull(out.at("/messages/1/reasoning_content").asText(null),
                "KIMI does not patch cross-turn — thinking resets across user turns");
        assertEquals("real-a2", out.at("/messages/3/reasoning_content").asText(null),
                "KIMI in-turn assistants still receive their relay value");
    }

    @Test
    @DisplayName("DEEPSEEK cross-turn assistant without tool_calls also patched (patchNonToolCall=true)")
    void crossTurnPlainAssistant_patchedForDeepseek() {
        // Plain prior-turn text assistant (no tool_calls): without the
        // patchNonToolCall guard, this would still be skipped. DEEPSEEK has
        // both patchNonToolCall=true AND patchCrossTurn=true, so it should
        // get the ' ' fallback. This is the most common production case
        // since plain assistants dominate IM channel history.
        List<String> thinkings = Arrays.asList("", "");
        String token = AssistantThinkingRelay.stash(thinkings, null);

        ObjectNode req = request(List.of(
                user("q1"),
                assistantPlain("plain a1"),  // no tool_calls
                user("q2"),
                assistantPlain("plain a2")
        ), token);

        ObjectNode out = rewrite(req, provider("deepseek"));

        assertEquals(" ", out.at("/messages/1/reasoning_content").asText(null),
                "DEEPSEEK plain prior-turn assistant gets ' ' so request validates");
        assertEquals(" ", out.at("/messages/3/reasoning_content").asText(null),
                "DEEPSEEK plain in-turn assistant gets ' ' as before");
    }

    // ---------- XIAOMI_MIMO policy + cross-turn cache replay ----------

    @Test
    @DisplayName("XIAOMI_MIMO cross-turn tool_call: cache hit replays real reasoning_content")
    void xiaomiMimoCrossTurn_replaysCachedReasoning() {
        // Prior turn produced a tool_call with real thinking; NodeStreamingChatHelper
        // stored it in the cache keyed by tool_call_id. On the next turn, the same
        // assistant message is replayed as history with reasoning_content=null —
        // resolveCrossTurnReasoning must fetch the cached value before falling
        // back to the policy's empty " ".
        ReasoningContentCache.store(List.of("call_1"), "real-prior-thinking");

        // Empty relay: no in-turn thinking (current turn hasn't produced one yet).
        String token = AssistantThinkingRelay.stash(List.of(""), null);

        ObjectNode req = request(List.of(
                user("q1"),
                assistantToolCall("a1", null),   // i=1, cross-turn (1 <= 2), tool_call id="call_1"
                user("q2")                       // i=2, lastUserIdx
        ), token);

        ObjectNode out = rewrite(req, provider("xiaomi-mimo"));

        assertEquals("real-prior-thinking", out.at("/messages/1/reasoning_content").asText(null),
                "XIAOMI_MIMO cross-turn tool_call must replay cached reasoning_content over the ' ' fallback");
    }

    @Test
    @DisplayName("XIAOMI_MIMO cross-turn tool_call: cache miss falls back to ' '")
    void xiaomiMimoCrossTurnCacheMiss_fallsBackToSpace() {
        // No cache entry for call_1 — the multi-turn path must still validate by
        // injecting the policy's emptyFallback so MiMo doesn't 400.
        String token = AssistantThinkingRelay.stash(List.of(""), null);

        ObjectNode req = request(List.of(
                user("q1"),
                assistantToolCall("a1", null),   // i=1, cross-turn, no cache entry
                user("q2")
        ), token);

        ObjectNode out = rewrite(req, provider("xiaomi-mimo"));

        assertEquals(" ", out.at("/messages/1/reasoning_content").asText(null),
                "XIAOMI_MIMO cross-turn cache miss falls back to ' ' so the request still validates");
    }

    @Test
    @DisplayName("XIAOMI_MIMO plain cross-turn assistant (no tool_calls) also patched via patchNonToolCall=true")
    void xiaomiMimoCrossTurnPlainAssistant_patchedWithSpace() {
        // XIAOMI_MIMO mirrors DEEPSEEK: patchNonToolCall=true means even plain
        // text assistants in prior turns must carry reasoning_content. Cache
        // can't help here (no tool_call_ids to key on) — fallback is " ".
        String token = AssistantThinkingRelay.stash(List.of("", ""), null);

        ObjectNode req = request(List.of(
                user("q1"),
                assistantPlain("plain a1"),  // i=1, cross-turn, no tool_calls
                user("q2"),
                assistantPlain("plain a2")   // i=3, in-turn, no tool_calls
        ), token);

        ObjectNode out = rewrite(req, provider("xiaomi-mimo"));

        assertEquals(" ", out.at("/messages/1/reasoning_content").asText(null),
                "XIAOMI_MIMO plain prior-turn assistant gets ' ' (patchNonToolCall=true + patchCrossTurn=true)");
        assertEquals(" ", out.at("/messages/3/reasoning_content").asText(null),
                "XIAOMI_MIMO plain in-turn assistant gets ' ' (patchNonToolCall=true)");
    }
}
