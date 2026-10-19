package vip.mate.llm.chatmodel;

import okhttp3.MediaType;
import okhttp3.ResponseBody;

import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes complete SSE events without buffering the response or changing cancellation ownership.
 */
final class OpenAiReasoningResponseBody extends ResponseBody {
    private final ResponseBody delegate;
    private final BufferedSource source;

    OpenAiReasoningResponseBody(ResponseBody delegate) {
        this.delegate = delegate;
        BufferedSource upstream = delegate.source();
        this.source =
                Okio.buffer(
                        new Source() {
                            private final Buffer pending = new Buffer();

                            @Override
                            public long read(Buffer sink, long byteCount) throws IOException {
                                if (byteCount < 0)
                                    throw new IllegalArgumentException(
                                            "byteCount must be nonnegative");
                                if (byteCount == 0) return 0;
                                if (pending.size() == 0 && !readEvent()) return -1;
                                return pending.read(sink, Math.min(byteCount, pending.size()));
                            }

                            private boolean readEvent() throws IOException {
                                List<String> lines = new ArrayList<>();
                                StringBuilder data = new StringBuilder();
                                String line;
                                while ((line = upstream.readUtf8Line()) != null) {
                                    if (line.isEmpty()) break;
                                    lines.add(line);
                                    if (line.startsWith("data:")) {
                                        if (!data.isEmpty()) data.append('\n');
                                        String value = line.substring(5);
                                        data.append(
                                                value.startsWith(" ") ? value.substring(1) : value);
                                    }
                                }
                                if (line == null && lines.isEmpty()) return false;
                                String originalData = data.toString();
                                String normalized =
                                        OpenAiReasoningResponseNormalizer.normalize(originalData);
                                if (!normalized.equals(originalData)) {
                                    boolean written = false;
                                    for (String eventLine : lines) {
                                        if (eventLine.startsWith("data:")) {
                                            if (!written) {
                                                pending.writeUtf8("data: " + normalized + "\n");
                                                written = true;
                                            }
                                        } else pending.writeUtf8(eventLine + "\n");
                                    }
                                } else
                                    for (String eventLine : lines)
                                        pending.writeUtf8(eventLine + "\n");
                                pending.writeUtf8("\n");
                                return true;
                            }

                            @Override
                            public Timeout timeout() {
                                return upstream.timeout();
                            }

                            @Override
                            public void close() throws IOException {
                                upstream.close();
                            }
                        });
    }

    @Override
    public MediaType contentType() {
        return delegate.contentType();
    }

    @Override
    public long contentLength() {
        return -1;
    }

    @Override
    public BufferedSource source() {
        return source;
    }
}
