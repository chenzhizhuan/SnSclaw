package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;

/** Per-invocation structured output, separate from the model-facing text budget. */
public final class McpToolResultCapture {
    public static final String CONTEXT_KEY = "_mcp_tool_result_capture";
    public static final int MAX_STRUCTURED_BYTES = 100 * 1024;

    private volatile Map<String, Object> structuredContent;
    private volatile boolean error;

    public ToolContext attach(ToolContext context) {
        Map<String, Object> values = new HashMap<>(context.getContext());
        values.put(CONTEXT_KEY, this);
        return new ToolContext(values);
    }

    public static McpToolResultCapture from(ToolContext context) {
        if (context == null) return null;
        Object value = context.getContext().get(CONTEXT_KEY);
        return value instanceof McpToolResultCapture capture ? capture : null;
    }

    public void capture(McpSchema.CallToolResult result, ObjectMapper mapper) {
        structuredContent = null;
        error = result != null && Boolean.TRUE.equals(result.isError());
        if (result == null || error || !(result.structuredContent() instanceof Map<?, ?>)) return;
        try {
            byte[] json = mapper.writeValueAsBytes(result.structuredContent());
            if (json.length <= MAX_STRUCTURED_BYTES) {
                // Detach the JSON tree from the client's mutable response object.
                structuredContent = mapper.readValue(json, new TypeReference<Map<String, Object>>() { });
            }
        } catch (Exception ignored) {
            // A display-only payload must never retry a completed remote tool.
        }
    }

    public Map<String, Object> structuredContent() { return structuredContent; }
    public boolean isError() { return error; }
}
