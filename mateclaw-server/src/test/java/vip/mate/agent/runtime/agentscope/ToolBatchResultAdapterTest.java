package vip.mate.agent.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.executor.ToolExecutionExecutor.ToolExecutionResult;
import vip.mate.agent.graph.state.DirectToolOutput;

import java.util.List;
import java.util.Map;

class ToolBatchResultAdapterTest {
    private static AssistantMessage.ToolCall call(String id) {
        return new AssistantMessage.ToolCall(id, "function", "same", " {\"x\": 1} ");
    }

    private static ToolResponseMessage.ToolResponse response(String id, String text) {
        return new ToolResponseMessage.ToolResponse(id, "same", text);
    }

    private static GraphEventPublisher.GraphEvent complete(String id, boolean success) {
        return new GraphEventPublisher.GraphEvent(
                GraphEventPublisher.EVENT_TOOL_COMPLETE,
                Map.of("toolCallId", id, "toolName", "same", "success", success),
                1);
    }

    @Test
    void duplicateNamesPreserveIdsArgumentsAndAuthoritativeOutcomes() {
        var result =
                new ToolExecutionResult(
                        List.of(
                                response("a", "looks like failure"),
                                response("b", "looks successful")),
                        List.of(complete("a", true), complete("b", false)),
                        false,
                        null,
                        null);
        var projected = ToolBatchResultAdapter.project(List.of(call("a"), call("b")), result);
        assertSame(result, projected.metadata().get("productResult"));
        assertEquals("a", projected.results().get(0).getId());
        assertEquals("same", projected.results().get(0).getName());
        assertEquals(
                call("a").arguments(),
                projected.results().get(0).getMetadata().get("rawArguments"));
        assertEquals(
                "looks like failure",
                ((TextBlock) projected.results().get(0).getOutput().get(0)).getText());
        assertEquals(ToolResultState.SUCCESS, projected.results().get(0).getState());
        assertEquals(ToolResultState.ERROR, projected.results().get(1).getState());
        assertNull(projected.stopReason());
    }

    @Test
    void approvalAndSkippedResponsesStayConservativeWithoutCompletion() {
        var result =
                new ToolExecutionResult(
                        List.of(response("a", "pending"), response("b", "skipped")),
                        List.of(),
                        true,
                        "pending",
                        "same");
        var projected = ToolBatchResultAdapter.project(List.of(call("a"), call("b")), result);
        assertEquals("awaiting_approval", projected.stopReason());
        assertEquals(2, projected.results().size());
        projected.results().forEach(block -> assertEquals(ToolResultState.ERROR, block.getState()));
    }

    @Test
    void directOutputsAndApprovalFlagsDetermineStopWithoutTextParsing() {
        var direct = new DirectToolOutput("a", "same", "full", 1);
        var result =
                new ToolExecutionResult(
                        List.of(response("a", "awaiting approval")),
                        List.of(complete("a", true)),
                        false,
                        null,
                        null,
                        List.of(direct));
        assertEquals(
                "direct_output",
                ToolBatchResultAdapter.project(List.of(call("a")), result).stopReason());
        var both =
                new ToolExecutionResult(
                        result.responses(),
                        result.events(),
                        true,
                        "pending",
                        "same",
                        List.of(direct));
        assertEquals(
                "awaiting_approval",
                ToolBatchResultAdapter.project(List.of(call("a")), both).stopReason());
    }

    @Test
    void responsesAreReorderedAndMissingApprovalResultsAreConservative() {
        var reordered =
                new ToolExecutionResult(
                        List.of(response("b", "second"), response("a", "first")),
                        List.of(complete("a", true), complete("b", false)),
                        false,
                        null,
                        null);
        assertEquals(
                List.of("a", "b"),
                ToolBatchResultAdapter.project(List.of(call("a"), call("b")), reordered)
                        .results()
                        .stream()
                        .map(block -> block.getId())
                        .toList());
        var shortApproval =
                new ToolExecutionResult(
                        List.of(response("a", "pending")), List.of(), true, "pending", "same");
        var projected =
                ToolBatchResultAdapter.project(List.of(call("a"), call("b")), shortApproval);
        assertEquals(
                List.of("a", "b"),
                projected.results().stream().map(block -> block.getId()).toList());
        assertEquals(ToolResultState.ERROR, projected.results().get(1).getState());
        assertEquals(true, projected.results().get(1).getMetadata().get("skipped"));
        assertEquals(
                "unreported", projected.results().get(1).getMetadata().get("completionStatus"));
        assertSame(shortApproval, projected.metadata().get("productResult"));
        assertEquals(1, shortApproval.responses().size());
    }

    @Test
    void duplicateMissingAndInvalidCompletionIdentitiesFailClosed() {
        var duplicate =
                new ToolExecutionResult(
                        List.of(response("a", "one"), response("a", "two")),
                        List.of(),
                        false,
                        null,
                        null);
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolBatchResultAdapter.project(List.of(call("a")), duplicate));
        var missing =
                new ToolExecutionResult(
                        List.of(response("a", "one")), List.of(), false, null, null);
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolBatchResultAdapter.project(List.of(call("a"), call("b")), missing));
        for (Map<String, Object> data :
                List.of(
                        Map.<String, Object>of(
                                "toolCallId", "unknown", "toolName", "same", "success", true),
                        Map.<String, Object>of("toolName", "same", "success", true))) {
            var invalid =
                    new ToolExecutionResult(
                            List.of(response("a", "one")),
                            List.of(
                                    new GraphEventPublisher.GraphEvent(
                                            GraphEventPublisher.EVENT_TOOL_COMPLETE, data, 1)),
                            false,
                            null,
                            null);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ToolBatchResultAdapter.project(List.of(call("a")), invalid));
        }
    }

    @Test
    void duplicateIdsAndMismatchedResponsesAreRejected() {
        var result =
                new ToolExecutionResult(
                        List.of(response("a", "ok")),
                        List.of(complete("a", true)),
                        false,
                        null,
                        null);
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolBatchResultAdapter.project(List.of(call("a"), call("a")), result));
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolBatchResultAdapter.project(List.of(call("other")), result));
        var duplicate =
                new ToolExecutionResult(
                        List.of(response("a", "ok")),
                        List.of(complete("a", true), complete("a", false)),
                        false,
                        null,
                        null);
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolBatchResultAdapter.project(List.of(call("a")), duplicate));
    }
}
