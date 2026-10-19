package vip.mate.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.failover.FallbackEntry;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NodeStreamingChatHelperBufferedContentTest {
    private final ChatStreamTracker tracker = new ChatStreamTracker(new ObjectMapper());
    private final NodeStreamingChatHelper helper = new NodeStreamingChatHelper(tracker);

    @AfterEach
    void closeTracker() {
        tracker.onShutdown();
    }

    @Test
    void returnsWholeCandidateWithoutPublishingContentIncludingReplay() {
        var events = attach("buffered");
        var result = buffered(helper, model(Flux.just(chunk("Hello "), chunk("world"))), "buffered");
        assertEquals("Hello world", result.text());
        assertEquals("Hello world", result.assistantMessage().getText());
        assertNoContent("buffered", events);
        var replay = new CapturingEmitter();
        assertTrue(tracker.attach("buffered", replay));
        assertFalse(replay.names().contains("content_delta"));
        assertTrue(events.names().contains("llm_request_sent"));
    }

    @Test
    void thinkingIsDeliveredBeforeCandidateFinishes() throws Exception {
        var events = attach("thinking");
        var sink = Sinks.many().unicast().<ChatResponse>onBackpressureBuffer();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> buffered(helper, model(sink.asFlux()), "thinking"));
            assertEquals(Sinks.EmitResult.OK, sink.tryEmitNext(new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("").properties(Map.of("reasoningContent", "working")).build())))));
            awaitEvent(events, "thinking_delta");
            assertFalse(future.isDone(), "Thinking must be visible while the candidate is still in flight");
            assertEquals(Sinks.EmitResult.OK, sink.tryEmitNext(chunk("candidate")));
            sink.tryEmitComplete();
            assertEquals("working", future.get(5, TimeUnit.SECONDS).thinking());
            assertNoContent("thinking", events);
        }
    }

    @Test
    void laterRegularCallStillPublishesContent() {
        var events = attach("later");
        buffered(helper, model(Flux.just(chunk("private"))), "later");
        helper.streamCall(model(Flux.just(chunk("public"))), new Prompt("Hi"), "later", "test");
        tracker.broadcastObject("later", "flush", Map.of());
        assertEquals(List.of("{\"delta\":\"public\"}"), events.payloads("content_delta"));
    }

    @Test
    void simultaneousRegularAndBufferedConversationsStayIsolated() throws Exception {
        var hidden = attach("hidden");
        var visible = attach("visible");
        var sink = Sinks.many().unicast().<ChatResponse>onBackpressureBuffer();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> buffered(helper, model(sink.asFlux()), "hidden"));
            awaitEvent(hidden, "llm_request_sent");
            helper.streamCall(model(Flux.just(chunk("visible answer"))), new Prompt("Hi"), "visible", "test");
            tracker.broadcastObject("visible", "flush", Map.of());
            sink.tryEmitNext(chunk("hidden answer"));
            sink.tryEmitComplete();
            assertEquals("hidden answer", future.get(5, TimeUnit.SECONDS).text());
            assertNoContent("hidden", hidden);
            assertEquals(List.of("{\"delta\":\"visible answer\"}"), visible.payloads("content_delta"));
        }
    }

    @Test
    void fallbackKeepsWarningsLiveWithoutPublishingCandidate() {
        var events = attach("fallback");
        var primary = model(Flux.error(new IllegalStateException("401 Unauthorized")));
        var fallback = model(Flux.just(chunk("fallback candidate")));
        var withFallback = new NodeStreamingChatHelper(tracker, List.of(new FallbackEntry("backup", fallback)), null);
        assertEquals("fallback candidate", buffered(withFallback, primary, "fallback").text());
        assertTrue(events.names().contains("warning"));
        assertNoContent("fallback", events);
    }

    @Test
    void retryKeepsContentBufferedAndWarningsLive() {
        var events = attach("retry");
        helper.setRetryTimingForTest(1, 1, 10_000);
        var model = model(Flux.empty());
        when(model.stream(any(Prompt.class))).thenReturn(
                Flux.error(new IllegalStateException("500 internal server error")),
                Flux.just(chunk("retried candidate")));
        assertEquals("retried candidate", buffered(helper, model, "retry").text());
        verify(model, times(2)).stream(any(Prompt.class));
        assertTrue(events.names().contains("warning"));
        assertNoContent("retry", events);
    }

    @Test
    void inlineThinkingAndHeldBackTailCannotLeakContent() {
        var events = attach("tail");
        var result = buffered(helper, model(Flux.just(chunk("<thi"), chunk("nk>reason</think>answer<thi"))), "tail");
        assertEquals("answer<thi", result.text());
        assertEquals("reason", result.thinking());
        assertTrue(events.names().contains("thinking_delta"));
        assertNoContent("tail", events);
    }

    @Test
    void bufferedToolCallsSurviveAggregation() {
        var events = attach("tools");
        var message = AssistantMessage.builder().content("candidate")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "search", "{}"))).build();
        var response = new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(19, 11, 30, null, 7L, 3L)).build());
        var result = buffered(helper, model(Flux.just(response)), "tools");
        assertEquals(19, result.promptTokens());
        assertEquals(11, result.completionTokens());
        assertTrue(result.hasToolCalls());
        assertEquals("search", result.toolCalls().getFirst().name());
        assertNoContent("tools", events);
    }

    @Test
    void silentCallsRemainCompletelySilentOnTerminalErrors() {
        var events = attach("silent");
        helper.streamCallSilent(model(Flux.error(new IllegalStateException("401 Unauthorized"))),
                new Prompt("Hi"), "silent", "test");
        assertEquals(List.of(), events.names());
    }

    @Test
    void silentCallsRemainSilentWhenThinkingGuardTruncates() {
        var events = attach("silent-truncated");
        var message = AssistantMessage.builder().content("")
                .properties(Map.of("reasoningContent", "x".repeat(40_000))).build();
        var result = helper.streamCallSilent(model(Flux.just(new ChatResponse(List.of(new Generation(message))))),
                new Prompt("Hi"), "silent-truncated", "test");
        assertTrue(result.partial());
        assertEquals("thinking_only_no_content", result.errorMessage());
        assertEquals(List.of(), events.names());
    }

    private static NodeStreamingChatHelper.StreamResult buffered(NodeStreamingChatHelper target, ChatModel model, String id) {
        return target.streamCallBufferedContent(model, new Prompt("Hi"), id, "test");
    }

    private void assertNoContent(String id, CapturingEmitter events) {
        // A lifecycle event flushes the real tracker's pending content batch.
        // Checking before that barrier would miss leaks still queued for delivery.
        tracker.broadcastObject(id, "flush", Map.of());
        assertFalse(events.names().contains("content_delta"));
    }

    private CapturingEmitter attach(String id) {
        tracker.register(id);
        var events = new CapturingEmitter();
        assertTrue(tracker.attach(id, events));
        return events;
    }

    private static ChatModel model(Flux<ChatResponse> stream) {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(stream);
        return model;
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static void awaitEvent(CapturingEmitter events, String name) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!events.names().contains(name) && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(events.names().contains(name), "Expected live " + name);
    }

    private record Event(String name, String payload) {}

    private static final class CapturingEmitter extends SseEmitter {
        private final List<Event> events = new CopyOnWriteArrayList<>();

        List<String> names() {
            return events.stream().map(Event::name).toList();
        }

        List<String> payloads(String name) {
            return events.stream().filter(event -> name.equals(event.name())).map(Event::payload).toList();
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            String name = "";
            String payload = "";
            boolean expectPayload = false;
            for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
                if (!(entry.getData() instanceof String text)) continue;
                if (text.contains("event:") && text.contains("data:")) {
                    int start = text.indexOf("event:") + 6;
                    int end = text.indexOf('\n', start);
                    name = text.substring(start, end < 0 ? text.length() : end).trim();
                    expectPayload = true;
                } else if (expectPayload && !"\n\n".equals(text)) {
                    payload = text;
                    expectPayload = false;
                }
            }
            events.add(new Event(name, payload));
        }
    }
}
