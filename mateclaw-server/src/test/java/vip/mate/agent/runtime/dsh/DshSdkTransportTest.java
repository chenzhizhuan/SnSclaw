package vip.mate.agent.runtime.dsh;

import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic executables test deterministic protocol failure paths, not release support. */
class DshSdkTransportTest {
    @TempDir Path directory;
    @Test void rejectsWebBannerWithoutLeakingToken() throws Exception {
        Process process = process("echo 'dsh web: http://localhost/?token=secret123'; sleep 30");
        try (var sdk = new DshSdkProcess(process, new ObjectMapper(), "secret123")) {
            Exception error = assertThrows(Exception.class, () -> sdk.initialize(Map.of(), Duration.ofSeconds(3)));
            assertTrue(error.getMessage().contains("WRONG_ENTRYPOINT"), error.getMessage());
            assertFalse(error.getMessage().contains("secret123"));
        }
        assertFalse(process.isAlive());
    }
    @Test void timesOutAndReapsSilentProcess() throws Exception {
        Process process = process("sleep 30");
        try (var sdk = new DshSdkProcess(process, new ObjectMapper(), "")) {
            Exception error = assertThrows(Exception.class, () -> sdk.initialize(Map.of(), Duration.ofMillis(100)));
            assertEquals("DSH_TIMEOUT", error.getMessage());
        }
        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
    }
    @Test void rejectsWrongResponseId() throws Exception {
        Process process = process("read request; echo '{\"jsonrpc\":\"2.0\",\"id\":\"wrong\",\"result\":{}}'");
        try (var sdk = new DshSdkProcess(process, new ObjectMapper(), "")) {
            Exception error = assertThrows(Exception.class, () -> sdk.initialize(Map.of(), Duration.ofSeconds(1)));
            assertEquals("DSH_HANDSHAKE_ID_MISMATCH", error.getMessage());
        }
    }
    @Test void acceptsOnlySdkIdentityAndShutsDown() throws Exception {
        Process process = process("""
                read request
                id=$(printf '%s' "$request" | sed -n 's/.*"id":"\\([^"]*\\)".*/\\1/p')
                printf '{"jsonrpc":"2.0","id":"%s","result":{"serverInfo":{"name":"deepseek-harness-sdk-runtime","version":"0.0.1"}}}\\n' "$id"
                read shutdown
                """);
        try (var sdk = new DshSdkProcess(process, new ObjectMapper(), "")) {
            assertEquals("0.0.1", sdk.initialize(Map.of(), Duration.ofSeconds(2)).path("serverInfo").path("version").asText());
        }
        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
    }
    @Test void redactsCredentialsAndUrlTokens() {
        String diagnostic = DshSdkProcess.redact("api_key=abc https://host/?token=xyz bearer-secret", "bearer-secret");
        assertFalse(diagnostic.contains("abc")); assertFalse(diagnostic.contains("xyz")); assertFalse(diagnostic.contains("bearer-secret"));
    }
    @Test void rejectsOversizedFrameBeforeParsingIt() throws Exception {
        Process process = process("dd if=/dev/zero bs=1048576 count=17 2>/dev/null; sleep 30");
        try (var sdk = new DshSdkProcess(process, new ObjectMapper(), "")) {
            Exception error = assertThrows(Exception.class, () -> sdk.next(Duration.ofSeconds(5)));
            assertEquals("DSH_FRAME_TOO_LARGE", error.getMessage());
        }
        assertFalse(process.isAlive());
    }
    @Test void stderrIsBoundedAndDoesNotBlockHandshakeTimeout() throws Exception {
        Process process = process("dd if=/dev/zero bs=1024 count=128 1>&2 2>/dev/null; sleep 30");
        try (var sdk = new DshSdkProcess(process, new ObjectMapper(), "")) {
            assertThrows(Exception.class, () -> sdk.initialize(Map.of(), Duration.ofMillis(300)));
            assertTrue(sdk.diagnostics().length() <= 65536);
        }
    }
    private Process process(String body) throws Exception {
        Path script = Files.writeString(directory.resolve("synthetic-runtime"), "#!/bin/sh\n" + body + "\n");
        script.toFile().setExecutable(true);
        return new ProcessBuilder(script.toString()).start();
    }
}
