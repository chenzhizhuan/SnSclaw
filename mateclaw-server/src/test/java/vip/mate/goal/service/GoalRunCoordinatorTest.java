package vip.mate.goal.service;

import org.h2.jdbcx.JdbcDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.core.DecisionService;
import vip.mate.decision.record.JdbcDecisionRecordStore;
import vip.mate.decision.provider.RuleDecisionProvider;
import vip.mate.decision.api.DecisionRecordingException;
import vip.mate.goal.model.GoalEvaluationResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vip.mate.decision.provider.DecisionResult;
import vip.mate.decision.api.DecisionRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import vip.mate.goal.config.GoalProperties;
import vip.mate.decision.api.DecisionTicket;
import vip.mate.decision.api.DecisionMode;
import vip.mate.decision.api.DecisionValue;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.model.SegmentOutcome;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.parallel.Isolated
class GoalRunCoordinatorTest {
    JdbcTemplate jdbc;
    GoalContinuationStore continuations;
    GoalAttemptStore attempts;
    GoalService goals=mock(GoalService.class);
    GoalProperties properties=new GoalProperties();
    GoalRunCoordinator coordinator;
    LocalDateTime now=LocalDateTime.of(2026,8,27,1,0);
    GoalEntity goal;

    @BeforeEach void setup() {
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:"+ UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V120__agent_goal.sql"),
                new ClassPathResource("db/migration/h2/V188__goal_continuation.sql"),
                new ClassPathResource("db/migration/h2/V189__goal_attempt_and_input_queue.sql"),
                new ClassPathResource("db/migration/h2/V198__goal_absolute_owner_leases.sql"),
                new ClassPathResource("db/migration/h2/V200__goal_approval_attempt_handoff.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);continuations=new GoalContinuationStore(jdbc);attempts=new GoalAttemptStore(jdbc);
        coordinator=new GoalRunCoordinator(continuations,attempts,goals,properties,java.time.Clock.fixed(now.atZone(java.time.ZoneId.systemDefault()).toInstant(), java.time.ZoneId.systemDefault()));
        jdbc.update("""
                INSERT INTO mate_agent_goal(id,conversation_id,agent_id,workspace_id,created_by,title,
                description,status,persistent_execution,auto_followup_enabled,create_time,update_time)
                VALUES(1,'conv',2,3,'alice','goal','objective','active',TRUE,TRUE,?,?)
                """,now,now);
        goal=new GoalEntity();goal.setId(1L);goal.setConversationId("conv");goal.setAgentId(2L);
        goal.setWorkspaceId(3L);goal.setCreatedBy("alice");goal.setStatus(GoalStatus.ACTIVE);
        goal.setPersistentExecution(true);goal.setAutoFollowupEnabled(true);
        when(goals.getById(1L)).thenReturn(goal);
        continuations.discover(now);
    }

    @Test void runningAndImmediateSettlementRecordOnlyAfterSuccessfulFence() {
        var adapter = mock(GoalDecisionAdapter.class);
        coordinator.setDecisionAdapter(adapter);
        var ticket = new DecisionTicket("decision", DecisionMode.ACTIVE, new DecisionValue.Choice("CONTINUE"));
        var run = coordinator.claim(continuations.get(1L), goal, now);
        assertTrue(coordinator.markRunning(run, now, ticket));
        verify(adapter).outcome(ticket, true, "RUN_STARTED");
        assertTrue(coordinator.settle(run, new SegmentOutcome.Continue("normal"), now));
        assertFalse(coordinator.markRunning(run, now, ticket));
        verify(adapter).outcome(ticket, false, "FENCE_REJECTED");
        verifyNoMoreInteractions(adapter);
        var next = continuations.get(1L);
        var deferred = coordinator.claim(next, goal, next.nextRunAt());
        assertTrue(coordinator.settle(deferred, new SegmentOutcome.Defer("decision_defer", now.plusMinutes(1)),
                next.nextRunAt(), ticket));
        verify(adapter).outcome(ticket, true, "QUEUED");
    }

    @Test void freshCompletionOverridesRequestedDeferInRecordedActualOutcome() {
        var adapter = mock(GoalDecisionAdapter.class); coordinator.setDecisionAdapter(adapter);
        var ticket = new DecisionTicket("decision", DecisionMode.ACTIVE, new DecisionValue.Choice("DEFER"));
        var run = coordinator.claim(continuations.get(1L), goal, now);
        goal.setStatus(GoalStatus.COMPLETED);
        assertTrue(coordinator.settle(run, new SegmentOutcome.Defer("defer", now.plusSeconds(30)), now, ticket));
        assertEquals("completed", continuations.get(1L).state());
        verify(adapter).outcome(ticket, false, "COMPLETED");
    }

    @Test void freshBudgetPauseSettlesAuthorityButDoesNotApplyDelayProposal() {
        var adapter = mock(GoalDecisionAdapter.class); coordinator.setDecisionAdapter(adapter);
        var ticket = new DecisionTicket("decision", DecisionMode.ACTIVE, new DecisionValue.Choice("RETRY"));
        var run = coordinator.claim(continuations.get(1L), goal, now);
        goal.setStatus(GoalStatus.PAUSED);
        when(goals.isBudgetExhausted(goal)).thenReturn(true);
        when(goals.exhaustionReason(goal)).thenReturn("turn_budget");
        assertTrue(coordinator.settle(run, new SegmentOutcome.Defer("retry", now.plusSeconds(30)), now, ticket));
        assertEquals("budget_limited", continuations.get(1L).state());
        verify(adapter).outcome(ticket, false, "BUDGET_LIMITED");
    }

    @Test void activeOutcomeFailureRollsBackRunningAndSettlementButRetainsProposal() {
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var transaction = new TransactionTemplate(manager);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V203__decision_record.sql")).execute(jdbc.getDataSource());
        var config = new DecisionProperties(); config.setMode(DecisionMode.ACTIVE);
        var records = spy(new JdbcDecisionRecordStore(jdbc, manager, config));
        try (var service = new DecisionService(config, List.of(new RuleDecisionProvider()), records, new SimpleMeterRegistry())) {
            var adapter = new GoalDecisionAdapter(new GoalFollowupService(properties, new ObjectMapper()), service);
            coordinator.setDecisionAdapter(adapter);
            var evaluation = new GoalEvaluationResult(0, "remaining", "continue", false, "", 0, 0, List.of(), null);
            var run = coordinator.claim(continuations.get(1L), goal, now);
            var selection = adapter.select(goal, evaluation, now, run.attempt().id(), "DURABLE");
            doThrow(new IllegalStateException("audit unavailable")).when(records).outcome(any(), any(), any());
            assertThrows(DecisionRecordingException.class, () -> transaction.executeWithoutResult(status ->
                    coordinator.markRunning(run, now, selection.ticket())));
            assertEquals("claimed", attempts.get(run.attempt().id()).state());
            assertThrows(DecisionRecordingException.class, () -> transaction.executeWithoutResult(status ->
                    coordinator.settle(run, new SegmentOutcome.Defer("defer", now.plusSeconds(30)), now, selection.ticket())));
            assertEquals(run.attempt().id(), continuations.get(1L).currentAttemptId());
            assertEquals("claimed", attempts.get(run.attempt().id()).state());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome", Integer.class));
        }
    }

    @Test void successfulOutcomeJoinsCommitAndLateRollbackKeepsOnlyIndependentProposal() {
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var transaction = new TransactionTemplate(manager);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V203__decision_record.sql")).execute(jdbc.getDataSource());
        var config = new DecisionProperties(); config.setMode(DecisionMode.ACTIVE);
        var records = new JdbcDecisionRecordStore(jdbc, manager, config);
        try (var service = new DecisionService(config, List.of(new RuleDecisionProvider()), records, new SimpleMeterRegistry())) {
            var adapter = new GoalDecisionAdapter(new GoalFollowupService(properties, new ObjectMapper()), service);
            coordinator.setDecisionAdapter(adapter);
            var run = coordinator.claim(continuations.get(1L), goal, now);
            transaction.executeWithoutResult(status -> {
                var selection = adapter.select(goal, null, now, run.attempt().id(), "DURABLE");
                assertTrue(coordinator.settle(run, new SegmentOutcome.Defer("retry", now.plusSeconds(30)), now, selection.ticket()));
                status.setRollbackOnly();
            });
            assertEquals("claimed", attempts.get(run.attempt().id()).state());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome", Integer.class));
            var selection = adapter.select(goal, null, now, run.attempt().id(), "DURABLE");
            transaction.executeWithoutResult(status -> coordinator.settle(run,
                    new SegmentOutcome.Defer("retry", now.plusSeconds(30)), now, selection.ticket()));
            assertEquals("QUEUED", jdbc.queryForObject("SELECT actual_value FROM mate_decision_outcome", String.class));
            assertEquals("APPLIED", jdbc.queryForObject("SELECT outcome FROM mate_decision_outcome", String.class));
        }
    }

    @Test void activeStartRechecksStoppedGoalAfterDecisionButShadowKeepsBaseline() {
        var adapter = mock(GoalDecisionAdapter.class); coordinator.setDecisionAdapter(adapter);
        var run = coordinator.claim(continuations.get(1L), goal, now);
        goal.setStatus(GoalStatus.PAUSED);
        var active = new DecisionTicket("active", DecisionMode.ACTIVE, new DecisionValue.Choice("CONTINUE"));
        assertFalse(coordinator.markRunning(run, now, active));
        assertEquals("claimed", attempts.get(run.attempt().id()).state());
        verify(adapter).outcome(active, false, "GOAL_NOT_RUNNABLE");
        var shadow = new DecisionTicket("shadow", DecisionMode.SHADOW, new DecisionValue.Choice("CONTINUE"));
        assertTrue(coordinator.markRunning(run, now, shadow));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void activeDelayCannotQueueAfterConcurrentPauseOrAutoFollowupDisable(boolean paused) {
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var transaction = new TransactionTemplate(manager);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V203__decision_record.sql")).execute(jdbc.getDataSource());
        var config = new DecisionProperties(); config.setMode(DecisionMode.ACTIVE);
        var records = new JdbcDecisionRecordStore(jdbc, manager, config);
        var provider = new RuleDecisionProvider() {
            @Override public DecisionResult decide(DecisionRequest request) {
                return DecisionResult.proposed(new DecisionValue.Choice("DEFER"), 1, "delay-v1");
            }
        };
        try (var service = new DecisionService(config, List.of(provider), records, new SimpleMeterRegistry())) {
            var adapter = new GoalDecisionAdapter(new GoalFollowupService(properties, new ObjectMapper()), service);
            coordinator.setDecisionAdapter(adapter);
            var run = coordinator.claim(continuations.get(1L), goal, now);
            var evaluation = new GoalEvaluationResult(0, "remaining", "continue", false, "", 0, 0, List.of(), null);
            var selected = adapter.select(goal, evaluation, now, run.attempt().id(), "DURABLE");
            assertEquals("DEFER", selected.decision().action().name());
            if (paused) {
                goal.setStatus(GoalStatus.PAUSED);
                jdbc.update("UPDATE mate_agent_goal SET status='paused' WHERE id=1");
            } else {
                goal.setAutoFollowupEnabled(false);
                jdbc.update("UPDATE mate_agent_goal SET auto_followup_enabled=FALSE WHERE id=1");
            }
            transaction.executeWithoutResult(status -> assertFalse(coordinator.settle(run,
                    new SegmentOutcome.Defer(selected.decision().reason(), selected.decision().nextRunAt()), now, selected.ticket())));
            assertEquals("claimed", attempts.get(run.attempt().id()).state());
            assertEquals("running", continuations.get(1L).state());
            assertEquals("NOT_APPLIED", jdbc.queryForObject("SELECT outcome FROM mate_decision_outcome", String.class));
            assertEquals("GOAL_NOT_RUNNABLE", jdbc.queryForObject("SELECT actual_value FROM mate_decision_outcome", String.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OFF", "SHADOW"})
    void observationModesKeepLegacyDelaySettlementAfterPause(String mode) {
        var adapter = mock(GoalDecisionAdapter.class); coordinator.setDecisionAdapter(adapter);
        var run = coordinator.claim(continuations.get(1L), goal, now);
        goal.setStatus(GoalStatus.PAUSED);
        var ticket = new DecisionTicket("ticket", DecisionMode.valueOf(mode), new DecisionValue.Choice("DEFER"));
        assertTrue(coordinator.settle(run, new SegmentOutcome.Defer("cooldown", now.plusSeconds(30)), now, ticket));
        assertEquals("queued", continuations.get(1L).state());
    }

    @Test void claimBindsAttemptAndStaleSettlementCannotOverwriteNewProjection() {
        var claim=coordinator.claim(continuations.get(1L),goal,now);
        assertNotNull(claim);
        assertEquals("claimed",attempts.get(claim.attempt().id()).state());
        assertEquals(claim.attempt().id(),continuations.get(1L).currentAttemptId());
        assertTrue(coordinator.markRunning(claim,now));
        assertTrue(coordinator.settle(claim,new SegmentOutcome.Continue("unfinished"),now));
        assertEquals("queued",continuations.get(1L).state());
        assertEquals("succeeded",attempts.get(claim.attempt().id()).state());
        assertFalse(coordinator.settle(claim,new SegmentOutcome.Complete("late"),now.plusSeconds(1)));
    }

    @Test void retryAndBlockedOutcomesHaveExplicitTerminalAttemptStates() {
        var retry=coordinator.claim(continuations.get(1L),goal,now);
        assertTrue(coordinator.markRunning(retry,now));
        assertTrue(coordinator.settle(retry,new SegmentOutcome.Retry("provider","timeout"),now));
        assertEquals("retryable",attempts.get(retry.attempt().id()).state());
        var due=continuations.get(1L);
        var blocked=coordinator.claim(due,goal,due.nextRunAt());
        assertTrue(coordinator.markRunning(blocked,due.nextRunAt()));
        assertTrue(coordinator.settle(blocked,new SegmentOutcome.Blocked("tool","review"),due.nextRunAt()));
        assertEquals("blocked",attempts.get(blocked.attempt().id()).state());
        assertEquals("blocked",continuations.get(1L).state());
    }

    @Test void continuationUsesTheLargerOfGlobalAndGoalCooldowns() {
        properties.setMinimumContinuationIntervalSeconds(300);
        goal.setFollowupCooldownSeconds(0);
        var first=coordinator.claim(continuations.get(1L),goal,now);
        assertTrue(coordinator.markRunning(first,now));
        assertTrue(coordinator.settle(first,new SegmentOutcome.Continue("unfinished"),now));
        assertEquals(now.plusSeconds(300),continuations.get(1L).nextRunAt());

        LocalDateTime secondStart=now.plusSeconds(300);
        goal.setFollowupCooldownSeconds(600);
        var second=coordinator.claim(continuations.get(1L),goal,secondStart);
        assertTrue(coordinator.markRunning(second,secondStart));
        assertTrue(coordinator.settle(second,new SegmentOutcome.Continue("unfinished"),secondStart));
        assertEquals(secondStart.plusSeconds(600),continuations.get(1L).nextRunAt());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"2026-11-01T05:59:50Z", "2026-11-01T06:00:10Z"})
    void leaseDurationStaysSixtyRealSecondsAcrossDstRollback(String timestamp) {
        java.util.TimeZone previous = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            var instant = java.time.Instant.parse(timestamp);
            var reference = new java.util.concurrent.atomic.AtomicReference<>(instant);
            var clock = new java.time.Clock() {
                public java.time.ZoneId getZone() { return java.time.ZoneId.of("America/New_York"); }
                public java.time.Clock withZone(java.time.ZoneId zone) { return java.time.Clock.fixed(instant(), zone); }
                public java.time.Instant instant() { return reference.get(); }
            };
            var timed = new GoalRunCoordinator(continuations, attempts, goals, properties, clock);
            var local = java.time.LocalDateTime.ofInstant(instant, clock.getZone());
            var run = timed.claim(continuations.get(1L), goal, local);
            assertNotNull(run);
            assertEquals(instant.plusSeconds(60).getEpochSecond(), jdbc.queryForObject(
                    "SELECT lease_until_epoch_second FROM mate_goal_attempt WHERE attempt_id=?", Long.class, run.attempt().id()));
            reference.set(instant.plusSeconds(10));
            assertTrue(timed.renew(run, java.time.LocalDateTime.ofInstant(reference.get(), clock.getZone())));
            assertEquals(reference.get().plusSeconds(60).getEpochSecond(), jdbc.queryForObject(
                    "SELECT lease_until_epoch_second FROM mate_goal_continuation WHERE goal_id=1", Long.class));
            reference.set(reference.get().plusSeconds(61));
            var expiredLocal = java.time.LocalDateTime.ofInstant(reference.get(), clock.getZone());
            assertFalse(timed.renew(run, expiredLocal));
            var recovery = new GoalRecoveryService(attempts, continuations,
                    new vip.mate.channel.web.ConversationInputQueueStore(jdbc, new com.fasterxml.jackson.databind.ObjectMapper()), goals,
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            assertEquals(1, recovery.recoverExpired(reference.get()));
            assertEquals("retry", continuations.get(1L).state());
            var next = timed.claim(continuations.get(1L), goal, expiredLocal);
            assertNotNull(next);
            assertNotEquals(run.attempt().leaseToken(), next.attempt().leaseToken());
        } finally { java.util.TimeZone.setDefault(previous); }
    }

    @Test void delayedTickCannotRenewUsingItsPreLockTimestamp() {
        var reference = new java.util.concurrent.atomic.AtomicReference<>(now.atZone(java.time.ZoneId.systemDefault()).toInstant());
        var clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneId.systemDefault(); }
            public java.time.Clock withZone(java.time.ZoneId zone) { return java.time.Clock.fixed(instant(), zone); }
            public java.time.Instant instant() { return reference.get(); }
        };
        var timed = new GoalRunCoordinator(continuations, attempts, goals, properties, clock);
        var run = timed.claim(continuations.get(1L), goal, now);
        assertTrue(timed.markRunning(run, now));
        reference.set(reference.get().plusSeconds(61));
        assertFalse(timed.renew(run, now));
        assertFalse(timed.checkpoint(run, "resolved", "tool_completed", null, now));
        assertFalse(timed.settle(run, new SegmentOutcome.Complete("delayed"), now));
    }

    @Test void selectedJsonGoalCannotSettleCompletedFromSegmentClaimAlone() {
        goal.setJsonAcceptanceRequired(true);
        var run=coordinator.claim(continuations.get(1L),goal,now);
        assertNotNull(run);
        assertTrue(coordinator.markRunning(run,now));
        assertTrue(coordinator.settle(run,new SegmentOutcome.Complete("model claim"),now));
        assertEquals("retry",continuations.get(1L).state());
        assertEquals("retryable",attempts.get(run.attempt().id()).state());
        assertEquals("json_completion_not_committed",continuations.get(1L).reason());
    }

}
