package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/** Observes native SDK payloads without changing stream events or exposing internal headers. */
final class AnthropicSdkWireInterceptor implements Interceptor {
    static final String CORRELATION_HEADER = "X-MateClaw-Anthropic-Request";
    final ConcurrentMap<String, AnthropicThinkingScope> scopes = new ConcurrentHashMap<>();
    private final Map<String, String> headers;
    private final boolean systemArray;
    private final Duration readTimeout;

    AnthropicSdkWireInterceptor(
            Map<String, String> headers, boolean systemArray, Duration readTimeout) {
        this.headers = Map.copyOf(headers);
        this.systemArray = systemArray;
        this.readTimeout = readTimeout;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String requestId = request.header(CORRELATION_HEADER);
        AnthropicThinkingScope scope = requestId == null ? null : scopes.get(requestId);
        if (requestId != null && scope == null)
            throw new IOException("Provider request scope has ended");
        if (scope != null) scope.cancellation.register(chain.call());
        Request.Builder outgoing = request.newBuilder().removeHeader(CORRELATION_HEADER);
        if (headers.keySet().stream().anyMatch(key -> "Authorization".equalsIgnoreCase(key)))
            outgoing.removeHeader("x-api-key");
        headers.forEach((name, value) -> {
            if (!CORRELATION_HEADER.equalsIgnoreCase(name)) outgoing.header(name, value);
        });
        if (request.body() != null && (scope != null || systemArray)) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            ObjectNode body =
                    (ObjectNode) AnthropicThinkingScope.JSON.readTree(buffer.readByteArray());
            if (systemArray && scope != null && !scope.systemTexts.isEmpty()) {
                JsonNode oldSystem = body.path("system");
                JsonNode cacheControl = null;
                if (oldSystem.isArray()) {
                    for (JsonNode block : oldSystem) {
                        if (block.hasNonNull("cache_control"))
                            cacheControl = block.get("cache_control");
                    }
                }
                ArrayNode system = body.putArray("system");
                scope.systemTexts.forEach(
                        value -> system.addObject().put("type", "text").put("text", value));
                if (cacheControl != null)
                    ((ObjectNode) system.get(system.size() - 1)).set("cache_control", cacheControl);
            }
            if (scope != null) restoreThinking(body, scope);
            outgoing.method(
                    request.method(),
                    RequestBody.create(
                            AnthropicThinkingScope.JSON.writeValueAsBytes(body),
                            request.body().contentType()));
        }
        Response response =
                chain.withConnectTimeout(
                                Math.toIntExact(HttpTimeouts.CONNECT_TIMEOUT.toMillis()),
                                TimeUnit.MILLISECONDS)
                        .withReadTimeout(
                                Math.toIntExact(readTimeout.toMillis()), TimeUnit.MILLISECONDS)
                        .proceed(outgoing.build());
        ResponseBody body = response.body();
        if (scope == null || body == null || !response.isSuccessful()) return response;
        boolean streaming =
                body.contentType() != null
                        && body.contentType().toString().contains("text/event-stream");
        Buffer captured = new Buffer();
        StringBuilder data = new StringBuilder();
        BufferedSource source =
                Okio.buffer(
                        new ForwardingSource(body.source()) {
                            @Override
                            public long read(Buffer sink, long byteCount) throws IOException {
                                long before = sink.size();
                                long read = super.read(sink, byteCount);
                                if (read > 0) {
                                    sink.copyTo(captured, before, read);
                                    if (streaming) {
                                        while (captured.indexOf((byte) '\n') >= 0) {
                                            String line = captured.readUtf8Line();
                                            if (line.isEmpty()) {
                                                if (!data.isEmpty()) {
                                                    scope.accept(
                                                            AnthropicThinkingScope.JSON.readTree(
                                                                    data.toString()));
                                                    data.setLength(0);
                                                }
                                            } else if (line.startsWith("data:")) {
                                                if (!data.isEmpty()) data.append('\n');
                                                data.append(line.substring(5).stripLeading());
                                            }
                                        }
                                    } else {
                                        try {
                                            scope.accept(
                                                    AnthropicThinkingScope.JSON.readTree(
                                                            captured.clone().readByteArray()));
                                        } catch (IOException ignored) {
                                        }
                                    }
                                }
                                return read;
                            }
                        });
        return response.newBuilder()
                .body(
                        new ResponseBody() {
                            @Override
                            public MediaType contentType() {
                                return body.contentType();
                            }

                            @Override
                            public long contentLength() {
                                return body.contentLength();
                            }

                            @Override
                            public BufferedSource source() {
                                return source;
                            }
                        })
                .build();
    }

    private static void restoreThinking(ObjectNode request, AnthropicThinkingScope scope) {
        int assistantIndex = 0;
        for (JsonNode message : request.path("messages")) {
            if (!"assistant".equals(message.path("role").asText())) continue;
            List<Map<String, Object>> saved =
                    assistantIndex < scope.history.size()
                            ? scope.history.get(assistantIndex)
                            : List.of();
            assistantIndex++;
            if (saved.isEmpty()) continue;
            JsonNode content = message.path("content");
            List<JsonNode> ordinary = new ArrayList<>();
            if (content.isArray()) {
                content.forEach(
                        block -> {
                            String type = block.path("type").asText();
                            if (!"thinking".equals(type) && !"redacted_thinking".equals(type))
                                ordinary.add(block);
                        });
            } else if (content.isTextual() && !content.asText().isBlank()) {
                ordinary.add(
                        AnthropicThinkingScope.JSON.valueToTree(
                                Map.of("type", "text", "text", content.asText())));
            }
            ArrayNode restored = ((ObjectNode) message).putArray("content");
            saved.forEach(block -> restored.add(AnthropicThinkingScope.JSON.valueToTree(block)));
            ordinary.forEach(restored::add);
        }
    }
}
