package vip.mate.agent.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

import io.agentscope.core.model.ToolSchema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.delegation.SubagentRunContext;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.state.DirectToolOutput;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.runtime.state.RuntimeExecutionScope;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class ProductCanonicalBoundaryTest {
    @Test
    void nativeMalformedArgumentsProduceRealExecutorErrorAndAnotherModelRound() {
        runError("real_tool", "{");
    }

    @Test
    void nativeUnknownNameProducesRealExecutorHintAndAnotherModelRound() {
        runError("unknown", "{}");
    }

    @Test
    void delegatedRestrictionsReachRealExecutorAcrossNativeWorkers() {
        var child = new SubagentRunContext(2, "parent", "root", "child", Set.of("real_tool"));
        runError("real_tool", "{}", new RunState(Map.of(SUBAGENT_RUN_CONTEXT, child)));
    }

    private void runError(String requestedName, String raw) {
        runError(requestedName, raw, new RunState());
    }

    private void runError(String requestedName, String raw, RunState initial) {
        AtomicInteger effects = new AtomicInteger(),
                ownerCalls = new AtomicInteger(),
                rounds = new AtomicInteger();
        ToolCallback target =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("real_tool")
                                .description("real")
                                .inputSchema("{}")
                                .build();
                    }

                    public String call(String json) {
                        effects.incrementAndGet();
                        throw new AssertionError("Error path must never execute business callback");
                    }

                    public String call(String json, ToolContext context) {
                        return call(json);
                    }
                };
        ToolGuard allow = (name, arguments) -> ToolGuardResult.allow();
        ToolExecutionExecutor executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(target)), allow, null, null);
        var policies =
                NativePolicyDriver.Policies.singleTask(
                        state ->
                                rounds.incrementAndGet() == 1
                                        ? Map.of(
                                                TOOL_CALLS,
                                                List.of(
                                                        new AssistantMessage.ToolCall(
                                                                "error-original",
                                                                "function",
                                                                requestedName,
                                                                raw)))
                                        : Map.of(
                                                MESSAGES,
                                                List.of(new AssistantMessage("final-answer"))),
                        state ->
                                state.<List<AssistantMessage.ToolCall>>value(TOOL_CALLS, List.of())
                                                .isEmpty()
                                        ? FINAL_ANSWER_NODE
                                        : ACTION_NODE,
                        state -> {
                            ownerCalls.incrementAndGet();
                            var calls =
                                    state.<List<AssistantMessage.ToolCall>>value(
                                            TOOL_CALLS, List.of());
                            assertEquals(raw, calls.getFirst().arguments());
                            var result =
                                    executor.execute(
                                            calls,
                                            "conversation",
                                            "agent",
                                            false,
                                            "requester",
                                            null);
                            assertFalse(
                                    result.events().stream()
                                            .filter(
                                                    event ->
                                                            GraphEventPublisher.EVENT_TOOL_COMPLETE
                                                                    .equals(event.type()))
                                            .anyMatch(
                                                    event ->
                                                            Boolean.TRUE.equals(
                                                                    event.data().get("success"))));
                            return Map.of(
                                    NativePolicyDriver.BATCH_RESULT,
                                    ToolBatchResultAdapter.project(calls, result));
                        },
                        state -> Map.of(),
                        state -> REASONING_NODE,
                        state -> Map.of(),
                        state -> Map.of(),
                        state -> Map.of(FINAL_ANSWER, "final-answer"));
        var schema =
                ToolSchema.builder()
                        .name("real_tool")
                        .description("schema only")
                        .parameters(Map.of("type", "object", "properties", Map.of()))
                        .build();
        var driver =
                new NativePolicyDriver(
                        policies,
                        List.of(schema),
                        4,
                        RuntimeExecutionScope::open,
                        NativePolicyDriver.ToolNamePolicy.APPLICATION_OWNER);
        var steps = driver.run(initial).collectList().block(Duration.ofSeconds(5));
        assertEquals(
                List.of(
                        REASONING_NODE,
                        ACTION_NODE,
                        OBSERVATION_NODE,
                        REASONING_NODE,
                        FINAL_ANSWER_NODE),
                steps.stream().map(step -> step.node()).toList());
        assertEquals(1, ownerCalls.get());
        assertEquals(2, rounds.get());
        assertEquals(0, effects.get());
    }

    @ParameterizedTest
    @CsvSource({"true,false,false", "true,true,false", "true,false,true", "false,false,false"})
    void approvedAliasPreservesProviderPairAcrossOutcomes(
            boolean present, boolean failure, boolean direct) {
        var effects = new AtomicInteger();
        ToolCallback target =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("web_search")
                                .description("Search")
                                .inputSchema("{}")
                                .build();
                    }

                    public ToolMetadata getToolMetadata() {
                        return ToolMetadata.builder().returnDirect(direct).build();
                    }

                    public String call(String arguments) {
                        effects.incrementAndGet();
                        if (failure) throw new IllegalStateException("fixture failure");
                        return "approved result";
                    }

                    public String call(String arguments, ToolContext context) {
                        return call(arguments);
                    }
                };
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(
                                List.of(), present ? List.of(target) : List.of()),
                        (name, arguments) -> ToolGuardResult.allow(),
                        null,
                        null);
        var call =
                new AssistantMessage.ToolCall("approved-original", "function", "WebSearch", "{}");
        var events = new ArrayList<GraphEventPublisher.GraphEvent>();
        var outputs = new ArrayList<DirectToolOutput>();
        var response =
                executor.executePreApproved(
                        call,
                        "{}",
                        events,
                        "conversation",
                        null,
                        outputs,
                        ChatOrigin.web("conversation", "requester", 1L, null));
        assertEquals("approved-original", response.id());
        assertEquals("WebSearch", response.name());
        assertEquals(present ? 1 : 0, effects.get());
        var result =
                new ToolExecutionExecutor.ToolExecutionResult(
                        List.of(response), events, false, null, null, outputs);
        var projection = ToolBatchResultAdapter.project(List.of(call), result);
        assertEquals("approved-original", projection.results().getFirst().getId());
        assertEquals("WebSearch", projection.results().getFirst().getName());
        assertEquals(
                present ? "web_search" : "WebSearch",
                events.stream()
                        .filter(
                                event ->
                                        GraphEventPublisher.EVENT_TOOL_COMPLETE.equals(
                                                event.type()))
                        .findFirst()
                        .orElseThrow()
                        .data()
                        .get("toolName"));
        assertEquals(present && direct && !failure ? 1 : 0, outputs.size());
    }

    @Test
    void realExecutorBridgeCompletionKeepsCanonicalTargetAndOriginalProviderPair() {
        AtomicInteger effects = new AtomicInteger();
        ToolCallback target =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("web_search")
                                .description("search")
                                .inputSchema("{}")
                                .build();
                    }

                    public String call(String json) {
                        effects.incrementAndGet();
                        assertEquals("{\"query\":\"MateClaw\"}", json);
                        return "original-product-result";
                    }

                    public String call(String json, ToolContext context) {
                        return call(json);
                    }
                };
        ToolGuard allow =
                (name, arguments) -> {
                    assertEquals("web_search", name);
                    return ToolGuardResult.allow();
                };
        ToolExecutionExecutor executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(target)), allow, null, null);
        var calls =
                List.of(
                        new AssistantMessage.ToolCall(
                                "bridge-original",
                                "function",
                                "tool_call",
                                "{\"toolName\":\"web_search\",\"arguments\":{\"query\":\"MateClaw\"}}"));
        var result = executor.execute(calls, "conversation", "agent", false, "requester", null);
        assertEquals(1, effects.get());
        assertEquals("tool_call", result.responses().getFirst().name());
        assertEquals(
                "web_search",
                result.events().stream()
                        .filter(
                                event ->
                                        GraphEventPublisher.EVENT_TOOL_COMPLETE.equals(
                                                event.type()))
                        .findFirst()
                        .orElseThrow()
                        .data()
                        .get("toolName"));
        var projection = ToolBatchResultAdapter.project(calls, result);
        var block = projection.results().getFirst();
        assertEquals("bridge-original", block.getId());
        assertEquals("tool_call", block.getName());
        assertEquals("web_search", block.getMetadata().get("canonicalToolName"));
        assertSame(result, projection.metadata().get("productResult"));
    }
}
