package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Captured npm release SDK calls on macOS arm64/Node 22.20, Messages API, 2026-09-29.
 * Replay state signatures removed. These recordings do not validate Linux/WSL or tools. */
class DshRecordedProtocolTest {
    @ParameterizedTest @ValueSource(strings = {"0.1.7-rc.2", "0.2.0-rc.1"})
    void recordedModelReplyCorrelatesPreResponseReceiptAndIncludesCachedInput(String version) throws Exception {
        var mapper = new ObjectMapper();
        try (var input = getClass().getResourceAsStream("/dsh/" + version + "-sdk.jsonl")) {
            assertNotNull(input);
            var frames = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().map(line -> {
                try { return mapper.readTree(line); } catch (Exception error) { throw new IllegalStateException(error); }
            }).toList();
            String root = frames.stream().filter(frame -> frame.path("params").has("sessionId")).findFirst().orElseThrow().path("params").path("sessionId").asText();
            var turn = new DshSdkTurn(root, "2");
            for (var frame : frames) turn.accept(frame);
            assertTrue(turn.complete());
            assertEquals(RuntimeEventType.COMPLETED, turn.terminalType());
            assertEquals("SDK_OK", turn.answer());
            assertEquals(5421, turn.inputTokens());
            assertEquals(4, turn.outputTokens());
        }
    }
}
