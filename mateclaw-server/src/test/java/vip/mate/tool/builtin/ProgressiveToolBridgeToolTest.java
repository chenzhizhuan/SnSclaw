package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.guard.service.ToolGuardConfigService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProgressiveToolBridgeToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void searchIncludesRequiredArgumentHintsWithoutFullSchema() throws Exception {
        ToolCallback scoped = callback("execute_code", "Execute code", "code");
        when(scoped.getToolDefinition().inputSchema()).thenReturn("""
                {"type":"object","required":["language","code"],"properties":{
                  "language":{"type":"string","enum":["python","bash","node"]},
                  "code":{"type":"string","description":"Full source code"},
                  "timeoutSeconds":{"type":"integer","description":"Optional timeout"}}}
                """);
        ProgressiveToolBridgeTool bridge = new ProgressiveToolBridgeTool(
                mock(ToolRegistry.class), mock(AgentBindingService.class), mock(ToolGuardConfigService.class));
        ToolContext context = new ToolContext(Map.of(
                ProgressiveToolBridgeTool.SCOPED_TOOL_CALLBACKS_CONTEXT_KEY, Map.of("execute_code", scoped)));

        JsonNode row = MAPPER.readTree(bridge.search("execute_code", 8, context)).path("tools").path(0);

        String hints = row.path("requiredArguments").asText();
        assertTrue(hints.contains("language:string"));
        assertTrue(hints.contains("python"));
        assertTrue(hints.contains("bash"));
        assertTrue(hints.contains("node"));
        assertTrue(hints.contains("code:string"));
        assertFalse(hints.contains("timeoutSeconds"));
        assertFalse(row.has("inputSchema"));
        assertEquals(MAPPER.readTree(scoped.getToolDefinition().inputSchema()),
                MAPPER.readTree(bridge.describe("execute_code", context)).path("inputSchema"));
    }

    @Test
    void requiredHintsPreserveDescribedLanguageChoicesAndStayBounded() throws Exception {
        ToolCallback scoped = callback("execute_code", "Execute code", "code");
        when(scoped.getToolDefinition().inputSchema()).thenReturn(MAPPER.writeValueAsString(Map.of(
                "required", List.of("language", "code"), "properties", Map.of(
                        "language", Map.of("type", "string", "description", "Language: python, bash, or node"),
                        "code", Map.of("type", "string", "description", "source ".repeat(1000))))));
        ProgressiveToolBridgeTool bridge = new ProgressiveToolBridgeTool(
                mock(ToolRegistry.class), mock(AgentBindingService.class), mock(ToolGuardConfigService.class));
        ToolContext context = new ToolContext(Map.of(
                ProgressiveToolBridgeTool.SCOPED_TOOL_CALLBACKS_CONTEXT_KEY, Map.of("execute_code", scoped)));

        String hints = MAPPER.readTree(bridge.search("", 8, context)).path("tools").path(0)
                .path("requiredArguments").asText();

        assertTrue(hints.contains("python, bash, or node"));
        assertTrue(hints.contains("code:string"));
        assertTrue(hints.length() <= 320);
    }

    @Test
    void malformedSchemaDoesNotBreakSearch() throws Exception {
        ToolCallback scoped = callback("lookup_customer", "Find account", "customer_id");
        when(scoped.getToolDefinition().inputSchema()).thenReturn("{broken");
        ProgressiveToolBridgeTool bridge = new ProgressiveToolBridgeTool(
                mock(ToolRegistry.class), mock(AgentBindingService.class), mock(ToolGuardConfigService.class));
        ToolContext context = new ToolContext(Map.of(
                ProgressiveToolBridgeTool.SCOPED_TOOL_CALLBACKS_CONTEXT_KEY, Map.of("lookup_customer", scoped)));

        JsonNode row = MAPPER.readTree(bridge.search("", 8, context)).path("tools").path(0);
        assertEquals("lookup_customer", row.path("name").asText());
        assertEquals("", row.path("requiredArguments").asText());
    }

    @Test
    void searchUsesExecutorSnapshotInsteadOfLiveRegistry() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolCallback liveOnly = callback("live_admin_tool", "Unrelated live tool", "session_id");
        AgentToolSet liveSet = AgentToolSet.fromCallbacks(List.of(), List.of(liveOnly));
        when(registry.getEnabledToolSet()).thenReturn(liveSet);
        ProgressiveToolBridgeTool bridge = new ProgressiveToolBridgeTool(
                registry, mock(AgentBindingService.class), mock(ToolGuardConfigService.class));

        ToolCallback scoped = callback("lookup_customer", "Find an account", "customer_id");
        ToolContext context = new ToolContext(Map.of(
                ProgressiveToolBridgeTool.SCOPED_TOOL_CALLBACKS_CONTEXT_KEY,
                Map.of("lookup_customer", scoped)));

        JsonNode result = MAPPER.readTree(bridge.search("customer_id", 8, context));

        assertEquals("lookup_customer", result.path("tools").path(0).path("name").asText());
        assertFalse(result.toString().contains("live_admin_tool"));
        verify(registry, never()).getEnabledToolSet();
    }

    @Test
    void punctuationOnlyQueryDoesNotMatchEveryTool() throws Exception {
        ProgressiveToolBridgeTool bridge = new ProgressiveToolBridgeTool(
                mock(ToolRegistry.class), mock(AgentBindingService.class),
                mock(ToolGuardConfigService.class));
        ToolCallback scoped = callback("lookup_customer", "Find an account", "customer_id");
        ToolContext context = new ToolContext(Map.of(
                ProgressiveToolBridgeTool.SCOPED_TOOL_CALLBACKS_CONTEXT_KEY,
                Map.of("lookup_customer", scoped)));

        JsonNode result = MAPPER.readTree(bridge.search("!!!", 8, context));

        assertTrue(result.path("tools").isEmpty());
    }

    private static ToolCallback callback(String name, String description, String property) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(definition.description()).thenReturn(description);
        when(definition.inputSchema()).thenReturn("{\"type\":\"object\",\"properties\":{\""
                + property + "\":{\"type\":\"string\"}}}");
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
