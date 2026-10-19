package vip.mate.goal.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ExecutionAttribution;
import vip.mate.agent.graph.executor.ToolExecutionCheckpointRejectedException;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.tool.guard.ToolGuardResult;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class GoalCheckpointTransactionTest {
    private record Fixture(
            ToolPublicationRecoveryTest database,
            ChatOrigin origin,
            GoalRunCoordinator.ClaimedRun run,
            GoalToolExecutionCheckpoint checkpoint,
            TransactionTemplate transaction) {}

    private Fixture fixture() {
        var database = new ToolPublicationRecoveryTest();
        database.setup();
        var run =
                database.coordinator.claim(
                        database.continuations.get(1L), database.goal, database.now);
        assertTrue(database.coordinator.markRunning(run, database.now));
        var origin =
                ChatOrigin.web("conv", "alice", 3L, null)
                        .withAgent(2L)
                        .withExecutionAttribution(
                                new ExecutionAttribution(
                                        1L,
                                        run.attempt().id(),
                                        null,
                                        null,
                                        run.attempt().leaseToken()));
        var manager = new DataSourceTransactionManager(database.jdbc.getDataSource());
        var checkpoint =
                new GoalToolExecutionCheckpoint(
                        database.attempts,
                        database.continuations,
                        database.goals,
                        database.coordinator,
                        manager,
                        database.jdbc,
                        Clock.fixed(
                                database.now.atZone(ZoneId.systemDefault()).toInstant(),
                                ZoneId.systemDefault()));
        return new Fixture(database, origin, run, checkpoint, new TransactionTemplate(manager));
    }

    @Test
    void ambientGoalFenceAndUnknownReportAreRejectedWithoutMutation() throws Exception {
        var fixture = fixture();
        fixture.transaction.executeWithoutResult(
                status -> {
                    assertThrows(
                            ToolExecutionCheckpointRejectedException.class,
                            () ->
                                    fixture.checkpoint.beforeInvocation(
                                            fixture.origin, "key", "call"));
                    assertThrows(
                            ToolExecutionCheckpointRejectedException.class,
                            () ->
                                    fixture.checkpoint.markUnknownOutcome(
                                            fixture.origin, new IllegalStateException("unknown")));
                });
        assertEquals(
                "safe", fixture.database.attempts.get(fixture.run.attempt().id()).replaySafety());
        fixture.checkpoint.beforeInvocation(fixture.origin, "key", "call");
        try (var reader = Executors.newSingleThreadExecutor()) {
            assertEquals(
                    "uncertain",
                    reader.submit(
                                    () ->
                                            fixture.database
                                                    .attempts
                                                    .get(fixture.run.attempt().id())
                                                    .replaySafety())
                            .get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void ambientTransactionCannotEnterCallbackAtBatchOrInvocationFence() {
        var fixture = fixture();
        for (boolean invocationOnly : new boolean[] {false, true}) {
            var effects = new AtomicInteger();
            var checks = new AtomicInteger();
            var rejected = new AtomicBoolean();
            ToolCallback callback = mock(ToolCallback.class);
            when(callback.getToolDefinition())
                    .thenReturn(
                            ToolDefinition.builder()
                                    .name("write_external")
                                    .description("Fixture effect")
                                    .inputSchema("{}")
                                    .build());
            when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
            when(callback.call(anyString(), any(ToolContext.class)))
                    .thenAnswer(
                            invocation -> {
                                effects.incrementAndGet();
                                return "effect";
                            });
            var executor =
                    new ToolExecutionExecutor(
                            AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                            (name, arguments) -> ToolGuardResult.allow(),
                            null,
                            null);
            executor.setExecutionCheckpoint(
                    (origin, key, id) -> {
                        if (!invocationOnly || checks.incrementAndGet() > 1)
                            fixture.checkpoint.beforeInvocation(origin, key, id);
                    });
            fixture.transaction.executeWithoutResult(
                    status -> {
                        try {
                            executor.execute(
                                    List.of(
                                            new AssistantMessage.ToolCall(
                                                    "call", "function", "write_external", "{}")),
                                    "conv",
                                    "2",
                                    false,
                                    "alice",
                                    null,
                                    fixture.origin,
                                    Set.of());
                        } catch (ToolExecutionCheckpointRejectedException expected) {
                            rejected.set(true);
                        }
                        status.setRollbackOnly();
                    });
            assertEquals(0, effects.get());
            assertTrue(rejected.get());
            if (invocationOnly) assertEquals(2, checks.get());
        }
    }

    @Test
    void cronOnlyAndUnattributedToolsRemainNoopInAmbientTransaction() {
        var fixture = fixture();
        var cron =
                ChatOrigin.EMPTY.withExecutionAttribution(
                        new ExecutionAttribution(null, null, 99L, null, "cron-fence"));
        fixture.transaction.executeWithoutResult(
                status -> {
                    assertDoesNotThrow(
                            () -> fixture.checkpoint.beforeInvocation(cron, "key", "call"));
                    assertDoesNotThrow(
                            () ->
                                    fixture.checkpoint.markUnknownOutcome(
                                            cron, new IllegalStateException("cron")));
                    assertDoesNotThrow(
                            () ->
                                    fixture.checkpoint.beforeInvocation(
                                            ChatOrigin.EMPTY, "key", "call"));
                });
        assertEquals(
                "safe", fixture.database.attempts.get(fixture.run.attempt().id()).replaySafety());
    }
}
