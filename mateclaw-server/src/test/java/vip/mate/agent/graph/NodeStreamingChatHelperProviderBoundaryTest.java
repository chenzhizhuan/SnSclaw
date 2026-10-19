package vip.mate.agent.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import reactor.core.publisher.Flux;

import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.dashscope.NativeDashScopeChatOptions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

class NodeStreamingChatHelperProviderBoundaryTest {
    @Test
    void openAiCumulativeReasoningIsAccumulatedOnlyOnce() {
        ChatModel model =
                model(
                        OpenAiChatOptions.builder().model("openai-compatible").build(),
                        Flux.just(
                                chunk("", Map.of("reasoningContent", "Think")),
                                chunk("", Map.of("reasoningContent", "Think twice")),
                                chunk("Answer", Map.of("reasoningContent", "Think twice"))));
        var result =
                helper(mock(ChatStreamTracker.class))
                        .streamCall(model, new Prompt("Hi"), "cumulative", "test");
        assertEquals("Think twice", result.thinking());
        assertEquals(
                "Think twice", result.assistantMessage().getMetadata().get("reasoningContent"));
        assertEquals("Answer", result.text());
    }

    @Test
    void anthropicNativeAndCustomProvidersKeepRepeatedReasoningDeltas() {
        for (ChatOptions options :
                List.of(
                        AnthropicChatOptions.builder().model("claude").build(),
                        NativeDashScopeChatOptions.builder().model("native").build(),
                        ChatOptions.builder().model("plugin").build())) {
            ChatModel model =
                    model(
                            options,
                            Flux.just(
                                    chunk("", Map.of("reasoningContent", "same")),
                                    chunk("Answer", Map.of("reasoningContent", "same"))));
            assertEquals(
                    "samesame",
                    helper(mock(ChatStreamTracker.class))
                            .streamCall(model, new Prompt("Hi"), "delta", "test")
                            .thinking());
        }
    }

    @Test
    void terminalEmptyChunkRetainsCompletedProviderMetadataOnSuccess() {
        Map<String, Object> metadata = providerMetadata();
        ChatModel model =
                model(
                        OpenAiChatOptions.builder().model("sdk").build(),
                        Flux.just(chunk("Answer", metadata), chunk("", Map.of())));
        var result =
                helper(mock(ChatStreamTracker.class))
                        .streamCall(model, new Prompt("Hi"), "success", "test");
        metadata.forEach(
                (key, value) ->
                        assertEquals(value, result.assistantMessage().getMetadata().get(key), key));
    }

    @Test
    void terminalEmptyChunkRetainsCompletedProviderMetadataWhenStopped() {
        AtomicBoolean stopped = new AtomicBoolean();
        ChatStreamTracker tracker = mock(ChatStreamTracker.class);
        when(tracker.isStopRequested(any())).thenAnswer(invocation -> stopped.get());
        Map<String, Object> metadata = providerMetadata();
        Flux<ChatResponse> stream =
                Flux.concat(
                        Flux.just(chunk("Answer", metadata), chunk("", Map.of()))
                                .doOnComplete(() -> stopped.set(true)),
                        Flux.never());
        var result =
                helper(tracker)
                        .streamCall(
                                model(
                                        AnthropicChatOptions.builder().model("claude").build(),
                                        stream),
                                new Prompt("Hi"),
                                "stopped",
                                "test");
        assertTrue(result.stopped());
        metadata.forEach(
                (key, value) ->
                        assertEquals(value, result.assistantMessage().getMetadata().get(key), key));
    }

    @Test
    void toolPropertiesMergeAcrossCallIdsWithLatestValueForEachCall() {
        String key = "openai.tool_calls.additional_properties";
        Map<String, String> expected =
                Map.of(
                        "call-1",
                        "{\"thought_signature\":\"latest\"}",
                        "call-2",
                        "{\"thought_signature\":\"second\"}");
        ChatModel model =
                model(
                        OpenAiChatOptions.builder().model("sdk").build(),
                        Flux.just(
                                chunk(
                                        "Answer",
                                        Map.of(
                                                key,
                                                Map.of(
                                                        "call-1",
                                                        "{\"thought_signature\":\"first\"}"))),
                                chunk("", Map.of(key, Map.of("call-2", expected.get("call-2")))),
                                chunk("", Map.of(key, Map.of("call-1", expected.get("call-1"))))));
        var result =
                helper(mock(ChatStreamTracker.class))
                        .streamCall(model, new Prompt("Hi"), "tool-properties", "test");
        assertEquals(expected, result.assistantMessage().getMetadata().get(key));
    }

    @Test
    void blankSearchMetadataDoesNotEraseCompletedSearchResult() {
        Object search = providerMetadata().get("search_info");
        ChatModel model =
                model(
                        ChatOptions.builder().model("native").build(),
                        Flux.just(
                                chunk("Answer", Map.of("search_info", search)),
                                chunk("", Map.of("search_info", "  "))));
        var result =
                helper(mock(ChatStreamTracker.class))
                        .streamCall(model, new Prompt("Hi"), "search", "test");
        assertEquals(search, result.assistantMessage().getMetadata().get("search_info"));
    }

    @Test
    void requestEventUsesTheConfiguredProviderModelName() {
        ChatStreamTracker tracker = mock(ChatStreamTracker.class);
        ChatModel model =
                model(
                        OpenAiChatOptions.builder().model("configured-provider-model").build(),
                        Flux.just(chunk("Answer", Map.of())));
        helper(tracker).streamCall(model, new Prompt("Hi"), "identity", "reasoning");
        verify(tracker)
                .broadcastObject(
                        eq("identity"),
                        eq("llm_request_sent"),
                        argThat(
                                payload ->
                                        payload instanceof Map<?, ?> data
                                                && "configured-provider-model"
                                                        .equals(data.get("model"))));
    }

    private static NodeStreamingChatHelper helper(ChatStreamTracker tracker) {
        return new NodeStreamingChatHelper(tracker);
    }

    private static ChatModel model(ChatOptions options, Flux<ChatResponse> stream) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(options);
        when(model.stream(any(Prompt.class))).thenReturn(stream);
        return model;
    }

    private static ChatResponse chunk(String text, Map<String, Object> metadata) {
        return new ChatResponse(
                List.of(
                        new Generation(
                                AssistantMessage.builder()
                                        .content(text)
                                        .properties(metadata)
                                        .build())));
    }

    private static Map<String, Object> providerMetadata() {
        return Map.of(
                "anthropicThinkingBlocks",
                List.of(Map.of("type", "thinking", "thinking", "Reason", "signature", "signed")),
                "openai.tool_calls.additional_properties",
                Map.of("call-1", "{\"thought_signature\":\"opaque\"}"),
                "search_info",
                Map.of("search_results", List.of(Map.of("url", "https://example.test"))));
    }
}
