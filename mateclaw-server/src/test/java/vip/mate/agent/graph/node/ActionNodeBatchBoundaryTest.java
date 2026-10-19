package vip.mate.agent.graph.node;

import static org.junit.jupiter.api.Assertions.*;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import vip.mate.agent.AgentToolSet;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class ActionNodeBatchBoundaryTest {
    @Test
    void completingAnExecutedBatchNeverInvokesTheToolAgain() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolCallback callback =
                new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("once")
                                .description("Single execution")
                                .inputSchema("{\"type\":\"object\"}")
                                .build();
                    }

                    @Override
                    public String call(String input) {
                        return call(input, new ToolContext(Map.of()));
                    }

                    @Override
                    public String call(String input, ToolContext context) {
                        executions.incrementAndGet();
                        assertEquals("{\"value\":1}", input);
                        return "executed";
                    }
                };
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, arguments) -> ToolGuardResult.allow(),
                        null,
                        null);
        var node = new ActionNode(executor);
        var call =
                new AssistantMessage.ToolCall("original-id", "function", "once", "{\"value\":1}");
        var state =
                new RunState(
                        Map.of(
                                TOOL_CALLS,
                                List.of(call),
                                CONVERSATION_ID,
                                "batch-conversation",
                                AGENT_ID,
                                "batch-agent"));

        var executed = node.executeBatch(state);
        assertEquals(1, executions.get());
        var output = node.completeBatch(state, executed);
        assertEquals(1, executions.get(), "Result projection must not repeat an external effect");
        assertEquals(executed.responses(), output.get(TOOL_RESULTS));
        assertEquals("original-id", executed.responses().getFirst().id());
        assertEquals("executed", executed.responses().getFirst().responseData());
        var messages = (List<?>) output.get(MESSAGES);
        assertEquals(
                executed.responses(), ((ToolResponseMessage) messages.getFirst()).getResponses());
        assertTrue(
                ((ActionExecutionLedger) output.get(ACTION_EXECUTION_LEDGER))
                        .hasSuccessfulTool("once"));
        var ledger = (ActionExecutionLedger) output.get(ACTION_EXECUTION_LEDGER);
        assertTrue(ledger.renderAnswerFacts().contains("sha256:"),
                "Real executor start arguments must join the completed receipt by call id");
        assertFalse(ledger.renderAnswerFacts().contains("\"value\":"));
        assertEquals(executed.events(), output.get(PENDING_EVENTS));
    }
}
