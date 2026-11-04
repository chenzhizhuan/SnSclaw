package vip.mate.agent.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression coverage for reasoning loops, extended budgets, and tool transitions. */
class NodeStreamingChatHelperThinkingCapTest {

    private ChatStreamTracker streamTracker;

    @BeforeEach
    void setUp() {
        streamTracker = mock(ChatStreamTracker.class);
        when(streamTracker.isStopRequested(any())).thenReturn(false);
    }

    private static Prompt smallPrompt() {
        return new Prompt(List.of(new UserMessage("hi")));
    }

    private static ChatModel singleChunkModel(AssistantMessage msg) {
        Generation gen = new Generation(msg, ChatGenerationMetadata.NULL);
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResults()).thenReturn(List.of(gen));
        when(resp.getResult()).thenReturn(gen);
        when(resp.getMetadata()).thenReturn(null);
        ChatModel m = mock(ChatModel.class);
        when(m.stream(any(Prompt.class))).thenReturn(Flux.just(resp));
        return m;
    }

    @Test
    void lengthFinishIsRetainedEvenWhenPartialContentWasEmitted() {
        var msg = AssistantMessage.builder().content("{\"decision\":\"ACCEPT\",")
                .properties(Map.of("reasoningContent", "Reviewing fifteen forecast rows")).build();
        var metadata = ChatGenerationMetadata.builder().finishReason("length").build();
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(msg, metadata)))));
        var result = new NodeStreamingChatHelper(streamTracker)
                .streamCallSilent(model, smallPrompt(), "c", "finalization_check");
        assertEquals("length", result.assistantMessage().getMetadata().get("generationFinishReason"));
        assertEquals(msg.getText(), result.text());
        assertFalse(result.partial(), "Do not change live chunk/continuation semantics when retaining finish metadata");
    }

    @Test
    @DisplayName("thinking >= cap + tool_call in same chunk: cap must NOT trigger; tool_call survives")
    void thinkingAndToolCallSameChunk_doesNotTripSoftCap() {
        // Build a single chunk that carries 40k thinking (well above the
        // 32k cap) AND a tool call. With the buggy ordering this would
        // dispose before accumulateToolCalls runs and the helper would
        // return a partial "thinking_only_no_content" result.
        String hugeThinking = "x".repeat(40_000);
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "id-1", "function", "search", "{\"q\":\"foo\"}");
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc))
                .properties(Map.of("reasoningContent", hugeThinking))
                .build();

        ChatModel m = singleChunkModel(msg);
        var helper = new NodeStreamingChatHelper(streamTracker);

        var result = helper.streamCall(m, smallPrompt(), "conv-thinking-tc", "reasoning");

        assertTrue(result.hasToolCalls(),
                "Tool call accompanying huge thinking in the same chunk must survive");
        assertEquals(1, result.toolCalls().size());
        assertEquals("search", result.toolCalls().get(0).name());
        assertFalse(result.partial(),
                "Result must not be marked partial when a tool_call was observed in the same chunk");
        assertNotEquals("thinking_only_no_content", result.errorMessage(),
                "Soft cap must not fire when the chunk carrying huge thinking also carried a tool call");
    }

    @Test
    @DisplayName("thinking >= cap with NO tool_call and NO content: cap fires, result is partial+thinking_only_no_content")
    void thinkingOnlyNoContent_capFires() {
        // Symmetric positive case: confirms the cap still triggers in the
        // genuine "深度思考 ... never finishes" scenario the cap was added for.
        String hugeThinking = "y".repeat(40_000);
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .properties(Map.of("reasoningContent", hugeThinking))
                .build();

        ChatModel m = singleChunkModel(msg);
        var helper = new NodeStreamingChatHelper(streamTracker);

        var result = helper.streamCall(m, smallPrompt(), "conv-thinking-only", "reasoning");

        assertFalse(result.hasToolCalls());
        assertTrue(result.partial(), "Cap should mark the result as partial");
        assertEquals("thinking_only_no_content", result.errorMessage());
        assertEquals(hugeThinking, result.thinking(),
                "Thinking transcript is preserved so the UI can show it in a collapse panel");
    }
    @Test
    void diverseLongThinkingCanReachAnswerInLaterChunk() {
        assertLongThinkingProgress(AssistantMessage.builder().content("Finished analysis").build());
    }

    @Test
    void diverseLongThinkingCanReachToolInLaterChunk() {
        assertLongThinkingProgress(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("later", "function", "search", "{}"))).build());
    }

    private void assertLongThinkingProgress(AssistantMessage progress) {
        StringBuilder reasoning = new StringBuilder();
        for (int i = 0; i < 1400; i++) {
            reasoning.append("Consider case ").append(i).append(": compare evidence ")
                    .append(i * 17).append(" with alternative ").append(i * 31).append(".\n");
        }
        assertTrue(reasoning.length() > 32768);
        var thinking = AssistantMessage.builder().content("")
                .properties(Map.of("reasoningContent", reasoning.toString())).build();
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(thinking))),
                new ChatResponse(List.of(new Generation(progress)))));
        var result = new NodeStreamingChatHelper(streamTracker)
                .streamCall(model, smallPrompt(), "conv-long-valid", "reasoning");
        assertFalse(result.partial(), "Diverse reasoning must be allowed to finish");
        assertEquals(reasoning.toString(), result.thinking());
        assertEquals(progress.getText(), result.text());
        assertEquals(progress.hasToolCalls(), result.hasToolCalls());
    }

    @Test
    void diverseThinkingStillHasFiniteMemorySafetyBound() {
        StringBuilder reasoning = new StringBuilder();
        for (int i = 0; i < 15000; i++) {
            reasoning.append("Evidence ").append(i).append(" differs from ").append(i * 31)
                    .append(" so compare the next independent case.\n");
        }
        var message = AssistantMessage.builder().content("")
                .properties(Map.of("reasoningContent", reasoning.toString())).build();
        var result = new NodeStreamingChatHelper(streamTracker).streamCall(
                singleChunkModel(message), smallPrompt(), "conv-memory-bound", "reasoning");
        assertTrue(result.partial());
        assertEquals("thinking_only_no_content", result.errorMessage());
    }

    @Test
    void largerConfiguredTokenBudgetRaisesThinkingSafetyBound() {
        StringBuilder reasoning = new StringBuilder();
        for (int i = 0; i < 15000; i++) {
            reasoning.append("Evidence ").append(i).append(" differs from ").append(i * 31)
                    .append(" so compare the next independent case.\n");
        }
        var thinking = AssistantMessage.builder().content("")
                .properties(Map.of("reasoningContent", reasoning.toString())).build();
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(thinking))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("Finished"))))));
        var prompt = new Prompt(List.of(new UserMessage("hi")),
                org.springframework.ai.chat.prompt.ChatOptions.builder().maxTokens(100000).build());
        var result = new NodeStreamingChatHelper(streamTracker)
                .streamCall(model, prompt, "conv-configured-budget", "reasoning");
        assertFalse(result.partial());
        assertEquals("Finished", result.text());
    }

    @Test
    void reasoningOnlyTokenLimitIsIncompleteRatherThanEmptyOrSuccessful() {
        var message = AssistantMessage.builder().content("")
                .properties(Map.of("reasoningContent", "still reasoning")).build();
        var generation = new Generation(message, ChatGenerationMetadata.builder().finishReason("length").build());
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(generation))));
        var result = new NodeStreamingChatHelper(streamTracker)
                .streamCall(model, smallPrompt(), "conv-length", "reasoning");
        assertTrue(result.partial());
        assertEquals("thinking_token_limit", result.errorMessage());
        assertEquals("still reasoning", result.thinking());
        assertEquals("", result.text());
        assertEquals(NodeStreamingChatHelper.ErrorType.NONE, result.errorType());
    }

    @Test
    void tokenLimitWithContentOrToolsIsNotMisclassifiedAsThinkingOnly() {
        for (var message : List.of(
                AssistantMessage.builder().content("partial answer")
                        .properties(Map.of("reasoningContent", "reasoning")).build(),
                AssistantMessage.builder().content("").properties(Map.of("reasoningContent", "reasoning"))
                        .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "search", "{}"))).build())) {
            var generation = new Generation(message, ChatGenerationMetadata.builder().finishReason("length").build());
            var model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(generation))));
            var result = new NodeStreamingChatHelper(streamTracker)
                    .streamCall(model, smallPrompt(), "conv-length-progress", "reasoning");
            assertNotEquals("thinking_token_limit", result.errorMessage());
        }
    }

}
