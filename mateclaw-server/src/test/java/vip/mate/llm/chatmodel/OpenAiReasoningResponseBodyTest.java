package vip.mate.llm.chatmodel;

import static org.junit.jupiter.api.Assertions.*;

import okhttp3.MediaType;
import okhttp3.ResponseBody;

import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

class OpenAiReasoningResponseBodyTest {
    @Test
    void fragmentedUtf8AndMultilineEventsNormalizeNullOnlyAndForwardClose() throws Exception {
        String events =
                ": comment\n"
                    + "event: completion\n"
                    + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":null,\n"
                    + "data: \"reasoning\":\"思考\"}}]}\n\n"
                    + "data:"
                    + " {\"choices\":[{\"delta\":{\"reasoning_content\":\"\",\"reasoning\":\"must"
                    + " not replace\"}}]}\n\n"
                    + "data: [DONE]\n\n";
        byte[] bytes = events.getBytes(StandardCharsets.UTF_8);
        AtomicBoolean closed = new AtomicBoolean();
        BufferedSource upstream =
                Okio.buffer(
                        new Source() {
                            private int offset;

                            @Override
                            public long read(Buffer sink, long byteCount) {
                                if (byteCount == 0) return 0;
                                if (offset == bytes.length) return -1;
                                sink.writeByte(bytes[offset++]);
                                return 1;
                            }

                            @Override
                            public Timeout timeout() {
                                return Timeout.NONE;
                            }

                            @Override
                            public void close() {
                                closed.set(true);
                            }
                        });
        ResponseBody original =
                new ResponseBody() {
                    @Override
                    public MediaType contentType() {
                        return MediaType.get("text/event-stream");
                    }

                    @Override
                    public long contentLength() {
                        return bytes.length;
                    }

                    @Override
                    public BufferedSource source() {
                        return upstream;
                    }
                };
        OpenAiReasoningResponseBody body = new OpenAiReasoningResponseBody(original);
        String normalized = body.source().readUtf8();
        assertTrue(normalized.startsWith(": comment\nevent: completion\n"));
        assertTrue(normalized.contains("\"reasoning_content\":\"思考\""));
        assertTrue(
                normalized.contains(
                        "\"reasoning_content\":\"\",\"reasoning\":\"must not replace\""));
        assertTrue(normalized.endsWith("data: [DONE]\n\n"));
        body.close();
        assertTrue(closed.get());
    }
}
