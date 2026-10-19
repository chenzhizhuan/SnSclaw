package vip.mate.llm.dashscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Native JSON/SSE transport with subscription-local cancellation and timeout. */
public final class NativeDashScopeTransport {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final ScheduledThreadPoolExecutor READ_DEADLINES = readDeadlines();
    private final String apiKey;
    private final String baseUrl;
    private final HttpClient client;
    private final Duration timeout;

    public NativeDashScopeTransport(
            String apiKey, String baseUrl, HttpClient client, Duration timeout) {
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalArgumentException("DashScope API key is required");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("DashScope timeout must be positive");
        }
        this.apiKey = apiKey.trim();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = timeout;
    }

    public NativeDashScopeTransport(String apiKey, String baseUrl) {
        this(
                apiKey,
                baseUrl,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                Duration.ofMinutes(5));
    }

    static String normalizeBaseUrl(String baseUrl) {
        String url =
                baseUrl == null || baseUrl.isBlank()
                        ? "https://dashscope.aliyuncs.com"
                        : baseUrl.trim();
        int compatible = url.indexOf("/compatible-mode");
        int after = compatible + "/compatible-mode".length();
        if (compatible >= 0 && (after == url.length() || url.charAt(after) == '/')) {
            url = url.substring(0, compatible);
        }
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        URI uri = URI.create(url);
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("DashScope base URL must use HTTP or HTTPS");
        }
        return url;
    }

    JsonNode exchange(String path, Map<String, Object> body) {
        try {
            HttpResponse<InputStream> response =
                    client.send(
                            request(path, body, false), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = readWithDeadline(input, buffer)) != -1)
                    bodyBytes.write(buffer, 0, count);
                return parse(bodyBytes.toString(StandardCharsets.UTF_8), response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DashScope request interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("DashScope request failed: " + e.getMessage(), e);
        }
    }

    private int readWithDeadline(InputStream input, byte[] buffer) throws IOException {
        long started = System.nanoTime();
        long timeoutNanos = timeout.toNanos();
        AtomicBoolean settled = new AtomicBoolean();
        var deadline =
                READ_DEADLINES.schedule(
                        () -> {
                            if (settled.compareAndSet(false, true)) close(input);
                        },
                        timeout.toNanos(),
                        TimeUnit.NANOSECONDS);
        int count;
        try {
            count = input.read(buffer);
        } catch (IOException failure) {
            if (!settled.compareAndSet(false, true)) throw readTimeout(failure);
            throw failure;
        } finally {
            deadline.cancel(false);
        }
        if (!settled.compareAndSet(false, true) || System.nanoTime() - started >= timeoutNanos) {
            throw readTimeout(null);
        }
        return count;
    }

    private static SocketTimeoutException readTimeout(IOException cause) {
        SocketTimeoutException timeout =
                new SocketTimeoutException("DashScope response body read timed out");
        if (cause != null) timeout.initCause(cause);
        return timeout;
    }

    private static ScheduledThreadPoolExecutor readDeadlines() {
        var deadlines =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            Thread thread = new Thread(runnable, "dashscope-read-deadline");
                            thread.setDaemon(true);
                            return thread;
                        });
        // Completed reads must not retain response bodies until their original deadline.
        deadlines.setRemoveOnCancelPolicy(true);
        return deadlines;
    }

    Flux<JsonNode> stream(String path, Map<String, Object> body) {
        return Flux.<JsonNode>create(
                        sink -> {
                            AtomicReference<InputStream> input = new AtomicReference<>();
                            var pending =
                                    client.sendAsync(
                                            request(path, body, true),
                                            HttpResponse.BodyHandlers.ofInputStream());
                            sink.onDispose(
                                    () -> {
                                        pending.cancel(true);
                                        close(input.getAndSet(null));
                                    });
                            pending.whenComplete(
                                    (response, failure) -> {
                                        if (failure != null) {
                                            if (!sink.isCancelled()) sink.error(failure);
                                            return;
                                        }
                                        input.set(response.body());
                                        if (sink.isCancelled()) {
                                            close(input.getAndSet(null));
                                            return;
                                        }
                                        Schedulers.boundedElastic()
                                                .schedule(
                                                        () -> {
                                                            try (InputStream stream =
                                                                            response.body();
                                                                    BufferedReader reader =
                                                                            new BufferedReader(
                                                                                    new InputStreamReader(
                                                                                            stream,
                                                                                            StandardCharsets
                                                                                                    .UTF_8))) {
                                                                if (response.statusCode() < 200
                                                                        || response.statusCode()
                                                                                >= 300) {
                                                                    parse(
                                                                            new String(
                                                                                    stream
                                                                                            .readAllBytes(),
                                                                                    StandardCharsets
                                                                                            .UTF_8),
                                                                            response.statusCode());
                                                                }
                                                                String contentType =
                                                                        response.headers()
                                                                                .firstValue(
                                                                                        "Content-Type")
                                                                                .orElse("");
                                                                if (!contentType
                                                                        .toLowerCase()
                                                                        .contains(
                                                                                "text/event-stream")) {
                                                                    sink.next(
                                                                            parse(
                                                                                    new String(
                                                                                            stream
                                                                                                    .readAllBytes(),
                                                                                            StandardCharsets
                                                                                                    .UTF_8),
                                                                                    response
                                                                                            .statusCode()));
                                                                    sink.complete();
                                                                    return;
                                                                }
                                                                StringBuilder data =
                                                                        new StringBuilder();
                                                                String line;
                                                                while (!sink.isCancelled()
                                                                        && (line =
                                                                                        reader
                                                                                                .readLine())
                                                                                != null) {
                                                                    if (line.isEmpty()) {
                                                                        if (data.length() == 0)
                                                                            continue;
                                                                        String event =
                                                                                data.toString();
                                                                        data.setLength(0);
                                                                        if ("[DONE]"
                                                                                .equals(
                                                                                        event
                                                                                                .trim()))
                                                                            break;
                                                                        sink.next(
                                                                                parse(
                                                                                        event,
                                                                                        response
                                                                                                .statusCode()));
                                                                    } else if (line.startsWith(
                                                                            "data:")) {
                                                                        if (data.length() > 0)
                                                                            data.append('\n');
                                                                        String value =
                                                                                line.substring(5);
                                                                        data.append(
                                                                                value.startsWith(
                                                                                                " ")
                                                                                        ? value
                                                                                                .substring(
                                                                                                        1)
                                                                                        : value);
                                                                    }
                                                                }
                                                                if (!sink.isCancelled()
                                                                        && data.length() > 0
                                                                        && !"[DONE]"
                                                                                .equals(
                                                                                        data.toString()
                                                                                                .trim())) {
                                                                    sink.next(
                                                                            parse(
                                                                                    data.toString(),
                                                                                    response
                                                                                            .statusCode()));
                                                                }
                                                                if (!sink.isCancelled())
                                                                    sink.complete();
                                                            } catch (Exception e) {
                                                                if (!sink.isCancelled())
                                                                    sink.error(e);
                                                            } finally {
                                                                input.set(null);
                                                            }
                                                        });
                                    });
                        })
                .timeout(timeout);
    }

    private HttpRequest request(String path, Map<String, Object> body, boolean stream) {
        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(timeout)
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .header("Accept", stream ? "text/event-stream" : "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            if (stream) builder.header("X-DashScope-SSE", "enable");
            return builder.build();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot encode DashScope request", e);
        }
    }

    private static JsonNode parse(String body, int status) {
        JsonNode result;
        try {
            result = JSON.readTree(body);
        } catch (IOException e) {
            throw new NativeDashScopeException(
                    status, "InvalidResponse", "Provider returned invalid JSON", null);
        }
        if (result == null || !result.isObject()) {
            throw new NativeDashScopeException(
                    status, "InvalidResponse", "Provider returned no JSON object", null);
        }
        if (status < 200 || status >= 300 || !result.path("code").asText("").isBlank()) {
            throw new NativeDashScopeException(
                    status,
                    result.path("code").asText("HTTP_" + status),
                    result.path("message").asText("Provider request failed"),
                    result.path("request_id").asText(null));
        }
        return result;
    }

    private static void close(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }
}
