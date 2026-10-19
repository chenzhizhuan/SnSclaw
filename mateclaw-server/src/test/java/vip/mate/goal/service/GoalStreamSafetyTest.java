package vip.mate.goal.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import reactor.core.publisher.Flux;

import vip.mate.agent.AgentService;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.model.SegmentOutcome;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class GoalStreamSafetyTest {
    private GoalRunCoordinatorTest database() {
        var db = new GoalRunCoordinatorTest();
        db.now = LocalDateTime.now();
        db.setup();
        return db;
    }

    private GoalSegmentRunnerTest runner(GoalRunCoordinatorTest db) {
        var fixture = new GoalSegmentRunnerTest();
        fixture.setup();
        ReflectionTestUtils.setField(fixture.runner, "coordinator", db.coordinator);
        ReflectionTestUtils.setField(fixture.runner, "goals", db.goals);
        return fixture;
    }

    private GoalRunCoordinator.ClaimedRun claim(GoalRunCoordinatorTest db) {
        var run = db.coordinator.claim(db.continuations.get(1L), db.goal, db.now);
        assertTrue(db.coordinator.markRunning(run, db.now));
        return run;
    }

    @Test
    void staleSafeSnapshotCannotRetryUncertainAttempt() {
        var db = database();
        var run = claim(db);
        assertEquals("safe", run.attempt().replaySafety());
        assertTrue(db.coordinator.checkpoint(run, "uncertain", "tool_started", null, db.now));
        assertTrue(
                db.coordinator.settle(
                        run, new SegmentOutcome.Retry("provider", "timeout"), db.now));
        assertEquals("blocked", db.continuations.get(1L).state());
        assertEquals("blocked", db.attempts.get(run.attempt().id()).state());
        assertEquals("uncertain", db.attempts.get(run.attempt().id()).replaySafety());
    }

    @Test
    void safeProviderFailureStillRetries() {
        var db = database();
        var run = claim(db);
        assertTrue(
                db.coordinator.settle(
                        run, new SegmentOutcome.Retry("provider", "timeout"), db.now));
        assertEquals("retry", db.continuations.get(1L).state());
    }

    @Test
    void committedCompletionStillWinsOverUnsafeRetry() {
        var db = database();
        var run = claim(db);
        assertTrue(db.coordinator.checkpoint(run, "uncertain", "tool_started", null, db.now));
        db.goal.setJsonAcceptanceRequired(true);
        db.goal.setStatus(GoalStatus.COMPLETED);
        assertTrue(
                db.coordinator.settle(
                        run, new SegmentOutcome.Retry("provider", "timeout"), db.now));
        assertEquals("completed", db.continuations.get(1L).state());
    }

    @Test
    void parallelCompletionAndFailedStreamRetainUncertainty() {
        var db = database();
        var fixture = runner(db);
        var run = claim(db);
        when(fixture.agents.chatStructuredStream(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        Flux.just(
                                        AgentService.StreamDelta.event(
                                                "tool_call_started", Map.of("toolCallId", "a")),
                                        AgentService.StreamDelta.event(
                                                "tool_call_started", Map.of("toolCallId", "b")),
                                        AgentService.StreamDelta.event(
                                                "tool_call_completed", Map.of("toolCallId", "a")))
                                .concatWith(
                                        Flux.defer(
                                                () -> {
                                                    assertEquals(
                                                            "uncertain",
                                                            db.attempts
                                                                    .get(run.attempt().id())
                                                                    .replaySafety());
                                                    return Flux.error(
                                                            new IllegalStateException(
                                                                    "provider body failed"));
                                                })));
        assertThrows(RuntimeException.class, () -> fixture.runner.run(run, "continue", false));
        assertEquals("uncertain", db.attempts.get(run.attempt().id()).replaySafety());
        assertNotEquals("message_saved", db.attempts.get(run.attempt().id()).checkpointType());
    }

    @Test
    void providerOnlyFailurePreservesSafeCheckpoint() {
        var db = database();
        var fixture = runner(db);
        var run = claim(db);
        when(fixture.agents.chatStructuredStream(any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("provider failed")));
        assertThrows(RuntimeException.class, () -> fixture.runner.run(run, "continue", false));
        assertEquals("safe", db.attempts.get(run.attempt().id()).replaySafety());
        assertEquals("provider_started", db.attempts.get(run.attempt().id()).checkpointType());
    }

    @Test
    void normalTerminalResolvesBatchAndApprovalStatusSurvives() {
        var db = database();
        var fixture = runner(db);
        var run = claim(db);
        when(fixture.agents.chatStructuredStream(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        Flux.just(
                                AgentService.StreamDelta.event(
                                        "tool_call_started", Map.of("toolCallId", "a")),
                                AgentService.StreamDelta.event(
                                        "tool_call_completed", Map.of("toolCallId", "a")),
                                AgentService.StreamDelta.event(
                                        "tool_approval_requested", Map.of("toolCallId", "b"))));
        assertInstanceOf(
                SegmentOutcome.AwaitApproval.class, fixture.runner.run(run, "continue", false));
        assertEquals("resolved", db.attempts.get(run.attempt().id()).replaySafety());
        assertEquals("message_saved", db.attempts.get(run.attempt().id()).checkpointType());
    }

    @Test
    void rejectedEventFenceFailsStreamImmediately() {
        var db = database();
        var fixture = runner(db);
        var run = claim(db);
        var delegate = spy(db.coordinator);
        doReturn(false)
                .when(delegate)
                .checkpoint(eq(run), eq("uncertain"), eq("tool_started"), isNull(), any());
        ReflectionTestUtils.setField(fixture.runner, "coordinator", delegate);
        var continued = new AtomicBoolean();
        when(fixture.agents.chatStructuredStream(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        Flux.just(
                                        AgentService.StreamDelta.event(
                                                "tool_call_started", Map.of("toolCallId", "a")))
                                .concatWith(
                                        Flux.defer(
                                                () -> {
                                                    continued.set(true);
                                                    return Flux.empty();
                                                })));
        assertThrows(RuntimeException.class, () -> fixture.runner.run(run, "continue", false));
        assertFalse(continued.get());
    }

    @Test
    void errorFallbackNormalTransportDoesNotResolveEffects() {
        var db = database();
        var fixture = runner(db);
        var run = claim(db);
        when(fixture.agents.chatStructuredStream(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        Flux.just(
                                AgentService.StreamDelta.event(
                                        "tool_call_started", Map.of("toolCallId", "a")),
                                AgentService.StreamDelta.event(
                                        "finish_reason", Map.of("reason", "error_fallback"))));
        assertInstanceOf(SegmentOutcome.Blocked.class, fixture.runner.run(run, "continue", false));
        assertEquals("uncertain", db.attempts.get(run.attempt().id()).replaySafety());
    }

    @Test
    void cancelAfterEffectDoesNotResolvePartialMessage() throws Exception {
        var db = database();
        var fixture = runner(db);
        var run = claim(db);
        var entered = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        when(fixture.agents.chatStructuredStream(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        Flux.just(
                                        AgentService.StreamDelta.event(
                                                "tool_call_started", Map.of("toolCallId", "a")))
                                .concatWith(
                                        Flux.defer(
                                                () -> {
                                                    entered.countDown();
                                                    return Flux.never();
                                                })));
        var worker =
                new Thread(
                        () -> {
                            try {
                                fixture.runner.run(run, "continue", false);
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        });
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            fixture.runner.cancel(1L);
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertEquals("uncertain", db.attempts.get(run.attempt().id()).replaySafety());
        } finally {
            fixture.runner.cancelAll();
            worker.join(5000);
        }
    }

    @Test
    void stickyUnknownBlocksAllRequeuePathsFromStaleClaim() {
        for (SegmentOutcome outcome :
                List.of(
                        new SegmentOutcome.Continue("normal"),
                        new SegmentOutcome.Defer("later", LocalDateTime.now().plusMinutes(1)),
                        new SegmentOutcome.Complete("uncommitted"),
                        new SegmentOutcome.Retry("provider", "timeout"))) {
            var db = database();
            var run = claim(db);
            db.goal.setJsonAcceptanceRequired(true);
            assertTrue(
                    db.coordinator.checkpoint(
                            run, "uncertain", "tool_outcome_uncertain", null, db.now));
            assertTrue(db.coordinator.settle(run, outcome, db.now));
            assertEquals("blocked", db.continuations.get(1L).state(), outcome.toString());
            assertEquals(
                    "tool_outcome_uncertain", db.attempts.get(run.attempt().id()).checkpointType());
            assertEquals("uncertain", db.attempts.get(run.attempt().id()).replaySafety());
        }
    }

    @Test
    void stickyUnknownRejectsNewCallbackAndResolvedDowngrades() {
        var db = database();
        var run = claim(db);
        assertTrue(
                db.coordinator.checkpoint(
                        run, "uncertain", "tool_outcome_uncertain", null, db.now));
        assertFalse(db.coordinator.checkpoint(run, "uncertain", "tool_started", null, db.now));
        assertFalse(db.coordinator.checkpoint(run, "resolved", "message_saved", 77L, db.now));
        assertFalse(db.coordinator.checkpoint(run, "safe", "provider_started", null, db.now));
        assertEquals(
                "tool_outcome_uncertain", db.attempts.get(run.attempt().id()).checkpointType());
    }

    @Test
    void stickyUnknownSurvivesNormalTransportAndToolCompletion() {
        var db = database();
        var fixture = runner(db);
        var run = claim(db);
        when(fixture.agents.chatStructuredStream(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        Flux.defer(
                                () -> {
                                    assertTrue(
                                            db.coordinator.checkpoint(
                                                    run,
                                                    "uncertain",
                                                    "tool_outcome_uncertain",
                                                    null,
                                                    db.now));
                                    return Flux.just(
                                            AgentService.StreamDelta.event(
                                                    "tool_call_completed",
                                                    Map.of("toolCallId", "a")),
                                            AgentService.StreamDelta.event(
                                                    "finish_reason", Map.of("reason", "normal")));
                                }));
        assertInstanceOf(SegmentOutcome.Blocked.class, fixture.runner.run(run, "continue", false));
        assertEquals(
                "tool_outcome_uncertain", db.attempts.get(run.attempt().id()).checkpointType());
        assertEquals("uncertain", db.attempts.get(run.attempt().id()).replaySafety());
    }

    @Test
    void committedCompletionPreservesStickyUnknownAudit() {
        var db = database();
        var run = claim(db);
        assertTrue(
                db.coordinator.checkpoint(
                        run, "uncertain", "tool_outcome_uncertain", null, db.now));
        db.goal.setJsonAcceptanceRequired(true);
        db.goal.setStatus(GoalStatus.COMPLETED);
        assertTrue(db.coordinator.settle(run, new SegmentOutcome.Complete("committed"), db.now));
        assertEquals("completed", db.continuations.get(1L).state());
        assertEquals("succeeded", db.attempts.get(run.attempt().id()).state());
        assertEquals(
                "tool_outcome_uncertain", db.attempts.get(run.attempt().id()).checkpointType());
        assertEquals("uncertain", db.attempts.get(run.attempt().id()).replaySafety());
    }

    @Test
    void activeNonJsonCompletionCannotOverrideStickyUnknown() {
        var db = database();
        var run = claim(db);
        db.goal.setJsonAcceptanceRequired(false);
        assertTrue(
                db.coordinator.checkpoint(
                        run, "uncertain", "tool_outcome_uncertain", null, db.now));
        assertTrue(db.coordinator.settle(run, new SegmentOutcome.Complete("unverified"), db.now));
        assertEquals("blocked", db.continuations.get(1L).state());
        assertEquals(
                "tool_outcome_uncertain", db.attempts.get(run.attempt().id()).checkpointType());
    }
}
