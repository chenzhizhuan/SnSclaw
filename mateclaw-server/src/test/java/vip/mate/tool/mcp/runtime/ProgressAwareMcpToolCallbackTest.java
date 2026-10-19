package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * White-box tests for {@link ProgressAwareMcpToolCallback}.
 *
 * <p>Covers the two code paths:
 * <ol>
 *   <li>progressToken present in ToolContext → direct McpSyncClient.callTool() with injected meta</li>
 *   <li>progressToken absent → delegates to inner callback (backward-compatible)</li>
 * </ol>
 */
class ProgressAwareMcpToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallback delegate;
    private McpSyncClient mcpClient;
    private ProgressAwareMcpToolCallback wrapper;

    @BeforeEach
    void setUp() {
        delegate = mock(ToolCallback.class);
        mcpClient = mock(McpSyncClient.class);
        when(delegate.getToolDefinition()).thenReturn(
                DefaultToolDefinition.builder().name("search").description("desc").inputSchema("{}").build());
        when(delegate.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        wrapper = new ProgressAwareMcpToolCallback(delegate, mcpClient, "search", MAPPER);
    }

    @Test
    @DisplayName("getToolDefinition delegates to inner callback")
    void delegatesGetToolDefinition() {
        assertEquals("search", wrapper.getToolDefinition().name());
        verify(delegate).getToolDefinition();
    }

    @Test
    @DisplayName("getToolMetadata delegates to inner callback")
    void delegatesGetToolMetadata() {
        assertNotNull(wrapper.getToolMetadata());
        verify(delegate).getToolMetadata();
    }

    @Test
    @DisplayName("call(toolInput) without ToolContext delegates to inner")
    void callWithoutToolContextDelegates() {
        when(delegate.call("{}")).thenReturn("result");
        assertEquals("result", wrapper.call("{}"));
        verify(delegate).call("{}");
        verifyNoInteractions(mcpClient);
    }

    @Test
    @DisplayName("call with ToolContext but without progressToken delegates to inner")
    void callWithoutProgressTokenDelegates() {
        ToolContext ctx = new ToolContext(Map.of());
        when(delegate.call("{}", ctx)).thenReturn("delegated");
        assertEquals("delegated", wrapper.call("{}", ctx));
        verify(delegate).call("{}", ctx);
        verifyNoInteractions(mcpClient);
    }

    @Test
    @DisplayName("call with null ToolContext delegates to inner")
    void callWithNullToolContextDelegates() {
        when(delegate.call("{}", null)).thenReturn("null_ctx");
        assertEquals("null_ctx", wrapper.call("{}", (ToolContext) null));
        verify(delegate).call("{}", (ToolContext) null);
        verifyNoInteractions(mcpClient);
    }

    @Test
    @DisplayName("call with progressToken in ToolContext calls McpSyncClient directly with meta injected")
    void callWithProgressTokenUsesMcpClient() {
        ToolContext ctx = new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "pt-uuid-123"));
        McpSchema.TextContent textContent = new McpSchema.TextContent("mcp result");
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(List.of(textContent), false);
        when(mcpClient.callTool(any())).thenReturn(result);

        String output = wrapper.call("{\"q\":\"hello\"}", ctx);

        assertEquals("mcp result", output);
        verify(mcpClient).callTool(any(McpSchema.CallToolRequest.class));
        verify(delegate, never()).call(any(), any());
    }

    @Test
    @DisplayName("progressToken present but blank → delegates (edge case)")
    void blankProgressTokenDelegates() {
        ToolContext ctx = new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "   "));
        when(delegate.call("{}", ctx)).thenReturn("fallback");
        assertEquals("fallback", wrapper.call("{}", ctx));
        verify(delegate).call("{}", ctx);
        verifyNoInteractions(mcpClient);
    }

    @Test
    @DisplayName("transport failure never replays a possibly completed MCP tool")
    void mcpClientFailureNeverReplaysDelegate() {
        ToolContext ctx = new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "tok"));
        RuntimeException failure = new RuntimeException("connection lost after execution");
        when(mcpClient.callTool(any())).thenThrow(failure);
        ToolExecutionException thrown = assertThrows(ToolExecutionException.class, () -> wrapper.call("{}", ctx));
        assertSame(failure, thrown.getCause());
        verify(mcpClient, times(1)).callTool(any());
        verify(delegate, never()).call(any(), any());
    }

    @Test
    void protocolErrorFailsWithoutReplayAndPreservesErrorCapture() {
        McpToolResultCapture capture = new McpToolResultCapture();
        ToolContext ctx = capture.attach(new ToolContext(Map.of(ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "tok")));
        when(mcpClient.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("denied")))
                .structuredContent(Map.of("hidden", "error payload")).isError(true).build());
        assertThrows(ToolExecutionException.class, () -> wrapper.call("{}", ctx));
        assertTrue(capture.isError());
        assertNull(capture.structuredContent());
        verify(mcpClient, times(1)).callTool(any());
        verify(delegate, never()).call(any(), any());
    }

    @Test
    void progressMetadataRetainsApplicationContextAndFiltersInternalEntries() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tenant", "t1");
        metadata.put("identity", Map.of("userId", "u1"));
        metadata.put("exchange", new Object());
        metadata.put("empty", null);
        metadata.put("progressToken", "untrusted");
        metadata.put(ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "real-token");
        McpToolResultCapture capture = new McpToolResultCapture();
        ToolContext ctx = capture.attach(new ToolContext(metadata));
        when(mcpClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(List.of(), false));
        wrapper.call("{}", ctx);
        ArgumentCaptor<McpSchema.CallToolRequest> request = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(mcpClient, times(1)).callTool(request.capture());
        assertEquals(Map.of("tenant", "t1", "identity", Map.of("userId", "u1"), "progressToken", "real-token"),
                request.getValue().meta());
    }

    @Test
    void captureOnlyMetadataRetainsApplicationContextWithoutInternalCapture() {
        ToolContext ctx = new McpToolResultCapture().attach(new ToolContext(Map.of("tenant", "t1")));
        when(mcpClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(List.of(), false));
        wrapper.call("{}", ctx);
        verify(mcpClient).callTool(argThat(request -> Map.of("tenant", "t1").equals(request.meta())));
    }

    @Test
    @DisplayName("callTool succeeds with multi-text content concatenated")
    void multiTextContentConcatenated() {
        ToolContext ctx = new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "tok"));
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(List.of(
                new McpSchema.TextContent("part1"),
                new McpSchema.TextContent("part2")), false);
        when(mcpClient.callTool(any())).thenReturn(result);

        assertEquals("part1part2", wrapper.call("{}", ctx));
    }

    @Test
    @DisplayName("getDelegate returns inner callback (for ReturnDirect / IdentityForward detection)")
    void getDelegateReturnsInner() {
        assertSame(delegate, wrapper.getDelegate());
    }

    @Test
    @DisplayName("parseArguments handles null input")
    void parseArgumentsHandlesNull() {
        ToolContext ctx = new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "tok"));
        when(mcpClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("ok")), false));
        assertEquals("ok", wrapper.call(null, ctx));
    }

    @Test
    @DisplayName("parseArguments handles blank input")
    void parseArgumentsHandlesBlank() {
        ToolContext ctx = new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "tok"));
        when(mcpClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("ok")), false));
        assertEquals("ok", wrapper.call("   ", ctx));
    }
}
