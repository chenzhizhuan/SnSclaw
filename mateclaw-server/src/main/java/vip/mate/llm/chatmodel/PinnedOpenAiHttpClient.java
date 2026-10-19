/*
 * HTTP adaptation follows Spring AI's SpringAiOpenAiHttpClient and the public OpenAI SDK contract.
 * Copyright 2023-present the original author or authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package vip.mate.llm.chatmodel;

import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import com.openai.errors.OpenAIIoException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import okio.BufferedSink;

import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** SDK transport retaining Spring AI observations while enforcing compatible gateway policies. */
final class PinnedOpenAiHttpClient implements HttpClient {
    static final String STREAM_HEADER = "X-MateClaw-Stream-Cancellation";
    private final ConcurrentMap<String, StreamCancellation> streams = new ConcurrentHashMap<>();
    private final SpringAiOpenAiHttpClient owner;
    private final OkHttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean();

    PinnedOpenAiHttpClient(SpringAiOpenAiHttpClient observed) {
        owner = observed;
        client =
                observed.getOkHttpClient()
                        .newBuilder()
                        .protocols(List.of(Protocol.HTTP_1_1))
                        .retryOnConnectionFailure(false)
                        .build();
    }

    StreamCancellation startStream() {
        StreamCancellation scope = new StreamCancellation();
        if (closed.get()) throw new IllegalStateException("Provider transport is closed");
        streams.put(scope.token(), scope);
        if (closed.get()) {
            scope.cancel();
            scope.close();
            throw new IllegalStateException("Provider transport is closed");
        }
        return scope;
    }

    int activeStreamCount() {
        return streams.size();
    }

    final class StreamCancellation implements AutoCloseable {
        private final String token = UUID.randomUUID().toString();
        private final ProviderCallCancellation cancellation = new ProviderCallCancellation();

        String token() {
            return token;
        }

        void register(Call call) {
            cancellation.register(call);
        }

        void cancel() {
            cancellation.cancel();
        }

        @Override
        public void close() {
            cancellation.close();
            streams.remove(token, this);
        }
    }

    OkHttpClient client() {
        return client;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options) {
        try {
            return response(call(request, options).execute());
        } catch (IOException failure) {
            throw new OpenAIIoException("Request failed", failure);
        } finally {
            closeBody(request);
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(
            HttpRequest request, RequestOptions options) {
        CompletableFuture<HttpResponse> result = new CompletableFuture<>();
        Call call;
        try {
            call = call(request, options);
        } catch (RuntimeException | Error failure) {
            closeBody(request);
            throw failure;
        }
        result.whenComplete(
                (response, failure) -> {
                    if (failure instanceof CancellationException) call.cancel();
                    closeBody(request);
                });
        try {
            call.enqueue(
                    new Callback() {
                        @Override
                        public void onResponse(Call call, Response response) {
                            HttpResponse converted = response(response);
                            if (!result.complete(converted)) converted.close();
                        }

                        @Override
                        public void onFailure(Call call, IOException failure) {
                            result.completeExceptionally(
                                    new OpenAIIoException("Request failed", failure));
                        }
                    });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private Call call(HttpRequest request, RequestOptions options) {
        OkHttpClient.Builder perCall = client.newBuilder();
        Timeout timeout = options.getTimeout();
        if (timeout != null)
            perCall.connectTimeout(timeout.connect())
                    .readTimeout(timeout.read())
                    .writeTimeout(timeout.write());
        // The configured timeout limits read gaps; complete healthy bodies have no overall
        // deadline.
        perCall.callTimeout(Duration.ZERO);
        OkHttpClient resolved = perCall.build();
        HttpRequestBody source = request.body();
        RequestBody body =
                source == null
                        ? null
                        : new RequestBody() {
                            @Override
                            public MediaType contentType() {
                                return source.contentType() == null
                                        ? null
                                        : MediaType.parse(source.contentType());
                            }

                            @Override
                            public long contentLength() {
                                return source.contentLength();
                            }

                            @Override
                            public boolean isOneShot() {
                                return !source.repeatable();
                            }

                            @Override
                            public void writeTo(BufferedSink sink) {
                                source.writeTo(sink.outputStream());
                            }
                        };
        if (body == null && List.of("POST", "PUT", "PATCH").contains(request.method().name()))
            body = RequestBody.create(new byte[0], null);
        Request.Builder outgoing =
                new Request.Builder().url(request.url()).method(request.method().name(), body);
        Headers headers = request.headers();
        StreamCancellation streamScope = null;
        for (String name : headers.names()) {
            if (STREAM_HEADER.equalsIgnoreCase(name)) {
                String token = headers.values(name).getFirst();
                streamScope = streams.get(token);
                if (streamScope == null)
                    throw new CancellationException("Stream cancellation scope has ended");
            } else {
                for (String value : headers.values(name)) outgoing.addHeader(name, value);
            }
        }
        if (!headers.names().contains("X-Stainless-Read-Timeout")
                && resolved.readTimeoutMillis() != 0)
            outgoing.addHeader(
                    "X-Stainless-Read-Timeout",
                    Long.toString(Duration.ofMillis(resolved.readTimeoutMillis()).getSeconds()));
        if (!headers.names().contains("X-Stainless-Timeout") && resolved.callTimeoutMillis() != 0)
            outgoing.addHeader(
                    "X-Stainless-Timeout",
                    Long.toString(Duration.ofMillis(resolved.callTimeoutMillis()).getSeconds()));
        Call call = resolved.newCall(outgoing.build());
        if (streamScope != null) streamScope.register(call);
        return call;
    }

    private static HttpResponse response(Response response) {
        Headers.Builder headers = Headers.builder();
        for (int i = 0; i < response.headers().size(); i++)
            headers.put(response.headers().name(i), response.headers().value(i));
        Headers copied = headers.build();
        return new HttpResponse() {
            @Override
            public int statusCode() {
                return response.code();
            }

            @Override
            public Headers headers() {
                return copied;
            }

            @Override
            public InputStream body() {
                return response.body() == null
                        ? InputStream.nullInputStream()
                        : response.body().byteStream();
            }

            @Override
            public void close() {
                response.close();
            }
        };
    }

    private static void closeBody(HttpRequest request) {
        if (request.body() != null) request.body().close();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            streams.values().forEach(scope -> {
                scope.cancel();
                scope.close();
            });
            owner.close();
        }
    }
}
