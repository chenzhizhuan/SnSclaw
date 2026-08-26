package vip.mate.goal.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.agent.runtime.RunningConversationRegistry;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoalContinuationSupervisorTest {
    GoalContinuationStore store = mock(GoalContinuationStore.class);
    GoalService goals = mock(GoalService.class);
    GoalSegmentRunner runner = mock(GoalSegmentRunner.class);
    RunningConversationRegistry running = mock(RunningConversationRegistry.class);
    ChatStreamTracker streams = mock(ChatStreamTracker.class);
    GoalProperties properties = new GoalProperties();
    LocalDateTime now = LocalDateTime.of(2026,8,26,12,0);
    GoalEntity goal = new GoalEntity();
    GoalContinuationSupervisor supervisor;

    @BeforeEach void setup() {
        goal.setId(1L); goal.setConversationId("conv"); goal.setStatus(GoalStatus.ACTIVE);
        goal.setPersistentExecution(true); goal.setAutoFollowupEnabled(true);
        goal.setTitle("full goal"); goal.setTurnBudget(0); goal.setLlmCallBudget(0);
        when(goals.getById(1L)).thenReturn(goal);
        when(store.due(any(), anyInt())).thenReturn(List.of(new GoalContinuationStore.Continuation(
                1L,"conv","queued",now,null,null,0,"")));
        when(store.claim(eq(1L),anyString(),any(),any())).thenReturn(true);
        when(store.settle(eq(1L),anyString(),anyString(),any(),anyInt(),anyString())).thenReturn(true);
        when(runner.run(any(),anyString(),anyBoolean())).thenReturn(new GoalSegmentRunner.Result("normal",false));
        supervisor = new GoalContinuationSupervisor(store, goals, properties,
                new GoalFollowupService(properties,new ObjectMapper()), runner, running, streams,
                Clock.fixed(now.toInstant(ZoneOffset.UTC),ZoneOffset.UTC), Runnable::run);
    }

    @Test void incompleteGoalIsRescheduledAcrossMultipleSegments() {
        supervisor.tick(); supervisor.tick(); supervisor.tick();
        verify(runner,times(3)).run(eq(goal),contains("full goal"),eq(false));
        verify(store,times(3)).settle(eq(1L),anyString(),eq("queued"),any(),eq(0),anyString());
    }

    @Test void cooldownIsDurablyDeferredWithoutCallingModel() {
        goal.setFollowupCooldownSeconds(60); goal.setLastFollowupAt(now.minusSeconds(10));
        supervisor.tick();
        verifyNoInteractions(runner);
        verify(store).settle(eq(1L),anyString(),eq("queued"),eq(now.plusSeconds(50)),eq(0),anyString());
    }

    @Test void neverStartsAlongsideUserTurnOrQueuedInput() {
        when(running.isActive("conv")).thenReturn(true);
        supervisor.tick();
        verify(store,never()).claim(any(),any(),any(),any());
        verifyNoInteractions(runner);
    }

    @Test void completionPreventsAnotherTurn() {
        when(runner.run(any(),anyString(),anyBoolean())).thenAnswer(inv -> {
            goal.setStatus(GoalStatus.COMPLETED); return new GoalSegmentRunner.Result("normal",false);
        });
        supervisor.tick(); supervisor.tick();
        verify(runner).run(any(),anyString(),anyBoolean());
        verify(store).settle(eq(1L),anyString(),eq("completed"),any(),eq(0),anyString());
    }

    @Test void shutdownCancellationLeavesLeaseForRestartRecovery() {
        when(runner.run(any(),anyString(),anyBoolean())).thenAnswer(inv -> {
            supervisor.close();
            return new GoalSegmentRunner.Result("stopped",false);
        });
        supervisor.tick();
        verify(runner).cancelAll();
        verify(store,never()).settle(any(),anyString(),anyString(),any(),anyInt(),anyString());
    }

    @Test void budgetReachedDuringSegmentIsReportedAsResumableBudgetLimit() {
        when(runner.run(any(),anyString(),anyBoolean())).thenAnswer(inv -> {
            goal.setStatus(GoalStatus.PAUSED);
            when(goals.isBudgetExhausted(goal)).thenReturn(true);
            when(goals.exhaustionReason(goal)).thenReturn("turn_budget");
            return new GoalSegmentRunner.Result("normal",false);
        });
        supervisor.tick();
        verify(store).settle(eq(1L),anyString(),eq("budget_limited"),any(),eq(0),eq("turn_budget"));
    }

    @Test void transientFailureGetsBackoffAndPermanentErrorBlocks() {
        when(runner.run(any(),anyString(),anyBoolean())).thenThrow(new java.io.UncheckedIOException(new java.io.IOException("connection reset")));
        supervisor.tick();
        verify(store).settle(eq(1L),anyString(),eq("retry"),eq(now.plusSeconds(5)),eq(1),anyString());
        reset(store);
        when(store.due(any(),anyInt())).thenReturn(List.of(new GoalContinuationStore.Continuation(1L,"conv","queued",now,null,null,0,"")));
        when(store.claim(any(),any(),any(),any())).thenReturn(true);
        doThrow(new IllegalArgumentException("invalid configuration")).when(runner).run(any(),anyString(),anyBoolean());
        supervisor.tick();
        verify(store).settle(eq(1L),anyString(),eq("blocked"),any(),eq(1),anyString());
    }

    @Test void approvalAndStopNeverTurnIntoAutomaticRetry() {
        when(runner.run(any(),anyString(),anyBoolean())).thenReturn(new GoalSegmentRunner.Result("normal",true));
        supervisor.tick();
        verify(store).settle(eq(1L),anyString(),eq("waiting_approval"),any(),eq(0),anyString());
        when(runner.run(any(),anyString(),anyBoolean())).thenReturn(new GoalSegmentRunner.Result("stopped",false));
        supervisor.tick();
        verify(store).settle(eq(1L),anyString(),eq("paused"),any(),eq(0),anyString());
    }
}
