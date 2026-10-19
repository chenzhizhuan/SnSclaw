package vip.mate.agent.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

import io.agentscope.core.model.ToolSchema;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import reactor.core.publisher.Flux;

import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.agent.delegation.SubagentRunContext;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.runtime.state.RuntimeExecutionScope;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import vip.mate.tool.builtin.DelegationContext;
import vip.mate.tool.builtin.ToolExecutionContext;
import vip.mate.tool.guard.ToolGuardResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

class NativeConcurrentIsolationTest {
    @Test
    void sharedNativeDriverKeepsConcurrentSessionsAndParallelCallbacksIsolated() {
        var effects = new ConcurrentHashMap<String, AtomicInteger>();
        ToolCallback callback =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("inspect_context")
                                .description("Inspect")
                                .inputSchema("{}")
                                .build();
                    }

                    public String call(String arguments) {
                        throw new AssertionError("Explicit context is required");
                    }

                    public String call(String arguments, ToolContext context) {
                        ChatOrigin origin = ChatOrigin.from(context);
                        assertEquals(origin, ToolExecutionContext.origin());
                        assertEquals(
                                origin.conversationId(), ToolExecutionContext.conversationId());
                        assertEquals(origin.requesterId(), ToolExecutionContext.username());
                        assertEquals(
                                origin.workspaceBasePath(),
                                ToolExecutionContext.workspaceBasePath());
                        assertEquals(
                                origin.conversationId(),
                                DelegationContext.current().parentConversationId());
                        effects.computeIfAbsent(
                                        origin.conversationId(), ignored -> new AtomicInteger())
                                .incrementAndGet();
                        return origin.conversationId();
                    }
                };
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, arguments) -> ToolGuardResult.allow(),
                        null,
                        null);
        var policies =
                NativePolicyDriver.Policies.singleTask(
                        state -> {
                            assertScope(state);
                            int round = state.value("isolation_round", 0);
                            return round == 0
                                    ? Map.of(
                                            "isolation_round",
                                            1,
                                            TOOL_CALLS,
                                            List.of(
                                                    new AssistantMessage.ToolCall(
                                                            "one",
                                                            "function",
                                                            "inspect_context",
                                                            "{}"),
                                                    new AssistantMessage.ToolCall(
                                                            "two",
                                                            "function",
                                                            "inspect_context",
                                                            "{}")))
                                    : Map.of(
                                            MESSAGES,
                                            List.of(
                                                    new AssistantMessage(
                                                            state.value(
                                                                            CHAT_ORIGIN,
                                                                            ChatOrigin.EMPTY)
                                                                    .conversationId())));
                        },
                        state ->
                                state.<List<AssistantMessage.ToolCall>>value(TOOL_CALLS, List.of())
                                                .isEmpty()
                                        ? FINAL_ANSWER_NODE
                                        : ACTION_NODE,
                        state -> {
                            assertScope(state);
                            ChatOrigin origin = state.value(CHAT_ORIGIN, ChatOrigin.EMPTY);
                            var calls =
                                    state.<List<AssistantMessage.ToolCall>>value(
                                            TOOL_CALLS, List.of());
                            var result =
                                    executor.execute(
                                            calls,
                                            origin.conversationId(),
                                            "2",
                                            false,
                                            origin.requesterId(),
                                            origin.workspaceBasePath(),
                                            origin,
                                            Set.of());
                            assertEquals(
                                    List.of(origin.conversationId(), origin.conversationId()),
                                    result.responses().stream()
                                            .map(response -> response.responseData())
                                            .toList());
                            return Map.of(
                                    NativePolicyDriver.BATCH_RESULT,
                                    ToolBatchResultAdapter.project(calls, result));
                        },
                        state -> Map.of(),
                        state -> REASONING_NODE,
                        state -> Map.of(),
                        state -> Map.of(),
                        state -> {
                            assertScope(state);
                            return Map.of(
                                    FINAL_ANSWER,
                                    state.value(CHAT_ORIGIN, ChatOrigin.EMPTY).conversationId());
                        });
        var schema =
                ToolSchema.builder()
                        .name("inspect_context")
                        .description("Schema")
                        .parameters(Map.of("type", "object"))
                        .build();
        var driver =
                new NativePolicyDriver(
                        policies,
                        List.of(schema),
                        4,
                        RuntimeExecutionScope::open,
                        NativePolicyDriver.ToolNamePolicy.APPLICATION_OWNER);
        var completed =
                Flux.range(0, 64)
                        .flatMap(
                                index -> {
                                    String conversation = "isolated-" + index;
                                    ChatOrigin origin =
                                            ChatOrigin.web(
                                                            conversation,
                                                            "user-" + index,
                                                            (long) index + 1,
                                                            "/workspace-" + index)
                                                    .withAgent(2L);
                                    var frame =
                                            new SubagentRunContext(
                                                    1,
                                                    conversation,
                                                    conversation,
                                                    "child-" + index,
                                                    Set.of());
                                    var initial =
                                            new RunState(
                                                    Map.of(
                                                            CHAT_ORIGIN,
                                                            origin,
                                                            REQUESTED_THINKING_LEVEL,
                                                            "thinking-" + index,
                                                            SUBAGENT_RUN_CONTEXT,
                                                            frame,
                                                            MESSAGES,
                                                            List.of(
                                                                    new UserMessage(
                                                                            conversation))));
                                    return driver.run(initial)
                                            .collectList()
                                            .map(
                                                    steps -> {
                                                        assertEquals(
                                                                conversation,
                                                                steps.getLast()
                                                                        .state()
                                                                        .value(FINAL_ANSWER, ""));
                                                        assertEquals(
                                                                1,
                                                                initial.<List<UserMessage>>value(
                                                                                MESSAGES, List.of())
                                                                        .size());
                                                        assertEquals(
                                                                0,
                                                                initial.value(
                                                                        "isolation_round", 0));
                                                        return conversation;
                                                    });
                                },
                                16)
                        .collectList()
                        .block(Duration.ofSeconds(20));
        assertEquals(64, completed.size());
        assertEquals(64, effects.size());
        effects.values().forEach(count -> assertEquals(2, count.get()));
    }

    private static void assertScope(RunState state) {
        assertEquals(state.value(CHAT_ORIGIN, ChatOrigin.EMPTY), ChatOriginHolder.get());
        assertEquals(state.value(REQUESTED_THINKING_LEVEL, ""), ThinkingLevelHolder.get());
        assertEquals(
                state.value(SUBAGENT_RUN_CONTEXT, SubagentRunContext.ROOT),
                DelegationContext.current());
    }
}
