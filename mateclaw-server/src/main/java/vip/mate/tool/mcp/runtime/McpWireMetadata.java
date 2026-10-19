package vip.mate.tool.mcp.runtime;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.execution.evidence.service.ExecutionObservationSink;
import vip.mate.tool.builtin.ProgressiveToolBridgeTool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Business metadata may cross the MCP boundary; local execution capabilities may not. */
final class McpWireMetadata {
    private static final Set<String> LOCAL_KEYS = Set.of(
            "exchange",
            ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY,
            McpToolResultCapture.CONTEXT_KEY,
            ProgressiveToolBridgeTool.SCOPED_TOOL_CALLBACKS_CONTEXT_KEY);

    private McpWireMetadata() {}

    static Map<String, Object> copy(ToolContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (context == null || context.getContext() == null) return metadata;
        context.getContext().forEach((key, value) -> {
            if (value != null && !LOCAL_KEYS.contains(key)
                    && !(value instanceof ExecutionObservationSink)
                    && !(value instanceof McpToolResultCapture)
                    && !(value instanceof ToolCallback)) {
                metadata.put(key, value);
            }
        });
        return metadata;
    }
}
