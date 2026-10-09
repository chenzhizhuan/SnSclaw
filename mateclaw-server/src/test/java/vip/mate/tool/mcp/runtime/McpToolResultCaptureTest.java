package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpToolResultCaptureTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsExactByteLimitAndRejectsOneByteMore() throws Exception {
        int overhead = mapper.writeValueAsBytes(Map.of("text", "")).length;
        var capture = new McpToolResultCapture();
        String text = "x".repeat(McpToolResultCapture.MAX_STRUCTURED_BYTES - overhead);
        capture.capture(McpSchema.CallToolResult.builder().structuredContent(Map.of("text", text)).build(), mapper);
        assertNotNull(capture.structuredContent());
        capture.capture(McpSchema.CallToolResult.builder().structuredContent(Map.of("text", text + "x")).build(), mapper);
        assertNull(capture.structuredContent());
    }

    @Test
    void capturedResponseIsDetachedAndErrorClearsIt() {
        Map<String, Object> nested = new HashMap<>(Map.of("value", "before"));
        var capture = new McpToolResultCapture();
        capture.capture(McpSchema.CallToolResult.builder().structuredContent(Map.of("nested", nested)).build(), mapper);
        nested.put("value", "after");
        assertEquals(Map.of("nested", Map.of("value", "before")), capture.structuredContent());
        capture.capture(McpSchema.CallToolResult.builder().structuredContent(nested).isError(true).build(), mapper);
        assertTrue(capture.isError());
        assertNull(capture.structuredContent());
    }
}
