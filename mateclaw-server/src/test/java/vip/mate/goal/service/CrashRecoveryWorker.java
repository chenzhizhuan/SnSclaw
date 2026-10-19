package vip.mate.goal.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.h2.jdbcx.JdbcDataSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ExecutionAttribution;
import vip.mate.agent.graph.executor.ToolExecutionCheckpointRejectedException;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.channel.web.ConversationInputQueueStore;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalStatus;
import vip.mate.tool.guard.ToolGuardResult;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

public class CrashRecoveryWorker {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path folder = Path.of(args[1]);
        Files.createDirectories(folder);
        JdbcDataSource ds = new JdbcDataSource();
        String url =
                "jdbc:h2:file:"
                        + folder.resolve("goal")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE";
        ds.setURL(url);
        var jdbc = new JdbcTemplate(ds);
        boolean initializing = mode.equals("crash") || mode.equals("safe");
        if (initializing) {
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
        }
        LocalDateTime start =
                initializing
                        ? LocalDateTime.now().withNano(0)
                        : LocalDateTime.parse(Files.readString(folder.resolve("start")));
        if (initializing) Files.writeString(folder.resolve("start"), start.toString());
        var attempts = new GoalAttemptStore(jdbc);
        var continuations = new GoalContinuationStore(jdbc);
        var inputs = new ConversationInputQueueStore(jdbc, new ObjectMapper());
        var goal = new GoalEntity();
        goal.setId(1L);
        goal.setConversationId("conv");
        goal.setAgentId(2L);
        goal.setWorkspaceId(3L);
        goal.setCreatedBy("alice");
        goal.setStatus(GoalStatus.ACTIVE);
        goal.setPersistentExecution(true);
        goal.setAutoFollowupEnabled(true);
        GoalService goals = mock(GoalService.class);
        when(goals.getById(1L)).thenReturn(goal);
        var manager = new DataSourceTransactionManager(ds);
        Clock clock =
                Clock.fixed(
                        start.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        var coordinator =
                new GoalRunCoordinator(continuations, attempts, goals, new GoalProperties(), clock);
        var proxy = new ProxyFactory(coordinator);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        coordinator = (GoalRunCoordinator) proxy.getProxy();
        if (initializing) {
            jdbc.update(
                    """
                    INSERT INTO mate_agent_goal(id,conversation_id,agent_id,workspace_id,created_by,title,
                    description,status,persistent_execution,auto_followup_enabled,create_time,update_time)
                    VALUES(1,'conv',2,3,'alice','goal','objective','active',TRUE,TRUE,?,?)
                    """,
                    start,
                    start);
            continuations.discover(start);
            var claimed = coordinator.claim(continuations.get(1L), goal, start);
            assertNotNull(claimed);
            assertTrue(coordinator.markRunning(claimed, start));
            assertTrue(coordinator.checkpoint(claimed, "safe", "provider_started", null, start));
            Files.writeString(folder.resolve("attempt"), claimed.attempt().id());
            if (mode.equals("safe")) {
                System.out.println("SAFE provider_started committed; no tool entry or effect");
                System.out.flush();
                Runtime.getRuntime().halt(73);
            }
            var callback = mock(ToolCallback.class);
            when(callback.getToolDefinition())
                    .thenReturn(
                            ToolDefinition.builder()
                                    .name("write_external")
                                    .description("Write fixture")
                                    .inputSchema("{}")
                                    .build());
            when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
            when(callback.call(anyString(), any(ToolContext.class)))
                    .thenAnswer(
                            invocation -> {
                                // Independent connection proves the pre-entry checkpoint has
                                // committed.
                                try (var independent = ds.getConnection()) {
                                    try (var stmt =
                                            independent.prepareStatement(
                                                    "SELECT checkpoint_type,replay_safety FROM"
                                                        + " mate_goal_attempt WHERE"
                                                        + " attempt_id=?")) {
                                        stmt.setString(1, claimed.attempt().id());
                                        try (var rs = stmt.executeQuery()) {
                                            assertTrue(rs.next());
                                            assertEquals("tool_started", rs.getString(1));
                                            assertEquals("uncertain", rs.getString(2));
                                        }
                                    }
                                }
                                try (var effect =
                                        FileChannel.open(
                                                folder.resolve("effect"),
                                                StandardOpenOption.CREATE,
                                                StandardOpenOption.WRITE,
                                                StandardOpenOption.APPEND)) {
                                    effect.write(ByteBuffer.wrap("one effect\n".getBytes()));
                                    effect.force(true);
                                }
                                System.out.println(
                                        "CALLBACK independent committed tool_started/uncertain;"
                                            + " durable effect; HALT73 before return/publication");
                                System.out.flush();
                                Runtime.getRuntime().halt(73);
                                throw new AssertionError("halt returned");
                            });
            var executor =
                    new ToolExecutionExecutor(
                            AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                            (name, raw) -> ToolGuardResult.allow(),
                            null,
                            null);
            executor.setExecutionCheckpoint(
                    new GoalToolExecutionCheckpoint(
                            attempts, continuations, goals, coordinator, manager, jdbc, clock));
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
            executor.execute(
                    List.of(
                            new AssistantMessage.ToolCall(
                                    "original-id", "function", "write_external", "{}")),
                    "conv",
                    "2",
                    false,
                    "alice",
                    null,
                    origin,
                    Set.of());
            throw new AssertionError("execution unexpectedly returned");
        }
        String id = Files.readString(folder.resolve("attempt"));
        boolean safe = mode.equals("recover-safe");
        assertEquals(safe ? "provider_started" : "tool_started", attempts.get(id).checkpointType());
        assertEquals(safe ? "safe" : "uncertain", attempts.get(id).replaySafety());
        var recovery = new GoalRecoveryService(attempts, continuations, inputs, goals, manager);
        LocalDateTime expired = start.plusSeconds(600);
        assertEquals(
                1, recovery.recoverExpired(expired.atZone(ZoneId.systemDefault()).toInstant()));
        assertEquals(safe ? "retryable" : "blocked", attempts.get(id).state());
        assertEquals(safe ? "retry" : "blocked", continuations.get(1L).state());
        assertEquals(safe ? 1 : 0, continuations.due(expired, 100).size());
        assertEquals(
                0,
                recovery.recoverExpired(
                        expired.plusSeconds(1).atZone(ZoneId.systemDefault()).toInstant()));
        if (safe) {
            assertFalse(Files.exists(folder.resolve("effect")));
        } else {
            assertEquals("one effect\n", Files.readString(folder.resolve("effect")));
            verify(goals).pause(1L, "alice");
            var attribution =
                    new ExecutionAttribution(1L, id, null, null, attempts.get(id).leaseToken());
            var checkpoint =
                    new GoalToolExecutionCheckpoint(
                            attempts, continuations, goals, coordinator, manager, jdbc, clock);
            assertThrows(
                    ToolExecutionCheckpointRejectedException.class,
                    () ->
                            checkpoint.beforeInvocation(
                                    ChatOrigin.web("conv", "alice", 3L, null)
                                            .withAgent(2L)
                                            .withExecutionAttribution(attribution),
                                    "key",
                                    "original-id"));
            var retryCallback = mock(ToolCallback.class);
            when(retryCallback.getToolDefinition())
                    .thenReturn(
                            ToolDefinition.builder()
                                    .name("write_external")
                                    .description("Retry fixture")
                                    .inputSchema("{}")
                                    .build());
            when(retryCallback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
            var retryExecutor =
                    new ToolExecutionExecutor(
                            AgentToolSet.fromCallbacks(List.of(), List.of(retryCallback)),
                            (name, raw) -> ToolGuardResult.allow(),
                            null,
                            null);
            retryExecutor.setExecutionCheckpoint(checkpoint);
            assertThrows(
                    ToolExecutionCheckpointRejectedException.class,
                    () ->
                            retryExecutor.execute(
                                    List.of(
                                            new AssistantMessage.ToolCall(
                                                    "original-id",
                                                    "function",
                                                    "write_external",
                                                    "{}")),
                                    "conv",
                                    "2",
                                    false,
                                    "alice",
                                    null,
                                    ChatOrigin.web("conv", "alice", 3L, null)
                                            .withAgent(2L)
                                            .withExecutionAttribution(attribution),
                                    Set.of()));
            verify(retryCallback, never()).call(anyString(), any(ToolContext.class));
            assertEquals("one effect\n", Files.readString(folder.resolve("effect")));
        }
        System.out.println(
                "FRESH JVM recovery="
                        + (safe
                                ? "RETRY_SAFE, due=1, effects=0"
                                : "BLOCKED, due=0, stale checkpoint denied, effects=1"));
        jdbc.execute("SHUTDOWN");
    }
}
