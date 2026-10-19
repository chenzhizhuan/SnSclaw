package vip.mate.agent.graph;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;

import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.chatmodel.AnthropicSdkChatModel;
import vip.mate.llm.chatmodel.OpenAiCompatibleChatModelBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class NodeStreamingChatHelperCancellationTransportTest {
    private static ChatModel primary(String provider, String base) {
        if (provider.equals("anthropic"))
            return AnthropicSdkChatModel.create(
                    AnthropicChatOptions.builder()
                            .apiKey("dummy")
                            .baseUrl(base)
                            .model("fixture")
                            .maxTokens(100)
                            .timeout(Duration.ofMillis(250))
                            .build(),
                    ObservationRegistry.NOOP,
                    Map.of(),
                    false);
        var s = mock(ModelProviderService.class);
        when(s.isProviderConfigured(anyString())).thenReturn(true);
        when(s.readProviderGenerateKwargs(any())).thenReturn(Map.of());
        var m = new ModelConfigEntity();
        m.setModelName("fixture");
        m.setRequestTimeoutSeconds(1);
        var p = new ModelProviderEntity();
        p.setProviderId("fixture");
        p.setRequireApiKey(false);
        p.setBaseUrl(base);
        return new OpenAiCompatibleChatModelBuilder(s, null, null, null).build(m, p, null);
    }

    private static String start(String p) {
        return p.equals("anthropic")
                ? "event: message_start\n"
                      + "data:"
                      + " {\"type\":\"message_start\",\"message\":{\"id\":\"r\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"fixture\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n"
                : "data:"
                      + " {\"id\":\"r\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":null}]}\n\n";
    }

    private static void run(String provider, String mode) throws Exception {
        var entered = new CountDownLatch(1);
        var received = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newFixedThreadPool(2);
        server.setExecutor(workers);
        server.createContext(
                "/",
                x -> {
                    requests.incrementAndGet();
                    try {
                        x.getRequestBody().readAllBytes();
                        if (mode.equals("httpError")) {
                            byte[] b =
                                    "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"fixture invalid\"}}"
                                            .getBytes();
                            x.sendResponseHeaders(503, b.length);
                            x.getResponseBody().write(b);
                            return;
                        }
                        x.getResponseHeaders().set("Content-Type", "text/event-stream");
                        x.sendResponseHeaders(200, 0);
                        var out = x.getResponseBody();
                        out.write(start(provider).getBytes(StandardCharsets.UTF_8));
                        if (mode.equals("postContent")) {
                            String content = provider.equals("anthropic")
                                    ? "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"fixture\"}}\n\n"
                                    : "data: {\"id\":\"r\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"fixture\"},\"finish_reason\":null}]}\n\n";
                            out.write(content.getBytes(StandardCharsets.UTF_8));
                        }
                        out.flush();
                        entered.countDown();
                        if (mode.equals("timeout")) {
                            Thread.sleep(1500);
                            return;
                        }
                        for (int i = 0; i < 100; i++) {
                            out.write(": heartbeat\n\n".getBytes());
                            out.flush();
                            Thread.sleep(mode.equals("postContent") ? 200 : 40);
                        }
                    } catch (IOException expectedDisconnect) {
                        disconnected.countDown();
                        // Cancelling or timing out the response closes the owned fixture socket.
                    } catch (InterruptedException shutdown) {
                        Thread.currentThread().interrupt();
                    } finally {
                        x.close();
                    }
                });
        server.start();
        var stopped = new AtomicBoolean();
        var tracker = mock(ChatStreamTracker.class);
        when(tracker.isStopRequested(any())).thenAnswer(i -> stopped.get());
        doAnswer(inv -> {
            if (((String) inv.getArgument(2)).contains("fixture")) received.countDown();
            return null;
        }).when(tracker).broadcast(eq("fixture"), eq("content_delta"), anyString());
        var calls = new AtomicInteger();
        var fallback = mock(ChatModel.class);
        when(fallback.stream(any(Prompt.class)))
                .thenAnswer(
                        i -> {
                            calls.incrementAndGet();
                            return Flux.just(
                                    new ChatResponse(
                                            List.of(
                                                    new Generation(
                                                            new AssistantMessage("fallback")))));
                        });
        var helper = new NodeStreamingChatHelper(tracker, fallback);
        helper.setRetryTimingForTest(1, 2, 10);
        Thread owner = null;
        try {
            if (mode.equals("preStopped")) {
                stopped.set(true);
                assertThrows(
                        CancellationException.class,
                        () -> helper.streamCallSilent(
                                primary(provider, "http://127.0.0.1:" + server.getAddress().getPort()),
                                new Prompt("hi"), "fixture", "test"));
                assertEquals(0, requests.get(), "Pre-stopped call must send no request");
                assertEquals(0, calls.get(), "Pre-stopped call must not fallback");
                assertFalse(Thread.currentThread().isInterrupted());
            } else if (!mode.equals("cancel") && !mode.equals("interrupt") && !mode.equals("postContent")) {
                var r =
                        helper.streamCallSilent(
                                primary(
                                        provider,
                                        "http://127.0.0.1:" + server.getAddress().getPort()),
                                new Prompt("hi"),
                                "fixture",
                                "test");
                assertEquals(1, calls.get(), "Genuine provider failure must retain failover");
                assertEquals("fallback", r.text());
            } else {
                var failure = new AtomicReference<Throwable>();
                var interrupted = new AtomicBoolean();
                var model = primary(provider, "http://127.0.0.1:" + server.getAddress().getPort());
                Thread.Builder ownerBuilder = mode.equals("postContent")
                        ? Thread.ofVirtual() : Thread.ofPlatform();
                owner = ownerBuilder.start(
                                        () -> {
                                            try {
                                                if (mode.equals("postContent")) {
                                                    helper.streamCall(model, new Prompt("hi"), "fixture", "test");
                                                } else {
                                                    helper.streamCallSilent(model, new Prompt("hi"), "fixture", "test");
                                                }
                                            } catch (Throwable e) {
                                                failure.set(e);
                                            } finally {
                                                interrupted.set(
                                                        Thread.currentThread().isInterrupted());
                                            }
                                        });
                assertTrue(entered.await(3, TimeUnit.SECONDS), "Primary HTTP request must start");
                if (mode.equals("postContent")) {
                    assertTrue(received.await(3, TimeUnit.SECONDS), "Helper must receive actual content before stop");
                    // Let the SDK reader enter the next comment-only read before cancellation.
                    Thread.sleep(300);
                }
                if (mode.equals("cancel") || mode.equals("postContent")) stopped.set(true);
                owner.interrupt();
                owner.join(3000);
                assertFalse(owner.isAlive(), "Cancelled helper owner must terminate promptly");
                if (mode.equals("postContent")) {
                    assertTrue(disconnected.await(3, TimeUnit.SECONDS), "Post-content cancellation must disconnect the server");
                }
                assertEquals(1, requests.get(), "SDK must not replay the cancelled request");
                assertEquals(0, calls.get(), "Cancelled primary must not invoke fallback");
                assertInstanceOf(CancellationException.class, failure.get());
                assertTrue(interrupted.get(), "Owner interrupt flag must remain set");
                if (mode.equals("interrupt")) {
                    assertFalse(stopped.get(), "Pure interrupt must not rely on tracker stop state");
                    assertInstanceOf(InterruptedException.class, failure.get().getCause());
                }
            }
        } finally {
            if (owner != null && owner.isAlive()) owner.interrupt();
            server.stop(0);
            workers.shutdownNow();
            if (owner != null) {
                owner.join(3000);
                assertFalse(owner.isAlive(), "Fixture owner must stop");
            }
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS), "Fixture workers must stop");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "openai"})
    void cancelledOwnerDoesNotInvokeFallback(String provider) throws Exception {
        run(provider, "cancel");
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "openai"})
    void postContentCancellationDisconnectsTransport(String provider) throws Exception {
        run(provider, "postContent");
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "openai"})
    void pureInterruptedOwnerDoesNotInvokeFallback(String provider) throws Exception {
        run(provider, "interrupt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "openai"})
    void preStoppedOwnerDoesNotSendPrimaryRequest(String provider) throws Exception {
        run(provider, "preStopped");
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "openai"})
    void genuineReadGapTimeoutStillInvokesFallback(String provider) throws Exception {
        run(provider, "timeout");
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "openai"})
    void genuineHttp503StillInvokesFallback(String provider) throws Exception {
        run(provider, "httpError");
    }
}
