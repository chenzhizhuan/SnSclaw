package vip.mate.agent.runtime.dsh;


import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded line-delimited SDK transport. Synthetic tests do not certify a DSH release. */
public final class DshSdkProcess implements AutoCloseable {
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PENDING_BYTES = 16 * 1024 * 1024;
    private final Process process;
    private final ObjectMapper mapper;
    private final BufferedWriter writer;
    private final BlockingQueue<Object> frames = new ArrayBlockingQueue<>(4096);
    private final AtomicInteger pendingBytes = new AtomicInteger();
    private final String secret;
    private final StringBuilder stderr = new StringBuilder();
    private volatile boolean closed;
    private volatile boolean initialized;
    private volatile IOException failure;

    public DshSdkProcess(Process process, ObjectMapper mapper, String secret) {
        this.process = process; this.mapper = mapper; this.secret = secret;
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startReader("dsh-sdk-stdout", this::readFrames);
        startReader("dsh-sdk-stderr", this::readErrors);
    }

    private static void startReader(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
    }

    public void send(String method, String id, Map<String, Object> params) throws IOException {
        send(Map.of("jsonrpc", "2.0", "method", method, "id", id, "params", params));
    }

    public synchronized void send(Map<String, Object> message) throws IOException {
        writer.write(mapper.writeValueAsString(message)); writer.newLine(); writer.flush();
    }

    public JsonNode initialize(Map<String, Object> params, Duration timeout) throws Exception {
        String id = "initialize-" + UUID.randomUUID();
        send("initialize", id, params);
        long deadline = System.nanoTime() + bounded(timeout).toNanos();
        for (;;) {
            if (System.nanoTime() >= deadline) throw new IOException("DSH_TIMEOUT");
            JsonNode frame = next(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            if (!frame.has("id")) continue;
            if (!id.equals(frame.path("id").asText())) throw new IOException("DSH_HANDSHAKE_ID_MISMATCH");
            if (frame.has("error")) throw new IOException("DSH_INITIALIZE_REJECTED: " + redact(frame.path("error").path("message").asText("runtime error"), secret));
            if (!"deepseek-harness-sdk-runtime".equals(frame.path("result").path("serverInfo").path("name").asText()))
                throw new IOException("DSH_HANDSHAKE_IDENTITY_MISMATCH");
            initialized = true;
            return frame.path("result");
        }
    }

    public JsonNode next(Duration timeout) throws Exception {
        if (failure != null) throw failure;
        Object value = frames.poll(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        if (failure != null) throw failure;
        if (value == null) throw new IOException("DSH_TIMEOUT");
        if (value instanceof IOException error) throw error;
        byte[] bytes = (byte[]) value;
        pendingBytes.addAndGet(-bytes.length);
        String line = new String(bytes, StandardCharsets.UTF_8);
        if (line.stripLeading().startsWith("dsh web:")) throw new IOException("DSH_WRONG_ENTRYPOINT: Web profile banner received; select sdk");
        JsonNode node;
        try { node = mapper.readTree(line); } catch (Exception error) { throw new IOException("DSH_INVALID_FRAME: expected SDK JSON-RPC"); }
        if (node == null || !node.isObject() || !"2.0".equals(node.path("jsonrpc").asText())) throw new IOException("DSH_INVALID_FRAME: expected JSON-RPC 2.0");
        return node;
    }

    private void readFrames() {
        try (InputStream input = process.getInputStream()) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while (!closed && (count = input.read(buffer)) >= 0) {
                for (int i = 0; i < count; i++) {
                    if (buffer[i] == '\n') {
                        byte[] frame = line.toByteArray(); line.reset();
                        if (pendingBytes.addAndGet(frame.length) > MAX_PENDING_BYTES || !frames.offer(frame))
                            throw new IOException("DSH_NOTIFICATION_OVERFLOW");
                    } else {
                        if (line.size() >= MAX_FRAME_BYTES) throw new IOException("DSH_FRAME_TOO_LARGE");
                        line.write(buffer[i]);
                    }
                }
            }
            if (!closed) frames.offer(new IOException("DSH_EOF: runtime closed before completion"));
        } catch (IOException error) {
            if (!closed) { failure = error; frames.offer(error); DshRuntimeService.cancelProcess(process); }
        }
    }

    private void readErrors() {
        try (Reader reader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048]; int count;
            while ((count = reader.read(buffer)) >= 0) synchronized (stderr) {
                stderr.append(buffer, 0, count);
                if (stderr.length() > 65536) stderr.delete(0, stderr.length() - 65536);
            }
        } catch (IOException ignored) { }
    }

    public String diagnostics() { synchronized (stderr) { return redact(stderr.toString(), secret); } }

    public static String redact(String text, String secret) {
        if (text == null) return "";
        String safe = secret == null || secret.isBlank() ? text : text.replace(secret, "[REDACTED]");
        return safe.replaceAll("(?i)(authorization[\"']?\\s*[:=]\\s*[\"']?)(?:Bearer|Basic)\\s+[^\\s,\"'}]+", "$1[REDACTED]")
                .replaceAll("(?i)([?&](?:token|key|api_key|access_token)=)[^\\s&#]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:authorization|api[_-]?key|password|secret|token)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,\\\"'}]+", "$1[REDACTED]");
    }

    private static Duration bounded(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) return Duration.ofSeconds(30);
        return timeout.compareTo(Duration.ofMinutes(2)) > 0 ? Duration.ofMinutes(2) : timeout;
    }

    @Override public void close() {
        if (closed) return;
        try {
            if (initialized && process.isAlive()) {
                send("shutdown", "shutdown-" + UUID.randomUUID(), Map.of());
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) { }
        finally { closed = true; DshRuntimeService.cancelProcess(process); }
    }
}
