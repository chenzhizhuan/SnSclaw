package vip.mate.llm.chatmodel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.openai.core.RequestOptions;
import com.openai.core.http.HttpMethod;
import com.openai.core.http.HttpRequest;

import io.micrometer.observation.ObservationRegistry;

import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

class PinnedOpenAiCancellationScopeTest {
    private PinnedOpenAiHttpClient transport() {
        return new PinnedOpenAiHttpClient(
                SpringAiOpenAiHttpClient.builder()
                        .observationRegistry(ObservationRegistry.NOOP)
                        .timeout(Duration.ofSeconds(2))
                        .build());
    }

    private HttpRequest request(MockWebServer server, String token) {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .baseUrl(server.url("/").toString())
                .putHeader(PinnedOpenAiHttpClient.STREAM_HEADER.toLowerCase(), token)
                .putHeader("X-User-Policy", "retained")
                .build();
    }

    @Test
    void cancelledScopeRejectsActualRequestBeforeExecution() throws Exception {
        try (var server = new MockWebServer();
                var transport = transport()) {
            server.start();
            var scope = transport.startStream();
            scope.cancel();
            assertThrows(
                    RuntimeException.class,
                    () -> transport.execute(request(server, scope.token()), RequestOptions.none()));
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
            scope.close();
            assertEquals(0, transport.activeStreamCount());
        }
    }

    @Test
    void closedScopeTokenFailsBeforeSynchronousOrAsyncNetworkDispatch() throws Exception {
        try (var server = new MockWebServer();
                var transport = transport()) {
            server.start();
            var scope = transport.startStream();
            scope.close();
            assertThrows(
                    CancellationException.class,
                    () -> transport.execute(request(server, scope.token()), RequestOptions.none()));
            assertThrows(
                    CancellationException.class,
                    () ->
                            transport.executeAsync(
                                    request(server, scope.token()), RequestOptions.none()));
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
            assertEquals(0, transport.activeStreamCount());
        }
    }

    @Test
    void cancellationBeforeLateActualCallRegistrationCancelsThatCall() throws Exception {
        try (var server = new MockWebServer();
                var transport = transport()) {
            server.start();
            var scope = transport.startStream();
            scope.cancel();
            var call =
                    transport.client().newCall(new Request.Builder().url(server.url("/")).build());
            scope.register(call);
            assertTrue(call.isCanceled());
            assertThrows(java.io.IOException.class, call::execute);
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
            scope.close();
        }
    }

    @Test
    void finishBeforeLateActualCallRegistrationCancelsThatCall() throws Exception {
        try (var server = new MockWebServer();
                var transport = transport()) {
            server.start();
            var scope = transport.startStream();
            scope.close();
            var call =
                    transport.client().newCall(new Request.Builder().url(server.url("/")).build());
            scope.register(call);
            assertTrue(call.isCanceled());
            assertThrows(java.io.IOException.class, call::execute);
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
            assertEquals(0, transport.activeStreamCount());
        }
    }

    @Test
    void cancelledSiblingCannotCancelLiveRequestAndInternalHeaderNeverLeavesTransport()
            throws Exception {
        try (var server = new MockWebServer();
                var transport = transport()) {
            server.enqueue(new MockResponse().setBody("live sibling"));
            server.start();
            var cancelled = transport.startStream();
            var live = transport.startStream();
            cancelled.cancel();
            cancelled.close();
            try (var response =
                    transport.execute(request(server, live.token()), RequestOptions.none())) {
                assertEquals("live sibling", new String(response.body().readAllBytes()));
            }
            var received = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(received);
            assertNull(received.getHeader(PinnedOpenAiHttpClient.STREAM_HEADER));
            assertEquals("retained", received.getHeader("X-User-Policy"));
            assertEquals(1, server.getRequestCount());
            live.close();
            assertEquals(0, transport.activeStreamCount());
        }
    }

    @Test
    void sameFluxSubscriptionsOwnSeparateScopesAndNormalCompletionCleansThem() {
        try (var transport = transport()) {
            var delegate = mock(ChatModel.class);
            when(delegate.getOptions())
                    .thenReturn(OpenAiChatOptions.builder().model("fixture").build());
            var tokens = new ArrayList<String>();
            when(delegate.stream(any(Prompt.class)))
                    .thenAnswer(
                            invocation -> {
                                Prompt prompt = invocation.getArgument(0);
                                tokens.add(
                                        ((OpenAiChatOptions) prompt.getOptions())
                                                .getCustomHeaders()
                                                .get(PinnedOpenAiHttpClient.STREAM_HEADER));
                                assertEquals(1, transport.activeStreamCount());
                                return Flux.<ChatResponse>empty();
                            });
            var wrapper = new OpenAiWirePolicyChatModel(delegate, null, transport);
            var stream = wrapper.stream(new Prompt("hello"));
            assertEquals(0, transport.activeStreamCount());
            stream.blockLast();
            assertEquals(0, transport.activeStreamCount());
            stream.blockLast();
            assertEquals(2, new HashSet<>(tokens).size());
            assertEquals(0, transport.activeStreamCount());
        }
    }

    @Test
    void synchronousDelegateConstructionFailureAndExternalDisposeCleanRegistry() {
        try (var transport = transport()) {
            var delegate = mock(ChatModel.class);
            when(delegate.getOptions())
                    .thenReturn(OpenAiChatOptions.builder().model("fixture").build());
            when(delegate.stream(any(Prompt.class)))
                    .thenThrow(new IllegalStateException("setup failed"));
            var wrapper = new OpenAiWirePolicyChatModel(delegate, null, transport);
            assertThrows(
                    IllegalStateException.class,
                    () -> wrapper.stream(new Prompt("hello")).blockLast());
            assertEquals(0, transport.activeStreamCount());
            doReturn(Flux.never()).when(delegate).stream(any(Prompt.class));
            var subscription = wrapper.stream(new Prompt("hello")).subscribe();
            assertEquals(1, transport.activeStreamCount());
            subscription.dispose();
            assertEquals(0, transport.activeStreamCount());
        }
    }
}
