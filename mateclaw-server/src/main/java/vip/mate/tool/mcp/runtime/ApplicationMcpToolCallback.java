package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Adapts discovered MCP tools without depending on Spring AI's MCP SDK version. */
final class ApplicationMcpToolCallback implements ToolCallback {
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {};

    private final McpSyncClient client;
    private final McpJsonMapper protocolMapper;
    private final ToolDefinition definition;

    ApplicationMcpToolCallback(McpSyncClient client, McpSchema.Tool tool) {
        this.client = Objects.requireNonNull(client, "MCP client must not be null");
        // Protocol numbers must remain numeric independently of MVC entity-ID serializers.
        this.protocolMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        Objects.requireNonNull(tool, "MCP tool must not be null");
        Map<String, Object> schema = protocolMapper.convertValue(tool.inputSchema(), MAP_TYPE);
        schema = schema != null ? new LinkedHashMap<>(schema) : new LinkedHashMap<>();
        schema.putIfAbsent("type", "object");
        if ("object".equals(schema.get("type"))) schema.putIfAbsent("properties", Map.of());
        try {
            this.definition = DefaultToolDefinition.builder()
                    .name(tool.name()).description(tool.description())
                    .inputSchema(protocolMapper.writeValueAsString(schema)).build();
        } catch (IOException ex) {
            throw new IllegalArgumentException("MCP tool schema cannot be serialized", ex);
        }
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> arguments;
        try {
            arguments = protocolMapper.readValue(toolInput == null || toolInput.isBlank() ? "{}" : toolInput, MAP_TYPE);
        } catch (IOException ex) {
            throw new IllegalArgumentException("MCP tool arguments must be a JSON object", ex);
        }
        if (arguments == null) {
            throw new IllegalArgumentException("MCP tool arguments must be a JSON object");
        }
        Map<String, Object> metadata = toolContext != null ? McpWireMetadata.copy(toolContext) : null;
        try {
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                    .name(definition.name()).arguments(arguments).meta(metadata).build());
            if (Boolean.TRUE.equals(result.isError())) {
                throw new IllegalStateException("Error calling tool: " + result.content());
            }
            return protocolMapper.writeValueAsString(result.content());
        } catch (Exception ex) {
            throw new ToolExecutionException(definition, ex);
        }
    }
}
