package vip.mate.llm.chatmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.openai.core.RequestOptions;
import com.openai.core.http.HttpMethod;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import okhttp3.Protocol;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class PinnedOpenAiHttpClientTest {
    @Test
    void tlsServerAdvertisingHttp2UsesHttp11() throws Exception {
        HeldCertificate certificate =
                new HeldCertificate.Builder()
                        .commonName("localhost")
                        .addSubjectAlternativeName("localhost")
                        .build();
        HandshakeCertificates serverTls =
                new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientTls =
                new HandshakeCertificates.Builder()
                        .addTrustedCertificate(certificate.certificate())
                        .build();
        AtomicReference<Protocol> negotiated = new AtomicReference<>();
        AtomicInteger observations = new AtomicInteger();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(
                        new ObservationHandler<>() {
                            @Override
                            public boolean supportsContext(Observation.Context context) {
                                return true;
                            }

                            @Override
                            public void onStart(Observation.Context context) {
                                observations.incrementAndGet();
                            }
                        });
        try (MockWebServer server = new MockWebServer()) {
            server.useHttps(serverTls.sslSocketFactory(), false);
            server.setProtocols(List.of(Protocol.HTTP_2, Protocol.HTTP_1_1));
            server.enqueue(new MockResponse().setBody("reply"));
            server.start();
            SpringAiOpenAiHttpClient observed =
                    SpringAiOpenAiHttpClient.builder()
                            .observationRegistry(registry)
                            .timeout(Duration.ofSeconds(2))
                            .sslSocketFactory(clientTls.sslSocketFactory())
                            .trustManager(clientTls.trustManager())
                            .interceptor(
                                    chain -> {
                                        var response = chain.proceed(chain.request());
                                        negotiated.set(response.protocol());
                                        return response;
                                    })
                            .build();
            try (PinnedOpenAiHttpClient transport = new PinnedOpenAiHttpClient(observed);
                    HttpResponse response =
                            transport.execute(request(server), RequestOptions.none())) {
                assertEquals("reply", new String(response.body().readAllBytes()));
                assertEquals(Protocol.HTTP_1_1, negotiated.get());
                assertEquals(1, observations.get());
                assertEquals(List.of(Protocol.HTTP_1_1), transport.client().protocols());
                assertFalse(transport.client().retryOnConnectionFailure());
            }
        }
    }

    @Test
    void transportNeverEnablesImplicitConnectionRetries() {
        try (PinnedOpenAiHttpClient transport = transport()) {
            assertFalse(transport.client().retryOnConnectionFailure());
        }
    }

    @Test
    void acceptedRequestIsNotReplayedAfterConnectionFailure() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(
                    new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            server.enqueue(new MockResponse().setBody("unexpected retry"));
            server.start();
            try (PinnedOpenAiHttpClient transport = transport()) {
                assertThrows(
                        RuntimeException.class,
                        () -> transport.execute(request(server), RequestOptions.none()));
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test
    void asyncCancellationCancelsInFlightCall() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            try (PinnedOpenAiHttpClient transport = transport()) {
                var response = transport.executeAsync(request(server), RequestOptions.none());
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
                assertTrue(response.cancel(true));
                assertTrue(response.isCancelled());
                for (int i = 0;
                        i < 50 && transport.client().dispatcher().runningCallsCount() != 0;
                        i++) Thread.sleep(20);
                assertEquals(0, transport.client().dispatcher().runningCallsCount());
            }
        }
    }

    @Test
    void requestBodyClosesWhenSyncOrAsyncCallConstructionFails() {
        AtomicInteger closes = new AtomicInteger();
        HttpRequestBody body =
                new HttpRequestBody() {
                    @Override
                    public void writeTo(OutputStream output) {}

                    @Override
                    public String contentType() {
                        return "application/json";
                    }

                    @Override
                    public long contentLength() {
                        return 0;
                    }

                    @Override
                    public boolean repeatable() {
                        return true;
                    }

                    @Override
                    public void close() {
                        closes.incrementAndGet();
                    }
                };
        HttpRequest request =
                HttpRequest.builder()
                        .method(HttpMethod.POST)
                        .baseUrl("invalid-url")
                        .body(body)
                        .build();
        try (PinnedOpenAiHttpClient transport = transport()) {
            assertThrows(
                    RuntimeException.class,
                    () -> transport.execute(request, RequestOptions.none()));
            assertEquals(1, closes.get());
            assertThrows(
                    RuntimeException.class,
                    () -> transport.executeAsync(request, RequestOptions.none()).join());
            assertEquals(2, closes.get());
        }
    }

    private PinnedOpenAiHttpClient transport() {
        return new PinnedOpenAiHttpClient(
                SpringAiOpenAiHttpClient.builder()
                        .observationRegistry(ObservationRegistry.NOOP)
                        .timeout(Duration.ofSeconds(2))
                        .build());
    }

    private HttpRequest request(MockWebServer server) {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .baseUrl(server.url("/").toString())
                .build();
    }
}
