package vip.mate.agent.runtime.agentscope;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.executor.ToolExecutionExecutor.ToolExecutionResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure projection of an already executed canonical product batch. */
public final class ToolBatchResultAdapter {
    private ToolBatchResultAdapter() {}

    public static GuardedToolBatchResult project(
            List<AssistantMessage.ToolCall> calls, ToolExecutionResult result) {
        Map<String, AssistantMessage.ToolCall> originals = new LinkedHashMap<>();
        Objects.requireNonNull(calls);
        Objects.requireNonNull(result);
        for (AssistantMessage.ToolCall call : calls) {
            if (call.id() == null
                    || call.id().isBlank()
                    || call.arguments() == null
                    || originals.put(call.id(), call) != null)
                throw new IllegalArgumentException(
                        "Original tool calls require unique IDs and raw arguments");
        }
        Map<String, GraphEventPublisher.GraphEvent> completions = new LinkedHashMap<>();
        String approvalId = null;
        for (GraphEventPublisher.GraphEvent event : result.events()) {
            Object id = event.data().get("toolCallId");
            if (!(id instanceof String) || ((String) id).isBlank()) {
                if (GraphEventPublisher.EVENT_TOOL_COMPLETE.equals(event.type()))
                    throw new IllegalArgumentException("Completion event requires a call ID");
                continue;
            }
            String key = (String) id;
            if (GraphEventPublisher.EVENT_TOOL_COMPLETE.equals(event.type())) {
                if (!originals.containsKey(key) || completions.put(key, event) != null)
                    throw new IllegalArgumentException(
                            "Completion event must match one original call ID");
            }
            if (GraphEventPublisher.EVENT_TOOL_APPROVAL_REQUESTED.equals(event.type())) {
                if (!originals.containsKey(key) || approvalId != null)
                    throw new IllegalArgumentException(
                            "Approval event must identify one original call ID");
                approvalId = key;
            }
        }
        String exactApprovalId = approvalId;
        Map<String, ToolResponseMessage.ToolResponse> responses = new LinkedHashMap<>();
        for (ToolResponseMessage.ToolResponse response : result.responses()) {
            AssistantMessage.ToolCall original = originals.get(response.id());
            if (original == null
                    || !Objects.equals(original.name(), response.name())
                    || responses.put(response.id(), response) != null)
                throw new IllegalArgumentException(
                        "Product responses require unique original call ID/name pairs");
        }
        List<ToolResultBlock> blocks =
                calls.stream()
                        .map(
                                original -> {
                                    ToolResponseMessage.ToolResponse response =
                                            responses.get(original.id());
                                    if (response == null
                                            && (!result.awaitingApproval()
                                                    || completions.containsKey(original.id())))
                                        throw new IllegalArgumentException(
                                                "Missing product response without an approval"
                                                        + " barrier");
                                    Map<String, Object> metadata = new LinkedHashMap<>();
                                    if (response != null) metadata.put("productResponse", response);
                                    else metadata.put("skipped", true);
                                    metadata.put("rawArguments", original.arguments());
                                    GraphEventPublisher.GraphEvent completion =
                                            completions.get(original.id());
                                    ToolResultState state = ToolResultState.ERROR;
                                    if (completion != null) {
                                        metadata.put("completionEvent", completion.data());
                                        Object canonicalName = completion.data().get("toolName");
                                        if (canonicalName instanceof String name && !name.isBlank())
                                            metadata.put("canonicalToolName", name);
                                        metadata.put("completionStatus", "reported");
                                        if (Boolean.TRUE.equals(completion.data().get("success")))
                                            state = ToolResultState.SUCCESS;
                                    } else metadata.put("completionStatus", "unreported");
                                    if (original.id().equals(exactApprovalId))
                                        metadata.put("awaitingApproval", true);
                                    return new ToolResultBlock(
                                            original.id(),
                                            original.name(),
                                            List.of(
                                                    TextBlock.builder()
                                                            .text(
                                                                    response == null
                                                                                    || response
                                                                                                    .responseData()
                                                                                            == null
                                                                            ? ""
                                                                            : response
                                                                                    .responseData())
                                                            .build()),
                                            metadata,
                                            state);
                                })
                        .toList();
        String stop =
                result.awaitingApproval()
                        ? "awaiting_approval"
                        : result.hasDirectOutputs() ? "direct_output" : null;
        return new GuardedToolBatchResult(blocks, Map.of("productResult", result), stop);
    }
}
