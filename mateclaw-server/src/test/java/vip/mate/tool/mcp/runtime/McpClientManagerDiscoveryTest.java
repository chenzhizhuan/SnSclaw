package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class McpClientManagerDiscoveryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final McpClientManager manager = new McpClientManager(publisher,
            new McpIdentityForwardService(new McpIdentityForwardProperties()), null, mapper);
    private final McpSyncClient client = mock(McpSyncClient.class);

    @Test
    void discoversRawNamesAndRetainsSnapshotWhenServerReturnsNoTools() throws Exception {
        installClient(77L, client);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("find-items")), null))
                .thenReturn(new McpSchema.ListToolsResult(List.of(), null));
        List<ToolCallback> first = manager.getAllToolCallbacks();
        assertEquals(1, first.size());
        assertEquals(McpToolNameResolver.prefixedName(77L, "find-items"), first.getFirst().getToolDefinition().name());
        assertEquals("Find items", first.getFirst().getToolDefinition().description());
        assertSame(first.getFirst(), manager.getAllToolCallbacks().getFirst());
        verify(client, times(2)).listTools();
        verifyNoInteractions(publisher);
    }

    @Test
    void sameRawNameOnTwoServersHasDistinctPrefixes() throws Exception {
        McpSyncClient second = mock(McpSyncClient.class);
        installClient(77L, client);
        installClient(78L, second);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("search")), null));
        when(second.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("search")), null));
        List<ToolCallback> callbacks = manager.getAllToolCallbacks();
        assertEquals(2, callbacks.size());
        assertEquals(2, callbacks.stream().map(cb -> cb.getToolDefinition().name()).distinct().count());
    }

    @Test
    void discoveredCallbackCapturesStructuredResultAndProgressWithSingleRemoteCall() throws Exception {
        installClient(77L, client);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("find-items")), null));
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("found")))
                .structuredContent(Map.of("items", List.of("one"))).isError(false).build());
        McpToolResultCapture capture = new McpToolResultCapture();
        ToolContext context = capture.attach(new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "progress-1")));
        String output = manager.getAllToolCallbacks().getFirst().call("{\"q\":\"hello\"}", context);
        assertEquals("found", output);
        assertEquals(Map.of("items", List.of("one")), capture.structuredContent());
        ArgumentCaptor<McpSchema.CallToolRequest> request = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client, times(1)).callTool(request.capture());
        assertEquals("find-items", request.getValue().name());
        assertEquals(Map.of("q", "hello"), request.getValue().arguments());
        assertEquals(Map.of("progressToken", "progress-1"), request.getValue().meta());
    }

    private McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder().name(name).description("Find items")
                .inputSchema(new JacksonMcpJsonMapper(new ObjectMapper()), "{\"type\":\"object\"}").build();
    }

    @SuppressWarnings("unchecked")
    private void installClient(long serverId, McpSyncClient installed) throws Exception {
        Field clients = McpClientManager.class.getDeclaredField("clients");
        clients.setAccessible(true);
        ((Map<Long, McpSyncClient>) clients.get(manager)).put(serverId, installed);
    }
}
