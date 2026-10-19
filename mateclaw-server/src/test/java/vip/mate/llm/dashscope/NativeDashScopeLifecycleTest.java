package vip.mate.llm.dashscope;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class NativeDashScopeLifecycleTest {
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService threads = Executors.newCachedThreadPool();
        final HttpClient client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        Fixture(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(threads);
            server.createContext(
                    "/",
                    exchange -> {
                        try {
                            exchange.getRequestBody().readAllBytes();
                            handler.handle(exchange);
                        } catch (Exception ignored) {
                        } finally {
                            exchange.close();
                        }
                    });
            server.start();
        }

        NativeDashScopeTransport transport(Duration timeout) {
            return new NativeDashScopeTransport(
                    "local-fixture",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    client,
                    timeout);
        }

        @Override
        public void close() {
            server.stop(0);
            threads.shutdownNow();
            client.shutdownNow();
        }
    }

    @Test
    void delayedWatchdogCannotAcceptBodyBeyondReadDeadline() throws Exception {
        var field = NativeDashScopeTransport.class.getDeclaredField("READ_DEADLINES");
        field.setAccessible(true);
        var deadlines = (java.util.concurrent.ScheduledThreadPoolExecutor) field.get(null);
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        deadlines.execute(
                () -> {
                    occupied.countDown();
                    try {
                        release.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        try (var fixture =
                new Fixture(
                        exchange -> {
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody().write(" ".getBytes(StandardCharsets.UTF_8));
                            exchange.getResponseBody().flush();
                            Thread.sleep(600);
                            exchange.getResponseBody()
                                    .write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                        })) {
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.transport(Duration.ofMillis(200)).exchange("/sync", Map.of()));
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentStalledBodiesDoNotSaturateSharedWatchdog() throws Exception {
        var ready = new CountDownLatch(32);
        var release = new CountDownLatch(1);
        try (var fixture =
                        new Fixture(
                                exchange -> {
                                    exchange.sendResponseHeaders(200, 0);
                                    exchange.getResponseBody()
                                            .write("{ ".getBytes(StandardCharsets.UTF_8));
                                    exchange.getResponseBody().flush();
                                    ready.countDown();
                                    release.await(5, TimeUnit.SECONDS);
                                });
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 32; i++) {
                futures.add(
                        callers.submit(
                                () -> {
                                    var failure =
                                            assertThrows(
                                                    IllegalStateException.class,
                                                    () ->
                                                            fixture.transport(Duration.ofSeconds(1))
                                                                    .exchange("/sync", Map.of()));
                                    assertInstanceOf(
                                            SocketTimeoutException.class, failure.getCause());
                                }));
            }
            try {
                assertTrue(ready.await(3, TimeUnit.SECONDS));
                for (var future : futures) future.get(3, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void completedReadsRemoveCancelledWatchdogs() throws Exception {
        try (var fixture =
                new Fixture(
                        exchange -> {
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody()
                                    .write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                        })) {
            for (int i = 0; i < 20; i++) {
                assertTrue(
                        fixture.transport(Duration.ofHours(1))
                                .exchange("/sync", Map.of())
                                .path("ok")
                                .asBoolean());
            }
            var field = NativeDashScopeTransport.class.getDeclaredField("READ_DEADLINES");
            field.setAccessible(true);
            var deadlines = (java.util.concurrent.ScheduledThreadPoolExecutor) field.get(null);
            assertTrue(
                    deadlines.getQueue().isEmpty(),
                    "Completed reads must release queued watchdog references");
        }
    }

    @Test
    void healthySyncBodyMayExceedReadTimeoutWithContinuousBytes() throws Exception {
        try (var fixture =
                new Fixture(
                        exchange -> {
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, 0);
                            for (int i = 0; i < 12; i++) {
                                exchange.getResponseBody()
                                        .write("          ".getBytes(StandardCharsets.UTF_8));
                                exchange.getResponseBody().flush();
                                Thread.sleep(100);
                            }
                            exchange.getResponseBody()
                                    .write(
                                            "{\"request_id\":\"healthy\",\"ok\":true}"
                                                    .getBytes(StandardCharsets.UTF_8));
                        })) {
            assertTrue(
                    fixture.transport(Duration.ofMillis(400))
                            .exchange("/sync", Map.of())
                            .path("ok")
                            .asBoolean());
        }
    }

    @Test
    void stalledSyncBodyStillTimesOutAfterHeaders() throws Exception {
        var disconnected = new CountDownLatch(1);
        try (var fixture =
                new Fixture(
                        exchange -> {
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody()
                                    .write("{\"ok\":".getBytes(StandardCharsets.UTF_8));
                            exchange.getResponseBody().flush();
                            Thread.sleep(700);
                            try {
                                for (int i = 0; i < 30; i++) {
                                    exchange.getResponseBody()
                                            .write(" ".getBytes(StandardCharsets.UTF_8));
                                    exchange.getResponseBody().flush();
                                    Thread.sleep(20);
                                }
                            } catch (IOException closed) {
                                disconnected.countDown();
                            }
                        })) {
            long started = System.nanoTime();
            var failure =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    fixture.transport(Duration.ofMillis(300))
                                            .exchange("/sync", Map.of()));
            assertInstanceOf(SocketTimeoutException.class, failure.getCause());
            assertTrue(
                    Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2))
                            < 0);
            assertTrue(
                    disconnected.await(3, TimeUnit.SECONDS),
                    "Read deadline must close the actual response body");
        }
    }

    @Test
    void cancellingRealSseClosesServerConnection() throws Exception {
        var disconnected = new CountDownLatch(1);
        try (var fixture =
                new Fixture(
                        exchange -> {
                            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                            exchange.sendResponseHeaders(200, 0);
                            try {
                                for (int i = 0; i < 200; i++) {
                                    exchange.getResponseBody()
                                            .write(
                                                    "data: {\"ok\":true}\n\n"
                                                            .getBytes(StandardCharsets.UTF_8));
                                    exchange.getResponseBody().flush();
                                    Thread.sleep(20);
                                }
                            } catch (IOException closed) {
                                disconnected.countDown();
                            }
                        })) {
            assertEquals(
                    1,
                    fixture.transport(Duration.ofSeconds(1)).stream("/sse", Map.of())
                            .take(1)
                            .collectList()
                            .block(Duration.ofSeconds(3))
                            .size());
            assertTrue(
                    disconnected.await(3, TimeUnit.SECONDS),
                    "Cancellation must close the actual HTTP response body");
        }
    }

    @Test
    void continuousSseEventsOutliveConfiguredIdleTimeout() throws Exception {
        try (var fixture =
                new Fixture(
                        exchange -> {
                            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                            exchange.sendResponseHeaders(200, 0);
                            for (int i = 0; i < 12; i++) {
                                exchange.getResponseBody()
                                        .write(
                                                "data: {\"ok\":true}\n\n"
                                                        .getBytes(StandardCharsets.UTF_8));
                                exchange.getResponseBody().flush();
                                Thread.sleep(100);
                            }
                            exchange.getResponseBody()
                                    .write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                        })) {
            assertEquals(
                    12,
                    fixture.transport(Duration.ofMillis(400)).stream("/sse", Map.of())
                            .collectList()
                            .block(Duration.ofSeconds(3))
                            .size());
        }
    }

    @Test
    void oversizedNativeEmbeddingIndexCannotWrapIntoValidInputIndex() throws Exception {
        for (String index :
                List.of("4294967296", "-1", "2147483647", "-2147483649", "9223372036854775807")) {
            try (var fixture =
                    new Fixture(
                            exchange -> {
                                byte[] response =
                                        "{\"request_id\":\"bad-index\",\"output\":{\"embeddings\":[{\"text_index\":%s,\"embedding\":[0.1,0.2]}]}}"
                                                .formatted(index)
                                                .getBytes(StandardCharsets.UTF_8);
                                exchange.getResponseHeaders()
                                        .set("Content-Type", "application/json");
                                exchange.sendResponseHeaders(200, response.length);
                                exchange.getResponseBody().write(response);
                            })) {
                var model =
                        new NativeDashScopeEmbeddingModel(
                                fixture.transport(Duration.ofSeconds(2)),
                                MetadataMode.EMBED,
                                EmbeddingOptions.builder()
                                        .model("text-embedding-v3")
                                        .dimensions(2)
                                        .build());
                var failure =
                        assertThrows(
                                NativeDashScopeException.class,
                                () -> model.call(new EmbeddingRequest(List.of("one"), null)));
                assertEquals("bad-index", failure.getRequestId());
            }
        }
    }

    @Test
    void exactNativeSdkReadTimeoutAllowsHealthyLongResponseBody() throws Exception {
        try (var fixture =
                new Fixture(
                        exchange -> {
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, 0);
                            for (int i = 0; i < 12; i++) {
                                exchange.getResponseBody()
                                        .write("          ".getBytes(StandardCharsets.UTF_8));
                                exchange.getResponseBody().flush();
                                Thread.sleep(100);
                            }
                            exchange.getResponseBody()
                                    .write(
                                            "{\"request_id\":\"sdk-healthy\",\"output\":{\"text\":\"hello\"},\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}"
                                                    .getBytes(StandardCharsets.UTF_8));
                        })) {
            var connection =
                    ConnectionOptions.builder()
                            .useDefaultClient(false)
                            .readTimeout(Duration.ofMillis(400))
                            .connectTimeout(Duration.ofSeconds(2))
                            .build();
            var generation =
                    new Generation(
                            "http",
                            "http://127.0.0.1:" + fixture.server.getAddress().getPort() + "/api/v1",
                            connection);
            var response =
                    generation.call(
                            GenerationParam.builder()
                                    .apiKey("local-fixture")
                                    .model("qwen-test")
                                    .prompt("hello")
                                    .build());
            assertEquals("hello", response.getOutput().getText());
            assertEquals("sdk-healthy", response.getRequestId());
        }
    }

    @Test
    void streamedProviderErrorRetainsAlreadyEmittedNativeMetadata() throws Exception {
        String first =
                """
{"request_id":"native-stream","output":{"choices":[{"index":0,"finish_reason":"tool_calls",
"message":{"role":"assistant","content":"partial","reasoning_content":"thinking","tool_calls":[
{"index":0,"id":"call","type":"function","function":{"name":"echo","arguments":"{\\"x\\":1}"}}]}}],
"search_info":{"search_results":[{"title":"source"}]}},"usage":{"input_tokens":3,"output_tokens":1,"total_tokens":4}}
""";
        try (var fixture =
                new Fixture(
                        exchange -> {
                            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody()
                                    .write(
                                            ("data: " + first.replace("\n", "") + "\n\n")
                                                    .getBytes(StandardCharsets.UTF_8));
                            exchange.getResponseBody().flush();
                            exchange.getResponseBody()
                                    .write(
                                            "data: {\"request_id\":\"provider-error\",\"code\":\"Throttling\",\"message\":\"retry later\"}\n\n"
                                                    .getBytes(StandardCharsets.UTF_8));
                        })) {
            var model =
                    new NativeDashScopeChatModel(
                            fixture.transport(Duration.ofSeconds(2)),
                            NativeDashScopeChatOptions.builder().model("qwen-test").build());
            var emitted = new ArrayList<ChatResponse>();
            var failure =
                    assertThrows(
                            NativeDashScopeException.class,
                            () ->
                                    model.stream(new Prompt("hello"))
                                            .doOnNext(emitted::add)
                                            .then()
                                            .block(Duration.ofSeconds(3)));
            assertEquals("Throttling", failure.getCode());
            assertEquals("provider-error", failure.getRequestId());
            assertEquals(1, emitted.size());
            var response = emitted.getFirst();
            assertEquals(4, response.getMetadata().getUsage().getTotalTokens());
            assertEquals(
                    "thinking",
                    response.getResult().getOutput().getMetadata().get("reasoningContent"));
            assertEquals(
                    "{\"x\":1}",
                    response.getResult().getOutput().getToolCalls().getFirst().arguments());
            assertNotEquals("", response.getResult().getOutput().getMetadata().get("search_info"));
        }
    }

    @Test
    void interruptingSyncBodyReadClosesConnectionAndRestoresInterrupt() throws Exception {
        var headers = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        try (var fixture =
                new Fixture(
                        exchange -> {
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody()
                                    .write("{\"ok\":".getBytes(StandardCharsets.UTF_8));
                            exchange.getResponseBody().flush();
                            headers.countDown();
                            Thread.sleep(500);
                            try {
                                for (int i = 0; i < 30; i++) {
                                    exchange.getResponseBody()
                                            .write(" ".getBytes(StandardCharsets.UTF_8));
                                    exchange.getResponseBody().flush();
                                    Thread.sleep(20);
                                }
                            } catch (IOException closed) {
                                disconnected.countDown();
                            }
                        })) {
            var interrupted = new AtomicBoolean();
            var failure = new AtomicReference<Throwable>();
            var caller =
                    new Thread(
                            () -> {
                                try {
                                    fixture.transport(Duration.ofSeconds(5))
                                            .exchange("/sync", Map.of());
                                } catch (Throwable error) {
                                    failure.set(error);
                                    interrupted.set(Thread.currentThread().isInterrupted());
                                }
                            });
            caller.start();
            try {
                assertTrue(headers.await(3, TimeUnit.SECONDS));
                caller.interrupt();
                caller.join(2000);
                assertFalse(caller.isAlive());
                assertNotNull(failure.get());
                assertTrue(interrupted.get());
                assertTrue(disconnected.await(3, TimeUnit.SECONDS));
            } finally {
                caller.interrupt();
                caller.join(2000);
            }
        }
    }

    @Test
    void syncProviderErrorStillPreservesHttpStatusCodeAndRequestId() throws Exception {
        try (var fixture =
                new Fixture(
                        exchange -> {
                            byte[] error =
                                    "{\"request_id\":\"sync-error\",\"code\":\"Throttling\",\"message\":\"retry later\"}"
                                            .getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(429, error.length);
                            exchange.getResponseBody().write(error);
                        })) {
            var failure =
                    assertThrows(
                            NativeDashScopeException.class,
                            () ->
                                    fixture.transport(Duration.ofSeconds(2))
                                            .exchange("/sync", Map.of()));
            assertEquals(429, failure.getStatus());
            assertEquals("Throttling", failure.getCode());
            assertEquals("sync-error", failure.getRequestId());
        }
    }
}
