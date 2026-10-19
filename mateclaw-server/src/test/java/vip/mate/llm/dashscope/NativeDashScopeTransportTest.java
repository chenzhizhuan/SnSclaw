package vip.mate.llm.dashscope;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import reactor.core.Disposable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

class NativeDashScopeTransportTest {
    @Test
    void cancellingBeforeResponseHeadersCancelsPendingHttpRequest() {
        TestClient client = new TestClient();
        NativeDashScopeTransport transport =
                new NativeDashScopeTransport(
                        "test-key", "https://example.test", client, Duration.ofSeconds(2));
        Disposable subscription = transport.stream("/chat", Map.of()).subscribe();
        subscription.dispose();
        assertTrue(client.pending.isCancelled());
    }

    @Test
    void cancellingAfterFirstEventClosesResponseBody() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body =
                new ByteArrayInputStream(
                        "data: {\"ok\":true}\n\ndata: {\"ok\":true}\n\n"
                                .getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                };
        TestClient client = new TestClient();
        client.pending.complete(new Response(body));
        NativeDashScopeTransport transport =
                new NativeDashScopeTransport(
                        "test-key", "https://example.test", client, Duration.ofSeconds(2));
        assertEquals(
                1,
                transport.stream("/chat", Map.of())
                        .take(1)
                        .collectList()
                        .block(Duration.ofSeconds(3))
                        .size());
        assertTrue(closed.get());
    }

    @Test
    void idleTimeoutCancelsPendingHttpRequest() {
        TestClient client = new TestClient();
        NativeDashScopeTransport transport =
                new NativeDashScopeTransport(
                        "test-key", "https://example.test", client, Duration.ofMillis(50));
        assertThrows(
                RuntimeException.class,
                () ->
                        transport.stream("/chat", Map.of())
                                .collectList()
                                .block(Duration.ofSeconds(2)));
        assertTrue(client.pending.isCancelled());
    }

    @Test
    void compatibleModeUrlsNormalizeToNativeHost() {
        assertEquals(
                "https://example.test",
                NativeDashScopeTransport.normalizeBaseUrl(
                        " https://example.test/compatible-mode/v1/ "));
        assertEquals(
                "https://dashscope.aliyuncs.com", NativeDashScopeTransport.normalizeBaseUrl(null));
        assertEquals(
                "https://example.test",
                NativeDashScopeTransport.normalizeBaseUrl("https://example.test/compatible-mode"));
    }

    private static final class TestClient extends HttpClient {
        private final CompletableFuture<HttpResponse<InputStream>> pending =
                new CompletableFuture<>();

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException(
                    "Only asynchronous transport is exercised here");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) pending;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushHandler) {
            return sendAsync(request, handler);
        }
    }

    private record Response(InputStream body) implements HttpResponse<InputStream> {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(URI.create("https://example.test")).build();
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(
                    Map.of("Content-Type", List.of("text/event-stream")), (a, b) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://example.test");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
