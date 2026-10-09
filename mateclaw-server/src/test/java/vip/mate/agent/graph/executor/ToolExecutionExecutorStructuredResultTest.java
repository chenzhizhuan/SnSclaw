package vip.mate.agent.graph.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.tool.mcp.runtime.ProgressAwareMcpToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ToolExecutionExecutorStructuredResultTest {
    private static final Map<String, Object> STRUCTURED = Map.of("mateclawUi", Map.of(
            "version", 1, "blocks", List.of(Map.of("type", "echarts", "data", Map.of(
                    "series", List.of(Map.of("type", "bar", "data", List.of(1, 2))))))));
    private final McpSyncClient client = mock(McpSyncClient.class);

    private ToolExecutionExecutor executor(boolean direct) {
        return executor(direct, "chart");
    }

    private ToolExecutionExecutor executor(boolean direct, String toolName) {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(toolName).description("Chart").inputSchema("{}").build());
        when(delegate.getToolMetadata()).thenReturn(ToolMetadata.builder().returnDirect(direct).build());
        ToolCallback callback = new ProgressAwareMcpToolCallback(delegate, client, toolName, new ObjectMapper());
        return new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                (name, args) -> ToolGuardResult.allow(), null, null);
    }

    private AssistantMessage.ToolCall call(String id) {
        return new AssistantMessage.ToolCall(id, "function", "chart", "{}");
    }

    @Test
    void sendsStructuredPayloadEvenWithoutProgressAndBeforeTextTruncation() {
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addTextContent("summary ".repeat(12000)).structuredContent(STRUCTURED).build());
        var result = executor(false).execute(List.of(call("chart-1")), "conv", "agent", false, "user", null);
        var event = result.events().stream().filter(e -> e.type().equals(GraphEventPublisher.EVENT_TOOL_COMPLETE))
                .findFirst().orElseThrow();
        assertEquals(STRUCTURED, event.data().get("structuredContent"));
        assertTrue(result.responses().getFirst().responseData().length() < 96000);
        assertFalse(result.responses().getFirst().responseData().contains("mateclawUi"));
        verify(client, times(1)).callTool(any());
    }

    @Test
    void structuredOnlyToolHasReadableModelFallback() {
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().structuredContent(STRUCTURED).build());
        var result = executor(false).execute(List.of(call("chart-1")), "conv", "agent", false, "user", null);
        assertTrue(result.responses().getFirst().responseData().contains("mateclawUi"));
    }

    @Test
    void directToolKeepsStructuredDataOutOfModelResponse() {
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addTextContent("summary").structuredContent(STRUCTURED).meta(Map.of("secret", "hidden")).build());
        var result = executor(true).execute(List.of(call("chart-1")), "conv", "agent", false, "user", null);
        var event = result.events().stream().filter(e -> e.type().equals(GraphEventPublisher.EVENT_TOOL_COMPLETE))
                .findFirst().orElseThrow();
        assertEquals(STRUCTURED, event.data().get("structuredContent"));
        assertFalse(event.data().toString().contains("hidden"));
        assertEquals(ToolExecutionExecutor.DIRECT_TOOL_PLACEHOLDER, result.responses().getFirst().responseData());
    }

    @Test
    void errorResultIsNotAdvertisedAsSuccessfulRichContent() {
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addTextContent("query failed").structuredContent(STRUCTURED).isError(true).build());
        var result = executor(false).execute(List.of(call("chart-1")), "conv", "agent", false, "user", null);
        var event = result.events().stream().filter(e -> e.type().equals(GraphEventPublisher.EVENT_TOOL_COMPLETE))
                .findFirst().orElseThrow();
        assertEquals(false, event.data().get("success"));
        assertFalse(event.data().containsKey("structuredContent"));
        assertEquals("query failed", result.responses().getFirst().responseData());
    }

    @Test
    void oversizedStructuredResultUsesTextFallback() {
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addTextContent("summary").structuredContent(Map.of("text", "汉".repeat(40000))).build());
        var result = executor(false).execute(List.of(call("chart-1")), "conv", "agent", false, "user", null);
        var event = result.events().stream().filter(e -> e.type().equals(GraphEventPublisher.EVENT_TOOL_COMPLETE))
                .findFirst().orElseThrow();
        assertFalse(event.data().containsKey("structuredContent"));
        assertEquals("summary", event.data().get("result"));
        verify(client, times(1)).callTool(any());
    }

    @Test
    void approvedReplayPreservesStructuredContent() {
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addTextContent("summary").structuredContent(STRUCTURED).build());
        var events = new ArrayList<GraphEventPublisher.GraphEvent>();
        executor(false).executePreApproved(call("chart-1"), "{}", events, "conv", null);
        assertEquals(STRUCTURED, events.getLast().data().get("structuredContent"));
    }
    @Test
    void consecutiveCallsDoNotReusePriorStructuredOutput() {
        when(client.callTool(any())).thenReturn(
                McpSchema.CallToolResult.builder().addTextContent("first").structuredContent(STRUCTURED).build(),
                McpSchema.CallToolResult.builder().addTextContent("second").build());
        var executor = executor(false);
        var first = executor.execute(List.of(call("first")), "conv", "agent", false, "user", null);
        var second = executor.execute(List.of(call("second")), "conv", "agent", false, "user", null);
        assertTrue(first.events().stream().anyMatch(e -> e.data().containsKey("structuredContent")));
        assertFalse(second.events().stream().anyMatch(e -> e.data().containsKey("structuredContent")));
        assertEquals("second", second.responses().getFirst().responseData());
    }

    @Test
    void failedRemoteCallIsNotRetriedViaDelegate() {
        when(client.callTool(any())).thenThrow(new IllegalStateException("lost connection"));
        var result = executor(false).execute(List.of(call("first")), "conv", "agent", false, "user", null);
        var event = result.events().stream().filter(e -> e.type().equals(GraphEventPublisher.EVENT_TOOL_COMPLETE))
                .findFirst().orElseThrow();
        assertEquals(false, event.data().get("success"));
        verify(client, times(1)).callTool(any());
    }

    @Test
    void simultaneousCallsKeepTheirOwnStructuredResult() throws Exception {
        var barrier = new CyclicBarrier(2);
        when(client.callTool(any())).thenAnswer(invocation -> {
            McpSchema.CallToolRequest request = invocation.getArgument(0);
            barrier.await(5, TimeUnit.SECONDS);
            return McpSchema.CallToolResult.builder().addTextContent("summary")
                    .structuredContent(Map.of("owner", request.arguments().get("owner"))).build();
        });
        var executor = executor(false);
        var first = CompletableFuture.supplyAsync(() -> executor.execute(List.of(
                new AssistantMessage.ToolCall("first", "function", "chart", "{\"owner\":\"first\"}")),
                "conv-a", "agent", false, "user", null));
        var second = CompletableFuture.supplyAsync(() -> executor.execute(List.of(
                new AssistantMessage.ToolCall("second", "function", "chart", "{\"owner\":\"second\"}")),
                "conv-b", "agent", false, "user", null));
        for (var pair : Map.of("first", first, "second", second).entrySet()) {
            var event = pair.getValue().get(10, TimeUnit.SECONDS).events().stream()
                    .filter(e -> e.type().equals(GraphEventPublisher.EVENT_TOOL_COMPLETE)).findFirst().orElseThrow();
            assertEquals(pair.getKey(), event.data().get("toolCallId"));
            assertEquals(Map.of("owner", pair.getKey()), event.data().get("structuredContent"));
        }
    }

    @Test
    void approvedDirectReplayKeepsStructuredDataOutOfModel() {
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addTextContent("summary").structuredContent(STRUCTURED).build());
        var events = new ArrayList<GraphEventPublisher.GraphEvent>();
        var response = executor(true).executePreApproved(call("chart-1"), "{}", events, "conv", null);
        assertEquals(STRUCTURED, events.getLast().data().get("structuredContent"));
        assertEquals(ToolExecutionExecutor.DIRECT_TOOL_PLACEHOLDER, response.responseData());
    }

    @Test
    void structuredProductCardsDoNotAskModelToRenderDuplicateCards() {
        String name = "mcp_1_ckjia_shopping_recom";
        String text = "recommendations: one product";
        var cards = Map.of("mateclawUi", Map.of("version", 1, "blocks", List.of(
                Map.of("type", "product-cards", "data", List.of(Map.of("name", "Product", "price", 12))))));
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addTextContent(text).structuredContent(cards).build());
        var result = executor(false, name).execute(List.of(
                new AssistantMessage.ToolCall("shopping", "function", name, "{}")),
                "conv", "agent", false, "user", null);
        assertEquals(text, result.responses().getFirst().responseData());
    }

}
