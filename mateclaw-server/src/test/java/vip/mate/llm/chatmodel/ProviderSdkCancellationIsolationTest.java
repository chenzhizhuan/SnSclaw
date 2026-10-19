package vip.mate.llm.chatmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;

import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ProviderSdkCancellationIsolationTest {
    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers = Executors.newFixedThreadPool(4);
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger internalHeaders = new AtomicInteger();
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final ChatModel model;
        final String protocol;

        Fixture(String protocol) throws Exception {
            this.protocol = protocol;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext(
                    "/",
                    exchange -> {
                        requests.incrementAndGet();
                        for (String name : exchange.getRequestHeaders().keySet())
                            if ((name.equalsIgnoreCase(PinnedOpenAiHttpClient.STREAM_HEADER)
                                    || name.equalsIgnoreCase(
                                            AnthropicSdkWireInterceptor.CORRELATION_HEADER)))
                                internalHeaders.incrementAndGet();
                        try {
                            String body =
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8);
                            boolean cancel = body.contains("cancel-me");
                            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                            exchange.sendResponseHeaders(200, 0);
                            var out = exchange.getResponseBody();
                            out.write((start() + chunk("first")).getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            if (cancel) {
                                first.countDown();
                                for (int i = 0; i < 100; i++) {
                                    Thread.sleep(100);
                                    out.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                                    out.flush();
                                }
                            } else {
                                Thread.sleep(400);
                                out.write(chunk("-done").getBytes(StandardCharsets.UTF_8));
                                out.write(end().getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            }
                        } catch (IOException closed) {
                            disconnected.countDown();
                        } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                        } finally {
                            exchange.close();
                        }
                    });
            server.start();
            var service = mock(ModelProviderService.class);
            when(service.isProviderConfigured(anyString())).thenReturn(true);
            when(service.readProviderGenerateKwargs(any())).thenReturn(java.util.Map.of());
            var config = new ModelConfigEntity();
            config.setModelName("fixture");
            config.setRequestTimeoutSeconds(30);
            var provider = new ModelProviderEntity();
            provider.setProviderId("fixture");
            provider.setRequireApiKey(false);
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            model =
                    protocol.equals("anthropic")
                            ? AnthropicSdkChatModel.create(
                                    AnthropicChatOptions.builder()
                                            .model("fixture")
                                            .apiKey("dummy")
                                            .maxTokens(100)
                                            .baseUrl(provider.getBaseUrl())
                                            .timeout(Duration.ofSeconds(30))
                                            .build(),
                                    ObservationRegistry.NOOP,
                                    Map.of(),
                                    false)
                            : new OpenAiCompatibleChatModelBuilder(service, null, null, null)
                                    .build(config, provider, null);
        }

        String start() {
            return protocol.equals("anthropic")
                    ? "event: message_start\n"
                          + "data:"
                          + " {\"type\":\"message_start\",\"message\":{\"id\":\"fixture\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"fixture\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n"
                          + "event: content_block_start\n"
                          + "data:"
                          + " {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                    : "";
        }

        String end() {
            return protocol.equals("anthropic")
                    ? "event: content_block_stop\n"
                          + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                          + "event: message_delta\n"
                          + "data:"
                          + " {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}\n\n"
                          + "event: message_stop\n"
                          + "data: {\"type\":\"message_stop\"}\n\n"
                    : "data: [DONE]\n\n";
        }

        String chunk(String text) {
            if (protocol.equals("anthropic"))
                return "event: content_block_delta\n"
                           + "data:"
                           + " {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\""
                        + text
                        + "\"}}\n\n";
            return "data:"
                       + " {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                    + text
                    + "\"},\"finish_reason\":null}]}\n\n";
        }

        void healthy(
                reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse>
                        stream) {
            var rows = stream.collectList().block(Duration.ofSeconds(3));
            assertNotNull(rows);
            assertTrue(
                    rows.stream()
                            .anyMatch(
                                    row ->
                                            row.getResult() != null
                                                    && row.getResult()
                                                            .getOutput()
                                                            .getText()
                                                            .contains("done")));
        }

        @Override
        public void close() throws Exception {
            server.stop(0);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "anthropic"})
    void cancellingVirtualOwnerLeavesConcurrentHealthyRequestAlive(String protocol)
            throws Exception {
        try (var fixture = new Fixture(protocol)) {
            var received = new CountDownLatch(1);
            var dispose = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            Thread owner =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        var subscription =
                                                fixture.model.stream(new Prompt("cancel-me"))
                                                        .subscribe(
                                                                row -> received.countDown(),
                                                                failure::set);
                                        try {
                                            dispose.await();
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                        } finally {
                                            subscription.dispose();
                                        }
                                    });
            CompletableFuture<Void> healthy = null;
            try {
                assertTrue(received.await(3, TimeUnit.SECONDS));
                healthy =
                        CompletableFuture.runAsync(
                                () -> fixture.healthy(fixture.model.stream(new Prompt("healthy"))));
                Thread.sleep(100);
                owner.interrupt();
                owner.join(3000);
                assertFalse(owner.isAlive(), "Virtual owner must finish cancellation promptly");
                assertTrue(fixture.disconnected.await(3, TimeUnit.SECONDS));
                healthy.get(3, TimeUnit.SECONDS);
                assertEquals(2, fixture.requests.get());
                assertEquals(0, fixture.internalHeaders.get());
            } finally {
                owner.interrupt();
                owner.join(3000);
                if (healthy != null) healthy.cancel(true);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "anthropic"})
    void externalDisposeWithoutInterruptClosesConnection(String protocol) throws Exception {
        try (var fixture = new Fixture(protocol)) {
            var received = new CountDownLatch(1);
            var subscription =
                    fixture.model.stream(new Prompt("cancel-me"))
                            .subscribe(row -> received.countDown());
            try {
                assertTrue(received.await(3, TimeUnit.SECONDS));
                Thread.sleep(100);
                subscription.dispose();
                assertTrue(fixture.disconnected.await(3, TimeUnit.SECONDS));
                assertEquals(1, fixture.requests.get());
                assertEquals(0, fixture.internalHeaders.get());
            } finally {
                subscription.dispose();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "anthropic"})
    void sameColdFluxSupportsIndependentSubscriptionsWithoutWireMarker(String protocol)
            throws Exception {
        try (var fixture = new Fixture(protocol)) {
            var stream = fixture.model.stream(new Prompt("healthy"));
            var a = CompletableFuture.runAsync(() -> fixture.healthy(stream));
            var b = CompletableFuture.runAsync(() -> fixture.healthy(stream));
            try {
                CompletableFuture.allOf(a, b).get(3, TimeUnit.SECONDS);
                fixture.healthy(stream);
                assertEquals(3, fixture.requests.get());
                assertEquals(0, fixture.internalHeaders.get());
            } finally {
                a.cancel(true);
                b.cancel(true);
            }
        }
    }
}
