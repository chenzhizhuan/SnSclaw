package vip.mate.agent.graph.plan.node;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.DirectToolOutput;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.planning.service.PlanningService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class StepNativeLoopTest {
    private static AssistantMessage.ToolCall call(int index) {
        return new AssistantMessage.ToolCall(
                "call-" + index, "function", "first", "{\"index\":" + index + "}");
    }

    private static NodeStreamingChatHelper.StreamResult response(int index, boolean tools) {
        var calls = tools ? List.of(call(index)) : List.<AssistantMessage.ToolCall>of();
        var assistant =
                AssistantMessage.builder()
                        .content(tools ? "" : "step-complete")
                        .toolCalls(calls)
                        .build();
        return new NodeStreamingChatHelper.StreamResult(
                tools ? "" : "step-complete",
                "thinking-" + index,
                assistant,
                calls,
                tools,
                2,
                3,
                false,
                null,
                NodeStreamingChatHelper.ErrorType.NONE,
                false,
                5,
                7,
                11);
    }

    private static ToolExecutionExecutor.ToolExecutionResult batch(
            List<AssistantMessage.ToolCall> calls) {
        return new ToolExecutionExecutor.ToolExecutionResult(
                calls.stream()
                        .map(
                                call ->
                                        new ToolResponseMessage.ToolResponse(
                                                call.id(),
                                                call.name(),
                                                "value-" + call.arguments()))
                        .toList(),
                List.of(),
                false,
                null,
                null);
    }

    private static final class Fixture {
        final ChatModel model = mock(ChatModel.class);
        final ToolExecutionExecutor executor = mock(ToolExecutionExecutor.class);
        final PlanningService planning = mock(PlanningService.class);
        final NodeStreamingChatHelper helper = mock(NodeStreamingChatHelper.class);
        final AtomicInteger rounds = new AtomicInteger();
        final AtomicBoolean nativeOwner = new AtomicBoolean();
        final StepExecutionNode node;
        final AgentToolSet tools;

        Fixture() {
            when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("test").build());
            ToolCallback callback =
                    new ToolCallback() {
                        public ToolDefinition getToolDefinition() {
                            return ToolDefinition.builder()
                                    .name("first")
                                    .description("test")
                                    .inputSchema(
                                            "{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"}}}")
                                    .build();
                        }

                        public String call(String input) {
                            throw new AssertionError("SDK must never execute schema callbacks");
                        }

                        public String call(String input, ToolContext context) {
                            return call(input);
                        }
                    };
            tools = AgentToolSet.fromCallbacks(List.of(), List.of(callback));
            node =
                    new StepExecutionNode(
                            model, tools, executor, planning, null, null, helper, null, 600_000);
            when(executor.execute(
                            anyList(),
                            anyString(),
                            anyString(),
                            anyBoolean(),
                            anyString(),
                            anyString(),
                            any(ChatOrigin.class),
                            anySet()))
                    .thenAnswer(invocation -> batch(invocation.getArgument(0)));
        }

        void modelRounds(int toolsBeforeText) {
            when(helper.streamCall(eq(model), any(), anyString(), anyString()))
                    .thenAnswer(
                            invocation -> {
                                nativeOwner.set(
                                        nativeOwner.get()
                                                || Arrays.stream(
                                                                Thread.currentThread()
                                                                        .getStackTrace())
                                                        .anyMatch(
                                                                frame ->
                                                                        frame.getClassName()
                                                                                .contains(
                                                                                        "NativePolicyDriver")));
                                int index = rounds.incrementAndGet();
                                return response(index, index <= toolsBeforeText);
                            });
        }

        RunState state() {
            return new RunState(
                    Map.of(
                            PlanStateKeys.PLAN_ID,
                            1L,
                            PlanStateKeys.PLAN_STEPS,
                            List.of("step one", "step two"),
                            PlanStateKeys.CURRENT_STEP_INDEX,
                            0,
                            MateClawStateKeys.SYSTEM_PROMPT,
                            "system",
                            MateClawStateKeys.CONVERSATION_ID,
                            "conversation",
                            MateClawStateKeys.AGENT_ID,
                            "agent"));
        }
    }

    @Test
    void replayStartAndReceiptDescribeActuallyExecutedStoredArguments() throws Exception {
        assertReplayProvenance("{\"index\":0}");
    }

    @Test
    void replayWithoutStoredArgumentsDescribesCurrentArgumentsFallback() throws Exception {
        assertReplayProvenance(null);
    }

    @Test
    void replayEmptyStoredStringRemainsEmptyLikeExecutorPayload() throws Exception {
        assertReplayProvenance("");
    }

    @SuppressWarnings("unchecked")
    private void assertReplayProvenance(String storedArguments) throws Exception {
        var fixture = new Fixture();
        fixture.modelRounds(1);
        var executedArguments = new ArrayList<String>();
        ToolCallback callback = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("first").description("replay provenance")
                        .inputSchema("{\"type\":\"object\"}").build();
            }
            public String call(String input) {
                executedArguments.add(input);
                return "approved";
            }
            public String call(String input, ToolContext context) { return call(input); }
        };
        var tools = AgentToolSet.fromCallbacks(List.of(), List.of(callback));
        var executor = new ToolExecutionExecutor(tools,
                (name, arguments) -> ToolGuardResult.allow(), null, null);
        var node = new StepExecutionNode(fixture.model, tools, executor, fixture.planning,
                null, null, fixture.helper, null, 600_000);
        var state = fixture.state();
        Map<String, String> approval = storedArguments == null ? Map.of("name", "first")
                : Map.of("name", "first", "arguments", storedArguments);
        state.replace(MateClawStateKeys.PRE_APPROVED_TOOL_CALL,
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(approval));

        var output = node.apply(state);

        String actual = storedArguments != null ? storedArguments : call(1).arguments();
        assertEquals(List.of(actual), executedArguments, "The real callback must receive the approved payload");
        var events = (List<GraphEventPublisher.GraphEvent>) output.get(MateClawStateKeys.PENDING_EVENTS);
        var start = events.stream().filter(event -> GraphEventPublisher.EVENT_TOOL_START.equals(event.type())
                && "call-1".equals(event.data().get("toolCallId"))).findFirst().orElseThrow();
        assertEquals(actual, start.data().get("arguments"),
                "Receipt provenance must describe actual execution, not the model's replacement request");
        var actualReceipt = ActionExecutionLedger.fromEvents(events).receipts().get("call-1");
        assertNotNull(actualReceipt);
        var expectedReceipt = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolStart("expected", "first", actual),
                GraphEventPublisher.toolComplete("expected", "first", "approved", true)))
                .receipts().get("expected");
        assertEquals(expectedReceipt.operationIdentity(), actualReceipt.operationIdentity());
        if (storedArguments != null && !storedArguments.isBlank()) {
            var failedReplacement = ActionExecutionLedger.fromEvents(List.of(
                    GraphEventPublisher.toolStart("failed", "first", call(1).arguments()),
                    GraphEventPublisher.toolComplete("failed", "first", "failed", false)))
                    .receipts().get("failed");
            assertNotEquals(failedReplacement.operationIdentity(), actualReceipt.operationIdentity(),
                    "Approved A must not look like recovery of failed model request B");
        }
    }

    @Test
    void modelAndToolsAreDrivenByActualNativeReAct() throws Exception {
        var fixture = new Fixture();
        fixture.modelRounds(2);
        var output = fixture.node.apply(fixture.state());
        assertTrue(fixture.nativeOwner.get(), "actual ReAct must own model iteration");
        assertEquals(3, fixture.rounds.get());
        assertEquals("step-complete", output.get(PlanStateKeys.CURRENT_STEP_RESULT));
        assertEquals(1, output.get(PlanStateKeys.CURRENT_STEP_INDEX));
        assertEquals(6, output.get(MateClawStateKeys.PROMPT_TOKENS));
        assertEquals(9, output.get(MateClawStateKeys.COMPLETION_TOKENS));
        assertEquals(15, output.get(MateClawStateKeys.CACHE_READ_TOKENS));
        assertEquals(21, output.get(MateClawStateKeys.CACHE_WRITE_TOKENS));
        assertEquals(33, output.get(MateClawStateKeys.REASONING_TOKENS));
        assertEquals("thinking-3", output.get(PlanStateKeys.CURRENT_STEP_THINKING));
        verify(fixture.executor, times(2))
                .execute(
                        anyList(),
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyString(),
                        anyString(),
                        any(),
                        anySet());
    }

    @Test
    void replayUsesStoredArgumentsOnlyOnceAcrossTwoToolRounds() throws Exception {
        var fixture = new Fixture();
        fixture.modelRounds(2);
        var approvals = new AtomicInteger();
        when(fixture.executor.executePreApproved(
                        any(), anyString(), anyList(), anyString(), anyString(), anyList(), any()))
                .thenAnswer(
                        invocation -> {
                            approvals.incrementAndGet();
                            assertEquals("{\"stored\":true}", invocation.getArgument(1));
                            var call = invocation.<AssistantMessage.ToolCall>getArgument(0);
                            return new ToolResponseMessage.ToolResponse(
                                    call.id(), call.name(), "approved");
                        });
        var state = fixture.state();
        state.replace(
                MateClawStateKeys.PRE_APPROVED_TOOL_CALL,
                "{\"name\":\"first\",\"arguments\":\"{\\\"stored\\\":true}\"}");
        var output = fixture.node.apply(state);
        assertEquals(
                "",
                output.get(MateClawStateKeys.PRE_APPROVED_TOOL_CALL),
                "consumed approval must be cleared before the outer plan advances");
        assertEquals(
                1,
                approvals.get(),
                "approved side effect must never replay on a later model round");
        verify(fixture.executor, times(1))
                .execute(
                        anyList(),
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyString(),
                        anyString(),
                        any(),
                        anySet());
    }

    @Test
    void approvalStopsThePlanAtTheCurrentStepWithoutAnotherModel() throws Exception {
        var fixture = new Fixture();
        fixture.modelRounds(1000);
        when(fixture.executor.execute(
                        anyList(),
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyString(),
                        anyString(),
                        any(),
                        anySet()))
                .thenAnswer(
                        invocation ->
                                new ToolExecutionExecutor.ToolExecutionResult(
                                        batch(invocation.getArgument(0)).responses(),
                                        List.of(),
                                        true,
                                        "pending",
                                        "first"));
        var output = fixture.node.apply(fixture.state());
        assertEquals("awaiting_approval", output.get(MateClawStateKeys.CURRENT_PHASE));
        assertEquals(0, output.get(PlanStateKeys.CURRENT_STEP_INDEX));
        assertEquals(1, fixture.rounds.get());
        verify(fixture.planning).updateSubPlanStatus(1L, 0, "awaiting_approval");
    }

    @Test
    void directOutputCompletesTheWholePlanWithoutAnotherModel() throws Exception {
        var fixture = new Fixture();
        fixture.modelRounds(1000);
        when(fixture.executor.execute(
                        anyList(),
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyString(),
                        anyString(),
                        any(),
                        anySet()))
                .thenAnswer(
                        invocation ->
                                new ToolExecutionExecutor.ToolExecutionResult(
                                        batch(invocation.getArgument(0)).responses(),
                                        List.of(),
                                        false,
                                        null,
                                        null,
                                        List.of(
                                                new DirectToolOutput(
                                                        "call-1", "first", "direct-answer", 0L))));
        var output = fixture.node.apply(fixture.state());
        assertEquals("plan_aborted", output.get(MateClawStateKeys.CURRENT_PHASE));
        assertEquals("direct-answer", output.get(PlanStateKeys.FINAL_SUMMARY));
        assertEquals(Boolean.TRUE, output.get(MateClawStateKeys.RETURN_DIRECT_TRIGGERED));
        assertEquals(2, output.get(PlanStateKeys.CURRENT_STEP_INDEX));
        assertEquals(1, fixture.rounds.get());
    }

    @Test
    void oneHundredBatchesStopWithoutSdkSummaryOrOneHundredFirstModel() throws Exception {
        var fixture = new Fixture();
        fixture.modelRounds(1000);
        var output = fixture.node.apply(fixture.state());
        assertEquals(100, fixture.rounds.get());
        assertEquals("plan_replan", output.get(MateClawStateKeys.CURRENT_PHASE));
        verify(fixture.executor, times(100))
                .execute(
                        anyList(),
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyString(),
                        anyString(),
                        any(),
                        anySet());
    }

    @Test
    void approvalBarrierProducesSkippedResultsForEveryUnexecutedCall() throws Exception {
        var fixture = new Fixture();
        var calls = List.of(call(1), call(2), call(3));
        var assistant = AssistantMessage.builder().content("").toolCalls(calls).build();
        when(fixture.helper.streamCall(eq(fixture.model), any(), anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            fixture.rounds.incrementAndGet();
                            return new NodeStreamingChatHelper.StreamResult(
                                    "", "", assistant, calls, true, 2, 3);
                        });
        when(fixture.executor.execute(
                        anyList(),
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyString(),
                        anyString(),
                        any(),
                        anySet()))
                .thenReturn(
                        new ToolExecutionExecutor.ToolExecutionResult(
                                List.of(
                                        new ToolResponseMessage.ToolResponse(
                                                "call-1", "first", "[APPROVAL_PENDING]")),
                                List.of(),
                                true,
                                "pending",
                                "first"));
        var output = fixture.node.apply(fixture.state());
        assertEquals(
                "awaiting_approval",
                output.get(MateClawStateKeys.CURRENT_PHASE),
                "SDK must persist a complete guarded result batch instead of rejecting partial"
                        + " approval results");
        assertEquals(1, fixture.rounds.get());
        verify(fixture.executor, times(1))
                .execute(
                        anyList(),
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyString(),
                        anyString(),
                        any(),
                        anySet());
    }

    @Test
    void expiredStepBudgetDoesNotCallProviderAndReplans() throws Exception {
        var fixture = new Fixture();
        var node =
                new StepExecutionNode(
                        fixture.model,
                        AgentToolSet.fromCallbacks(List.of(), List.of()),
                        fixture.executor,
                        fixture.planning,
                        null,
                        null,
                        fixture.helper,
                        null,
                        -1L);
        var output = node.apply(fixture.state());
        assertEquals("plan_replan", output.get(MateClawStateKeys.CURRENT_PHASE));
        verifyNoInteractions(fixture.helper, fixture.executor);
    }

    @Test
    void approvedFailureConsumesReplayWithoutSdkRetry() throws Exception {
        var fixture = new Fixture();
        fixture.modelRounds(1000);
        when(fixture.executor.executePreApproved(
                        any(), anyString(), anyList(), anyString(), anyString(), anyList(), any()))
                .thenThrow(new IllegalStateException("approved-failure"));
        var state = fixture.state();
        state.replace(
                MateClawStateKeys.PRE_APPROVED_TOOL_CALL,
                "{\"name\":\"first\",\"arguments\":\"{}\"}");
        var output = fixture.node.apply(state);
        assertEquals("plan_replan", output.get(MateClawStateKeys.CURRENT_PHASE));
        assertEquals("", output.get(MateClawStateKeys.PRE_APPROVED_TOOL_CALL));
        assertEquals(1, fixture.rounds.get());
        verify(fixture.executor, times(1))
                .executePreApproved(
                        any(), anyString(), anyList(), anyString(), anyString(), anyList(), any());
    }

    @Test
    void promptTooLongRetryPreservesCatalogContextSourceAndAcceptedUsage() throws Exception {
        var fixture = new Fixture();
        var manager = mock(ConversationWindowManager.class);
        when(manager.pruneOldToolResultsForModelInput(anyList(), anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            List<Message> original = invocation.getArgument(0);
                            var history =
                                    new ArrayList<Message>(
                                            original.subList(0, original.size() - 1));
                            for (int i = 0; i < 8; i++)
                                history.add(new UserMessage("old user " + i));
                            history.add(original.getLast());
                            return history;
                        });
        when(manager.compactForRetry(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<Message> compacted = invocation.getArgument(0);
                            return new ArrayList<>(
                                    compacted.subList(compacted.size() - 4, compacted.size()));
                        });
        var prompts = new ArrayList<Prompt>();
        var phases = new ArrayList<String>();
        when(fixture.helper.streamCall(eq(fixture.model), any(), anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            prompts.add(invocation.getArgument(1));
                            phases.add(invocation.getArgument(3));
                            return prompts.size() == 1
                                    ? new NodeStreamingChatHelper.StreamResult(
                                            "",
                                            "",
                                            null,
                                            List.of(),
                                            false,
                                            0,
                                            0,
                                            false,
                                            "too long",
                                            NodeStreamingChatHelper.ErrorType.PROMPT_TOO_LONG)
                                    : response(1, false);
                        });
        var node =
                new StepExecutionNode(
                        fixture.model,
                        fixture.tools,
                        fixture.executor,
                        fixture.planning,
                        null,
                        null,
                        fixture.helper,
                        manager,
                        loaded -> "## Skills\n- docx",
                        600_000L);
        var output = node.apply(fixture.state());
        assertEquals(List.of("step_execution[0]", "step_execution_compact_retry[0]"), phases);
        assertEquals(
                prompts.get(0).getInstructions().stream()
                        .filter(SystemMessage.class::isInstance)
                        .map(Message::getText)
                        .toList(),
                prompts.get(1).getInstructions().stream()
                        .filter(SystemMessage.class::isInstance)
                        .map(Message::getText)
                        .toList(),
                "Every system prompt survives tail-only PTL compaction exactly once");
        assertTrue(
                prompts.get(1).getInstructions().stream()
                        .anyMatch(message -> message.getText().contains("当前需要执行的步骤")));
        assertEquals(2, output.get(MateClawStateKeys.PROMPT_TOKENS));
        assertEquals(5, output.get(MateClawStateKeys.CACHE_READ_TOKENS));
        assertEquals(11, output.get(MateClawStateKeys.REASONING_TOKENS));
        verifyNoInteractions(fixture.executor);
    }
}
