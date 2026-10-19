package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApplicationMcpToolCallbackTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpSyncClient client = mock(McpSyncClient.class);
    private final McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("search-items").description("Find items")
            .inputSchema(new JacksonMcpJsonMapper(new ObjectMapper()),
                    "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}")
            .build();

    @Test
    void preservesRawNameDescriptionAndSchema() throws Exception {
        var callback = new ApplicationMcpToolCallback(client, tool);
        assertEquals("search-items", callback.getToolDefinition().name());
        assertEquals("Find items", callback.getToolDefinition().description());
        var schema = mapper.readTree(callback.getToolDefinition().inputSchema());
        assertEquals("string", schema.path("properties").path("q").path("type").asText());
        assertEquals("q", schema.path("required").get(0).asText());
        assertFalse(callback.getToolMetadata().returnDirect());
        verifyNoInteractions(client);
    }

    @Test
    void callsRawNameOnceWithArgumentsAndFilteredMetadata() throws Exception {
        var callback = new ApplicationMcpToolCallback(client, tool);
        var response = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("found")), false);
        when(client.callTool(any())).thenReturn(response);
        Map<String, Object> context = new HashMap<>();
        context.put("tenant", "t1");
        context.put("exchange", new Object());
        context.put("absent", null);
        String output = callback.call("{\"q\":\"hello\"}", new ToolContext(context));
        ArgumentCaptor<McpSchema.CallToolRequest> request = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client, times(1)).callTool(request.capture());
        assertEquals("search-items", request.getValue().name());
        assertEquals(Map.of("q", "hello"), request.getValue().arguments());
        assertEquals(Map.of("tenant", "t1"), request.getValue().meta());
        assertEquals("found", mapper.readTree(output).get(0).path("text").asText());
    }

    @Test
    void blankInputUsesEmptyObject() {
        var callback = new ApplicationMcpToolCallback(client, tool);
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(List.of(), false));
        assertEquals("[]", callback.call(" "));
        verify(client).callTool(argThat(request -> request.arguments().isEmpty()));
    }

    @Test
    void protocolErrorIsToolExecutionFailureWithoutRetry() {
        var callback = new ApplicationMcpToolCallback(client, tool);
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("denied")), true));
        assertThrows(ToolExecutionException.class, () -> callback.call("{}"));
        verify(client, times(1)).callTool(any());
    }

    @Test
    void transportFailureIsToolExecutionFailureWithoutRetry() {
        var callback = new ApplicationMcpToolCallback(client, tool);
        when(client.callTool(any())).thenThrow(new IllegalStateException("connection lost"));
        assertThrows(ToolExecutionException.class, () -> callback.call("{}"));
        verify(client, times(1)).callTool(any());
    }

    @Test
    void protocolSchemaNumbersRemainNumericWithMvcLongSerialization() throws Exception {
        ObjectMapper mvcMapper = new ObjectMapper().registerModule(new SimpleModule()
                .addSerializer(Long.class, ToStringSerializer.instance)
                .addSerializer(Long.TYPE, ToStringSerializer.instance));
        McpSchema.Tool numericTool = McpSchema.Tool.builder().name("range").description("Range")
                .inputSchema(new JacksonMcpJsonMapper(new ObjectMapper()),
                        "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":2147483648,\"enum\":[2147483648,2147483649]}}}")
                .build();
        var callback = configuredCallback(numericTool, mvcMapper);
        var number = mapper.readTree(callback.getToolDefinition().inputSchema()).path("properties").path("n");
        assertTrue(number.path("minimum").isNumber());
        assertEquals(2147483648L, number.path("minimum").longValue());
        assertTrue(number.path("enum").get(0).isNumber());
        assertEquals(2147483649L, number.path("enum").get(1).longValue());
    }

    @Test
    void protocolResultNumbersRemainNumericWithMvcLongSerialization() throws Exception {
        ObjectMapper mvcMapper = new ObjectMapper().registerModule(new SimpleModule()
                .addSerializer(Long.class, ToStringSerializer.instance)
                .addSerializer(Long.TYPE, ToStringSerializer.instance));
        var callback = configuredCallback(tool, mvcMapper);
        var content = new McpSchema.TextContent(null, "found", Map.of("sequence", 2147483648L));
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(List.of(content), false));
        var output = mapper.readTree(callback.call("{}"));
        assertTrue(output.get(0).path("_meta").path("sequence").isNumber());
        assertEquals(2147483648L, output.get(0).path("_meta").path("sequence").longValue());
        verify(client, times(1)).callTool(any());
    }

    @Test
    void jsonNullArgumentsNeverCallRemoteTool() {
        var callback = new ApplicationMcpToolCallback(client, tool);
        assertThrows(IllegalArgumentException.class, () -> callback.call("null"));
        verifyNoInteractions(client);
    }

    @SuppressWarnings("unchecked")
    private ToolCallback configuredCallback(McpSchema.Tool discovered, ObjectMapper mvcMapper) throws Exception {
        McpClientManager manager = new McpClientManager(mock(ApplicationEventPublisher.class),
                new McpIdentityForwardService(new McpIdentityForwardProperties()), null, mvcMapper);
        Field clients = McpClientManager.class.getDeclaredField("clients");
        clients.setAccessible(true);
        ((Map<Long, McpSyncClient>) clients.get(manager)).put(1L, client);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(discovered), null));
        return manager.getAllToolCallbacks().getFirst();
    }

    @Test
    void invalidInputNeverCallsRemoteTool() {
        var callback = new ApplicationMcpToolCallback(client, tool);
        assertThrows(IllegalArgumentException.class, () -> callback.call("invalid"));
        verifyNoInteractions(client);
    }
}
