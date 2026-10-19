package vip.mate.agent.runtime.agentscope;

import io.agentscope.core.message.ToolResultBlock;

import java.util.List;
import java.util.Map;

public record GuardedToolBatchResult(
        List<ToolResultBlock> results, Map<String, Object> metadata, String stopReason) {
    public GuardedToolBatchResult {
        results = List.copyOf(results);
        metadata = Map.copyOf(metadata);
    }
}
