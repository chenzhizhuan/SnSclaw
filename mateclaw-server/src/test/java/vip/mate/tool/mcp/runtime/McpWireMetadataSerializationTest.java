package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.execution.evidence.service.ExecutionObservationSink;
import vip.mate.tool.builtin.ProgressiveToolBridgeTool;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class McpWireMetadataSerializationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JacksonMcpJsonMapper protocol = new JacksonMcpJsonMapper(mapper);
    private final McpSyncClient client = mock(McpSyncClient.class);
    private final McpSchema.Tool tool = McpSchema.Tool.builder().name("owned_echo")
            .description("wire metadata fixture").inputSchema(protocol, "{\"type\":\"object\",\"properties\":{}}").build();

    private void requireSerializableWireRequest() {
        when(client.callTool(any())).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            String wire = protocol.writeValueAsString(new McpSchema.JSONRPCRequest("2.0", "tools/call", "fixture-id", request));
            var meta = mapper.readTree(wire).path("params").path("_meta");
            assertEquals("fixture-tenant", meta.path("tenant").asText());
            assertEquals(2147483648L, meta.path("sequence").longValue());
            assertFalse(meta.has("mateclaw.executionObservationSink"));
            assertFalse(meta.has(ProgressiveToolBridgeTool.SCOPED_TOOL_CALLBACKS_CONTEXT_KEY));
            return new McpSchema.CallToolResult("echo", false);
        });
    }

    @Test
    void genericAdapterKeepsBusinessMetadataAndExcludesLocalCapabilities() {
        requireSerializableWireRequest();
        var callback = new ApplicationMcpToolCallback(client, tool);
        ToolContext context = new ExecutionObservationSink(false).attach(new ToolContext(Map.of(
                "tenant", "fixture-tenant", "sequence", 2147483648L,
                ProgressiveToolBridgeTool.SCOPED_TOOL_CALLBACKS_CONTEXT_KEY, Map.of("owned_echo", callback))));
        assertDoesNotThrow(() -> callback.call("{}", context));
        verify(client, times(1)).callTool(any());
    }

    @Test
    void progressCaptureAdapterKeepsBusinessMetadataAndExcludesLocalCapabilities() {
        requireSerializableWireRequest();
        var delegate = new ApplicationMcpToolCallback(client, tool);
        var callback = new ProgressAwareMcpToolCallback(delegate, client, "owned_echo", mapper);
        ToolContext context = new ExecutionObservationSink(false).attach(new McpToolResultCapture().attach(new ToolContext(Map.of(
                "tenant", "fixture-tenant", "sequence", 2147483648L,
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "fixture-progress",
                ProgressiveToolBridgeTool.SCOPED_TOOL_CALLBACKS_CONTEXT_KEY, Map.of("owned_echo", delegate)))));
        assertDoesNotThrow(() -> callback.call("{}", context));
        verify(client, times(1)).callTool(any());
    }
}
