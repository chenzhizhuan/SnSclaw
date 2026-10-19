package vip.mate.goal.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.model.ToolSchema;
import io.modelcontextprotocol.spec.McpSchema;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ExecutionAttribution;
import vip.mate.agent.delegation.SubagentRunContext;
import vip.mate.agent.graph.executor.ToolExecutionCheckpointRejectedException;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.runtime.agentscope.NativePolicyDriver;
import vip.mate.agent.runtime.agentscope.ToolBatchResultAdapter;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.channel.web.ConversationInputQueueStore;
import vip.mate.config.ToolTimeoutProperties;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalStatus;
import vip.mate.tool.builtin.DelegationContext;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.tool.mcp.runtime.McpToolResultCapture;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class ToolPublicationRecoveryTest {
    JdbcTemplate jdbc;
    GoalAttemptStore attempts;
    GoalContinuationStore continuations;
    ConversationInputQueueStore inputs;
    GoalService goals = mock(GoalService.class);
    GoalRunCoordinator coordinator;
    GoalRecoveryService recovery;
    GoalEntity goal;
    LocalDateTime now = LocalDateTime.of(2026, 8, 27, 2, 0);

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/h2/V120__agent_goal.sql"),
                        new ClassPathResource("db/migration/h2/V188__goal_continuation.sql"),
                        new ClassPathResource(
                                "db/migration/h2/V189__goal_attempt_and_input_queue.sql"),
                        new ClassPathResource(
                                "db/migration/h2/V198__goal_absolute_owner_leases.sql"),
                        new ClassPathResource(
                                "db/migration/h2/V200__goal_approval_attempt_handoff.sql"),
                        new ClassPathResource(
                                "db/migration/h2/V199__queued_input_account_identity.sql"),
                        new ClassPathResource(
                                "db/migration/h2/V201__queued_input_selected_goal.sql"))
                .execute(ds);
        jdbc = new JdbcTemplate(ds);
        attempts = new GoalAttemptStore(jdbc);
        continuations = new GoalContinuationStore(jdbc);
        inputs = new ConversationInputQueueStore(jdbc, new ObjectMapper());
        coordinator =
                new GoalRunCoordinator(
                        continuations,
                        attempts,
                        goals,
                        new GoalProperties(),
                        Clock.fixed(
                                now.atZone(ZoneId.systemDefault()).toInstant(),
                                ZoneId.systemDefault()));
        recovery =
                new GoalRecoveryService(
                        attempts,
                        continuations,
                        inputs,
                        goals,
                        new DataSourceTransactionManager(ds));
        jdbc.update(
                """
INSERT INTO mate_agent_goal(id,conversation_id,agent_id,workspace_id,created_by,title,
description,status,persistent_execution,auto_followup_enabled,create_time,update_time)
VALUES(1,'conv',2,3,'alice','goal','objective','active',TRUE,TRUE,?,?)
""",
                now,
                now);
        goal = new GoalEntity();
        goal.setId(1L);
        goal.setConversationId("conv");
        goal.setAgentId(2L);
        goal.setWorkspaceId(3L);
        goal.setCreatedBy("alice");
        goal.setStatus(GoalStatus.ACTIVE);
        goal.setPersistentExecution(true);
        goal.setAutoFollowupEnabled(true);
        when(goals.getById(1L)).thenReturn(goal);
        continuations.discover(now);
    }

    @Test
    void realNativeCallbackMustObserveDurableUncertaintyBeforeEffect() {
        var claimed = coordinator.claim(continuations.get(1L), goal, now);
        assertTrue(coordinator.markRunning(claimed, now));
        assertTrue(coordinator.checkpoint(claimed, "safe", "provider_started", null, now));
        var observed = new AtomicReference<String>();
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_external")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            observed.set(attempts.get(claimed.attempt().id()).replaySafety());
                            return "side effect committed";
                        });
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        var origin =
                ChatOrigin.web("conv", "alice", 3L, null)
                        .withAgent(2L)
                        .withExecutionAttribution(
                                new ExecutionAttribution(
                                        1L,
                                        claimed.attempt().id(),
                                        null,
                                        null,
                                        claimed.attempt().leaseToken()));
        executor.setExecutionCheckpoint(
                new GoalToolExecutionCheckpoint(
                        attempts,
                        continuations,
                        goals,
                        coordinator,
                        new DataSourceTransactionManager(jdbc.getDataSource()),
                        jdbc,
                        Clock.fixed(
                                now.atZone(ZoneId.systemDefault()).toInstant(),
                                ZoneId.systemDefault())));
        var rounds = new AtomicInteger();
        var policies =
                NativePolicyDriver.Policies.singleTask(
                        state ->
                                rounds.incrementAndGet() == 1
                                        ? Map.of(
                                                "tool_calls",
                                                List.of(
                                                        new AssistantMessage.ToolCall(
                                                                "stable-call",
                                                                "function",
                                                                "write_external",
                                                                "{}")))
                                        : Map.of("messages", List.of(new AssistantMessage("done"))),
                        state ->
                                state.<List<AssistantMessage.ToolCall>>value(
                                                        "tool_calls", List.of())
                                                .isEmpty()
                                        ? "final_answer_node"
                                        : "action",
                        state -> {
                            var calls =
                                    state.<List<AssistantMessage.ToolCall>>value(
                                            "tool_calls", List.of());
                            var result =
                                    executor.execute(
                                            calls, "conv", "2", false, "alice", null, origin,
                                            Set.of());
                            return Map.of(
                                    NativePolicyDriver.BATCH_RESULT,
                                    ToolBatchResultAdapter.project(calls, result));
                        },
                        state -> Map.of(),
                        state -> "reasoning",
                        state -> Map.of(),
                        state -> Map.of(),
                        state -> Map.of("final_answer", "done"));
        var schema =
                ToolSchema.builder()
                        .name("write_external")
                        .description("schema only")
                        .parameters(Map.of("type", "object"))
                        .build();
        var driver =
                new NativePolicyDriver(
                        policies,
                        List.of(schema),
                        4,
                        state -> () -> {},
                        NativePolicyDriver.ToolNamePolicy.APPLICATION_OWNER);
        driver.run(new RunState()).collectList().block(Duration.ofSeconds(5));
        assertEquals(
                "uncertain",
                observed.get(),
                "Callback entry must precede side effect with a durable uncertain marker");
    }

    @Test
    void rejectedCheckpointAbortsNormalApprovedAndParallelExecutionWithoutCallback() {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_external")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        var rejected = new ToolExecutionCheckpointRejectedException("stale");
        executor.setExecutionCheckpoint(
                (origin, key, id) -> {
                    throw rejected;
                });
        var one = new AssistantMessage.ToolCall("one", "function", "write_external", "{}");
        var two = new AssistantMessage.ToolCall("two", "function", "write_external", "{}");
        assertSame(
                rejected,
                assertThrows(
                        ToolExecutionCheckpointRejectedException.class,
                        () -> executor.execute(List.of(one), "conv", "2", false, "alice", null)));
        assertSame(
                rejected,
                assertThrows(
                        ToolExecutionCheckpointRejectedException.class,
                        () ->
                                executor.execute(
                                        List.of(one, two), "conv", "2", false, "alice", null)));
        assertSame(
                rejected,
                assertThrows(
                        ToolExecutionCheckpointRejectedException.class,
                        () ->
                                executor.executePreApproved(
                                        one, "{}", new ArrayList<>(), "conv", null)));
        verify(callback, never()).call(anyString(), any(ToolContext.class));
    }

    @Test
    void perCallbackRejectionEscapesParallelFutureAggregation() {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_external")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        var checks = new AtomicInteger();
        executor.setExecutionCheckpoint(
                (origin, key, id) -> {
                    if (checks.incrementAndGet() > 1)
                        throw new ToolExecutionCheckpointRejectedException(
                                "lease expired after dispatch");
                });
        var calls =
                List.of(
                        new AssistantMessage.ToolCall("one", "function", "write_external", "{}"),
                        new AssistantMessage.ToolCall("two", "function", "write_external", "{}"));
        assertThrows(
                ToolExecutionCheckpointRejectedException.class,
                () -> executor.execute(calls, "conv", "2", false, "alice", null));
        verify(callback, never()).call(anyString(), any(ToolContext.class));
    }

    @Test
    void realGoalFenceRejectsWrongScopeAndExpiredOwnerBeforeAnyCallback() {
        var claimed = coordinator.claim(continuations.get(1L), goal, now);
        assertTrue(coordinator.markRunning(claimed, now));
        var origin =
                ChatOrigin.web("conv", "alice", 3L, null)
                        .withAgent(2L)
                        .withExecutionAttribution(
                                new ExecutionAttribution(
                                        1L,
                                        claimed.attempt().id(),
                                        null,
                                        null,
                                        claimed.attempt().leaseToken()));
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var clock =
                Clock.fixed(now.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        var checkpoint =
                new GoalToolExecutionCheckpoint(
                        attempts, continuations, goals, coordinator, manager, jdbc, clock);
        assertThrows(
                ToolExecutionCheckpointRejectedException.class,
                () -> checkpoint.beforeInvocation(origin.withWorkspace(99L, null), "key", "call"));
        assertThrows(
                ToolExecutionCheckpointRejectedException.class,
                () ->
                        checkpoint.beforeInvocation(
                                origin.withConversationId("other")
                                        .withExecutionAttribution(origin.executionAttribution()),
                                "key",
                                "call"));
        assertThrows(
                ToolExecutionCheckpointRejectedException.class,
                () -> checkpoint.beforeInvocation(origin.withAgent(99L), "key", "call"));
        var expired =
                new GoalToolExecutionCheckpoint(
                        attempts,
                        continuations,
                        goals,
                        coordinator,
                        manager,
                        jdbc,
                        Clock.offset(clock, java.time.Duration.ofSeconds(61)));
        assertThrows(
                ToolExecutionCheckpointRejectedException.class,
                () -> expired.beforeInvocation(origin, "key", "call"));
        assertEquals("safe", attempts.get(claimed.attempt().id()).replaySafety());
    }

    @Test
    void repeatedUnknownOutcomeRejectsExpiredAndReplacedOwnerFences() {
        var claimed = coordinator.claim(continuations.get(1L), goal, now);
        assertTrue(coordinator.markRunning(claimed, now));
        var origin =
                ChatOrigin.web("conv", "alice", 3L, null)
                        .withAgent(2L)
                        .withExecutionAttribution(
                                new ExecutionAttribution(
                                        1L,
                                        claimed.attempt().id(),
                                        null,
                                        null,
                                        claimed.attempt().leaseToken()));
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var clock =
                Clock.fixed(now.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        var checkpoint =
                new GoalToolExecutionCheckpoint(
                        attempts, continuations, goals, coordinator, manager, jdbc, clock);
        checkpoint.markUnknownOutcome(origin, new IllegalStateException("first unknown outcome"));
        assertEquals(
                "tool_outcome_uncertain", attempts.get(claimed.attempt().id()).checkpointType());
        assertDoesNotThrow(
                () ->
                        checkpoint.markUnknownOutcome(
                                origin, new IllegalStateException("same live owner")));
        var expired =
                new GoalToolExecutionCheckpoint(
                        attempts,
                        continuations,
                        goals,
                        coordinator,
                        manager,
                        jdbc,
                        Clock.offset(clock, Duration.ofSeconds(61)));
        assertThrows(
                ToolExecutionCheckpointRejectedException.class,
                () ->
                        expired.markUnknownOutcome(
                                origin, new IllegalStateException("late expired report")));
        jdbc.update(
                "UPDATE mate_goal_continuation SET lease_owner='new-owner',revision=revision+1"
                        + " WHERE goal_id=1");
        assertThrows(
                ToolExecutionCheckpointRejectedException.class,
                () ->
                        checkpoint.markUnknownOutcome(
                                origin, new IllegalStateException("old owner report")));
        assertEquals(
                "tool_outcome_uncertain", attempts.get(claimed.attempt().id()).checkpointType());
    }

    @Test
    void alreadyStoppedBatchNeverWritesToolEntryCheckpoint() {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_external")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        var tracker = mock(ChatStreamTracker.class);
        when(tracker.isStopRequested("conv")).thenReturn(true);
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        tracker);
        var checkpoints = new AtomicInteger();
        executor.setExecutionCheckpoint((origin, key, id) -> checkpoints.incrementAndGet());
        assertThrows(
                CancellationException.class,
                () ->
                        executor.execute(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "one", "function", "write_external", "{}")),
                                "conv",
                                "2",
                                false,
                                "alice",
                                null));
        assertEquals(0, checkpoints.get());
        verify(callback, never()).call(anyString(), any(ToolContext.class));
    }

    @Test
    void cronAttributionIsNotTreatedAsMalformedGoal() {
        var checkpoint =
                new GoalToolExecutionCheckpoint(
                        attempts,
                        continuations,
                        goals,
                        coordinator,
                        new DataSourceTransactionManager(jdbc.getDataSource()),
                        jdbc);
        ChatOrigin cron =
                ChatOrigin.EMPTY.withExecutionAttribution(
                        new ExecutionAttribution(null, null, 99L, null, "cron-fence"));
        assertDoesNotThrow(() -> checkpoint.beforeInvocation(cron, "key", "call"));
        assertDoesNotThrow(
                () ->
                        checkpoint.markUnknownOutcome(
                                cron, new IllegalStateException("cron-specific ownership")));
        assertThrows(
                ToolExecutionCheckpointRejectedException.class,
                () ->
                        checkpoint.beforeInvocation(
                                ChatOrigin.EMPTY.withExecutionAttribution(
                                        new ExecutionAttribution(1L, null, null, null, "fence")),
                                "key",
                                "call"));
    }

    @Test
    void unknownCallbackExceptionMarksStickyOutcomeAndBlocksLaterEntry() {
        var claimed = coordinator.claim(continuations.get(1L), goal, now);
        assertTrue(coordinator.markRunning(claimed, now));
        var origin =
                ChatOrigin.web("conv", "alice", 3L, null)
                        .withAgent(2L)
                        .withExecutionAttribution(
                                new ExecutionAttribution(
                                        1L,
                                        claimed.attempt().id(),
                                        null,
                                        null,
                                        claimed.attempt().leaseToken()));
        var checkpoint =
                new GoalToolExecutionCheckpoint(
                        attempts,
                        continuations,
                        goals,
                        coordinator,
                        new DataSourceTransactionManager(jdbc.getDataSource()),
                        jdbc,
                        Clock.fixed(
                                now.atZone(ZoneId.systemDefault()).toInstant(),
                                ZoneId.systemDefault()));
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_external")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenThrow(new IllegalStateException("connection lost after effect"));
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        executor.setExecutionCheckpoint(checkpoint);
        var calls =
                List.of(new AssistantMessage.ToolCall("one", "function", "write_external", "{}"));
        var result = executor.execute(calls, "conv", "2", false, "alice", null, origin, Set.of());
        assertEquals(
                "tool_outcome_uncertain", attempts.get(claimed.attempt().id()).checkpointType());
        assertEquals("uncertain", attempts.get(claimed.attempt().id()).replaySafety());
        assertFalse(
                result.events().stream()
                        .anyMatch(event -> Boolean.TRUE.equals(event.data().get("success"))));
        assertThrows(
                ToolExecutionCheckpointRejectedException.class,
                () -> executor.execute(calls, "conv", "2", false, "alice", null, origin, Set.of()));
        verify(callback, times(1)).call(anyString(), any(ToolContext.class));
    }

    @Test
    void enteredCancellationIsStickyButReportedMcpErrorRemainsKnown() {
        var claimed = coordinator.claim(continuations.get(1L), goal, now);
        assertTrue(coordinator.markRunning(claimed, now));
        var origin =
                ChatOrigin.web("conv", "alice", 3L, null)
                        .withAgent(2L)
                        .withExecutionAttribution(
                                new ExecutionAttribution(
                                        1L,
                                        claimed.attempt().id(),
                                        null,
                                        null,
                                        claimed.attempt().leaseToken()));
        var checkpoint =
                new GoalToolExecutionCheckpoint(
                        attempts,
                        continuations,
                        goals,
                        coordinator,
                        new DataSourceTransactionManager(jdbc.getDataSource()),
                        jdbc,
                        Clock.fixed(
                                now.atZone(ZoneId.systemDefault()).toInstant(),
                                ZoneId.systemDefault()));
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_external")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            var capture = McpToolResultCapture.from(invocation.getArgument(1));
                            capture.capture(
                                    McpSchema.CallToolResult.builder()
                                            .isError(true)
                                            .addTextContent("reported failure")
                                            .build(),
                                    new ObjectMapper());
                            throw new IllegalStateException("known protocol error");
                        });
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        executor.setExecutionCheckpoint(checkpoint);
        var calls =
                List.of(new AssistantMessage.ToolCall("one", "function", "write_external", "{}"));
        assertEquals(
                "reported failure",
                executor.execute(calls, "conv", "2", false, "alice", null, origin, Set.of())
                        .responses()
                        .getFirst()
                        .responseData());
        assertEquals("tool_started", attempts.get(claimed.attempt().id()).checkpointType());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenThrow(new CancellationException("cancelled after entry"));
        assertThrows(
                CancellationException.class,
                () -> executor.execute(calls, "conv", "2", false, "alice", null, origin, Set.of()));
        assertEquals(
                "tool_outcome_uncertain", attempts.get(claimed.attempt().id()).checkpointType());
    }

    @Test
    void callbackCompletingAfterDeadlineStillLeavesStickyUncertainty() {
        var claimed = coordinator.claim(continuations.get(1L), goal, now);
        assertTrue(coordinator.markRunning(claimed, now));
        var origin =
                ChatOrigin.web("conv", "alice", 3L, null)
                        .withAgent(2L)
                        .withExecutionAttribution(
                                new ExecutionAttribution(
                                        1L,
                                        claimed.attempt().id(),
                                        null,
                                        null,
                                        claimed.attempt().leaseToken()));
        var checkpoint =
                new GoalToolExecutionCheckpoint(
                        attempts,
                        continuations,
                        goals,
                        coordinator,
                        new DataSourceTransactionManager(jdbc.getDataSource()),
                        jdbc,
                        Clock.fixed(
                                now.atZone(ZoneId.systemDefault()).toInstant(),
                                ZoneId.systemDefault()));
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_external")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        var effects = new AtomicInteger();
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            effects.incrementAndGet();
                            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1300);
                            while (System.nanoTime() < until) {
                                try {
                                    Thread.sleep(20);
                                } catch (InterruptedException ignored) {
                                }
                            }
                            return "late success";
                        });
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        var timeouts = new ToolTimeoutProperties();
        timeouts.setDefaultTimeoutSeconds(1);
        ReflectionTestUtils.setField(executor, "toolTimeoutProperties", timeouts);
        executor.setExecutionCheckpoint(checkpoint);
        var calls =
                List.of(new AssistantMessage.ToolCall("one", "function", "write_external", "{}"));
        var result = executor.execute(calls, "conv", "2", false, "alice", null, origin, Set.of());
        assertEquals(1, effects.get());
        assertEquals(
                "tool_outcome_uncertain", attempts.get(claimed.attempt().id()).checkpointType());
        assertFalse(
                result.events().stream()
                        .anyMatch(event -> Boolean.TRUE.equals(event.data().get("success"))));
    }

    @Test
    void parallelCallbacksCarryTrustedDelegationAndRestoreCaller() {
        var frame = new SubagentRunContext(2, "parent", "root", "child", Set.of("denied"));
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_external")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            assertEquals(frame, DelegationContext.current());
                            return "ok";
                        });
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        DelegationContext.push(frame);
        try {
            var result =
                    executor.execute(
                            List.of(
                                    new AssistantMessage.ToolCall(
                                            "one", "function", "write_external", "{}"),
                                    new AssistantMessage.ToolCall(
                                            "two", "function", "write_external", "{}")),
                            "conv",
                            "2",
                            false,
                            "alice",
                            null);
            assertEquals(2, result.responses().size());
            assertEquals(
                    2,
                    result.events().stream()
                            .filter(event -> Boolean.TRUE.equals(event.data().get("success")))
                            .count());
            assertEquals(frame, DelegationContext.current());
        } finally {
            DelegationContext.exit();
        }
        assertEquals(SubagentRunContext.ROOT, DelegationContext.current());
        verify(callback, times(2)).call(anyString(), any(ToolContext.class));
    }

    @Test
    void delegatedGoalRequiresTrustedFramePersistedLineageAndSameWorkspace() {
        jdbc.execute(
                "CREATE TABLE mate_conversation(conversation_id VARCHAR(255),agent_id"
                        + " BIGINT,workspace_id BIGINT,username VARCHAR(255),parent_conversation_id"
                        + " VARCHAR(255),deleted INT)");
        jdbc.update(
                "INSERT INTO mate_conversation"
                        + " VALUES('conv',2,3,'alice',NULL,0),('child',7,3,'system','conv',0)");
        var claimed = coordinator.claim(continuations.get(1L), goal, now);
        assertTrue(coordinator.markRunning(claimed, now));
        var origin =
                ChatOrigin.web("child", "alice", 3L, null)
                        .withAgent(7L)
                        .withExecutionAttribution(
                                new ExecutionAttribution(
                                        1L,
                                        claimed.attempt().id(),
                                        null,
                                        null,
                                        claimed.attempt().leaseToken()));
        var checkpoint =
                new GoalToolExecutionCheckpoint(
                        attempts,
                        continuations,
                        goals,
                        coordinator,
                        new DataSourceTransactionManager(jdbc.getDataSource()),
                        jdbc,
                        Clock.fixed(
                                now.atZone(ZoneId.systemDefault()).toInstant(),
                                ZoneId.systemDefault()));
        assertThrows(
                ToolExecutionCheckpointRejectedException.class,
                () -> checkpoint.beforeInvocation(origin, "key", "call"));
        DelegationContext.push(new SubagentRunContext(1, "conv", "conv", "child", Set.of()));
        try {
            assertDoesNotThrow(() -> checkpoint.beforeInvocation(origin, "key", "call"));
            assertThrows(
                    ToolExecutionCheckpointRejectedException.class,
                    () ->
                            checkpoint.beforeInvocation(
                                    origin.withWorkspace(99L, null), "key", "call"));
            jdbc.update(
                    "UPDATE mate_conversation SET parent_conversation_id='foreign' WHERE"
                            + " conversation_id='child'");
            assertThrows(
                    ToolExecutionCheckpointRejectedException.class,
                    () -> checkpoint.beforeInvocation(origin, "key", "call"));
        } finally {
            DelegationContext.exit();
        }
    }

    @Test
    void restartAfterExecutedSideEffectBeforePublicationNeverRepeatsCallback() {
        var claimed = coordinator.claim(continuations.get(1L), goal, now);
        assertTrue(coordinator.markRunning(claimed, now));
        assertTrue(
                coordinator.checkpoint(
                        claimed, "uncertain", "tool_started", null, now.plusSeconds(1)));
        var count = new AtomicInteger();
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_external")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            count.incrementAndGet();
                            return "effect committed";
                        });
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        var result =
                executor.execute(
                        List.of(
                                new AssistantMessage.ToolCall(
                                        "stable-call", "function", "write_external", "{}")),
                        "conv",
                        "2",
                        false,
                        "alice",
                        null);
        assertEquals(1, count.get());
        assertEquals("stable-call", result.responses().getFirst().id());
        Consumer<ToolExecutionExecutor.ToolExecutionResult> publication =
                completed -> {
                    assertSame(result, completed);
                    throw new IllegalStateException(
                            "process failed before completion publication/checkpoint");
                };
        assertThrows(IllegalStateException.class, () -> publication.accept(result));
        var restartedAttempts = new GoalAttemptStore(jdbc);
        var restartedContinuations = new GoalContinuationStore(jdbc);
        var restartedInputs = new ConversationInputQueueStore(jdbc, new ObjectMapper());
        var restarted =
                new GoalRecoveryService(
                        restartedAttempts,
                        restartedContinuations,
                        restartedInputs,
                        goals,
                        new DataSourceTransactionManager(jdbc.getDataSource()));
        assertEquals(
                1,
                restarted.recoverExpired(
                        now.plusSeconds(61).atZone(ZoneId.systemDefault()).toInstant()));
        assertEquals("blocked", restartedAttempts.get(claimed.attempt().id()).state());
        assertEquals("blocked", restartedContinuations.get(1L).state());
        assertEquals(
                0,
                restarted.recoverExpired(
                        now.plusSeconds(120).atZone(ZoneId.systemDefault()).toInstant()));
        assertEquals(1, count.get());
        verify(callback, times(1)).call(anyString(), any(ToolContext.class));
    }
}
