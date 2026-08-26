package vip.mate.goal.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vip.mate.agent.runtime.RunningConversationRegistry;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Owns cross-turn liveness. Graph recursion limits bound segments, not goal lifetime. */
@Slf4j
@Component
public class GoalContinuationSupervisor {
    private final GoalContinuationStore store;
    private final GoalService goals;
    private final GoalProperties properties;
    private final GoalFollowupService followups;
    private final GoalSegmentRunner runner;
    private final RunningConversationRegistry running;
    private final ChatStreamTracker streams;
    private final Clock clock;
    private final Executor executor;
    private final ConcurrentHashMap<Long, String> active = new ConcurrentHashMap<>();
    private volatile boolean closing;

    @Autowired
    public GoalContinuationSupervisor(GoalContinuationStore store, GoalService goals, GoalProperties properties,
            GoalFollowupService followups, GoalSegmentRunner runner, RunningConversationRegistry running,
            ChatStreamTracker streams) {
        this(store,goals,properties,followups,runner,running,streams,Clock.systemDefaultZone(),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    GoalContinuationSupervisor(GoalContinuationStore store, GoalService goals, GoalProperties properties,
            GoalFollowupService followups, GoalSegmentRunner runner, RunningConversationRegistry running,
            ChatStreamTracker streams, Clock clock, Executor executor) {
        this.store=store; this.goals=goals; this.properties=properties; this.followups=followups;
        this.runner=runner; this.running=running; this.streams=streams; this.clock=clock; this.executor=executor;
    }

    @Scheduled(fixedDelayString="${mateclaw.goal.supervisor-poll-ms:5000}", initialDelayString="${mateclaw.goal.supervisor-poll-ms:5000}")
    public void tick() {
        if (closing || !properties.isEnabled() || !properties.isAllowAutoFollowup()) return;
        LocalDateTime now = LocalDateTime.now(clock);
        active.forEach((id, token) -> {
            GoalEntity goal = goals.getById(id);
            boolean cancelled = goal.getStatus()==GoalStatus.PAUSED || goal.getStatus()==GoalStatus.ABANDONED
                    || !Boolean.TRUE.equals(goal.getAutoFollowupEnabled());
            if (cancelled || !store.renew(id, token, now.plusSeconds(60))) runner.cancel(id);
        });
        store.discover(now);
        for (var candidate : store.due(now, 20)) {
            if (active.size() >= 4) break;
            String conv = candidate.conversationId();
            if (active.containsKey(candidate.goalId()) || running.isActive(conv)
                    || streams.isRunning(conv)) continue;
            GoalEntity goal = goals.getById(candidate.goalId());
            if (!eligible(goal)) continue;
            String token = UUID.randomUUID().toString();
            if (active.putIfAbsent(goal.getId(),token) != null) continue;
            try {
                if (!store.claim(goal.getId(),token,now,now.plusSeconds(60))) {
                    active.remove(goal.getId(),token); continue;
                }
                executor.execute(() -> execute(goal, candidate, token));
            } catch (RuntimeException error) {
                active.remove(goal.getId(),token);
                store.settle(goal.getId(),token,"retry",now.plusSeconds(5),candidate.failures()+1,"dispatch_failed");
                log.warn("Goal {} dispatch failed",goal.getId(),error);
            }
        }
    }

    private void execute(GoalEntity initial, GoalContinuationStore.Continuation candidate, String token) {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            if (closing) return;
            GoalEntity goal = goals.getById(initial.getId());
            if (!eligible(goal)) {
                settle(initial,token,"paused",now,0,"goal_not_runnable"); return;
            }
            var decision = followups.decide(goal,new GoalEvaluationResult(0,goal.getProgressSummary(),
                    GoalEvaluationResult.DECISION_CONTINUE,false,"",0,0,List.of(),null),now);
            switch (decision.action()) {
                case DEFER, RETRY -> {
                    settle(goal,token,"queued",decision.nextRunAt(),candidate.failures(),decision.reason()); return;
                }
                case BUDGET_LIMITED -> {
                    goals.markExhausted(goal.getId(),decision.reason());
                    settle(goal,token,"budget_limited",now,0,decision.reason()); return;
                }
                case COMPLETE, DISABLED -> {
                    settle(goal,token,"paused",now,0,decision.reason()); return;
                }
                case CONTINUE -> { }
            }
            GoalSegmentRunner.Result result = runner.run(goal,decision.prompt(),"running".equals(candidate.state()));
            // Shutdown cancellation is not user Stop: retain the lease for recovery.
            if (closing) return;
            GoalEntity fresh = goals.getById(goal.getId());
            if (fresh.getStatus() == GoalStatus.COMPLETED) {
                settle(goal,token,"completed",now,0,"goal_completed");
            } else if (fresh.getStatus() == GoalStatus.PAUSED && goals.isBudgetExhausted(fresh)) {
                settle(goal,token,"budget_limited",now,0,goals.exhaustionReason(fresh));
            } else if (!eligible(fresh) || "stopped".equals(result.finishReason())) {
                boolean waiting = fresh.getProgressSummary()!=null && fresh.getProgressSummary().startsWith("Waiting for input:");
                settle(goal,token,waiting ? "waiting_input" : "paused",now,0,
                        waiting ? fresh.getProgressSummary() : "goal_paused_or_stopped");
            } else if (result.awaitingApproval()) {
                settle(goal,token,"waiting_approval",now.plusSeconds(5),0,"approval_required");
            } else if ("error_fallback".equals(result.finishReason())) {
                // The graph discarded the original error category; do not blindly replay tools.
                goals.pause(goal.getId(),goal.getCreatedBy());
                settle(goal,token,"blocked",now,candidate.failures()+1,"graph_error_requires_review");
            } else if (result.evaluationUnavailable()) {
                settle(goal,token,"retry",now.plusSeconds(30),candidate.failures()+1,"evaluation_unavailable");
            } else {
                int cooldown = fresh.getFollowupCooldownSeconds() == null ? 0 : fresh.getFollowupCooldownSeconds();
                settle(goal,token,"queued",LocalDateTime.now(clock).plusSeconds(Math.max(1,cooldown)),0,"unfinished");
            }
        } catch (RuntimeException error) {
            // A shutdown/lost-lease cancellation is not a task failure. Leave the
            // running lease for restart recovery; the runner saves partial evidence.
            if (closing || Thread.currentThread().isInterrupted()) return;
            int failures = Math.min(1000,candidate.failures()+1);
            boolean transientError = retryable(error);
            if (!transientError) {
                GoalEntity fresh=goals.getById(initial.getId());
                if (eligible(fresh)) goals.pause(fresh.getId(),fresh.getCreatedBy());
            }
            long delay = Math.min(300,5L << Math.min(6,failures-1));
            settle(initial,token,transientError ? "retry" : "blocked",now.plusSeconds(delay),failures,
                    transientError ? "transient_provider_error" : "execution_requires_review");
            log.warn("Goal {} segment failed ({})",initial.getId(),transientError ? "retry" : "blocked",error);
        } finally {
            active.remove(initial.getId(),token);
        }
    }

    private void settle(GoalEntity goal, String token, String state, LocalDateTime due, int failures, String reason) {
        if (store.settle(goal.getId(),token,state,due,failures,reason)) {
            streams.broadcastObject(goal.getConversationId(),"goal_continuation",store.get(goal.getId()));
        }
    }

    private static boolean eligible(GoalEntity goal) {
        return goal != null && goal.getStatus()==GoalStatus.ACTIVE
                && Boolean.TRUE.equals(goal.getPersistentExecution()) && Boolean.TRUE.equals(goal.getAutoFollowupEnabled());
    }

    static boolean retryable(Throwable error) {
        for (Throwable e=error; e!=null; e=e.getCause()) {
            if (e instanceof java.io.IOException || e instanceof java.util.concurrent.TimeoutException
                    || e instanceof org.springframework.web.client.ResourceAccessException) return true;
            if (e instanceof org.springframework.web.client.RestClientResponseException response) {
                int code = response.getStatusCode().value();
                return code==408 || code==429 || code>=500;
            }
            if (e instanceof vip.mate.exception.MateClawException mate && mate.getCode()==409) return true;
        }
        return false;
    }

    @EventListener
    public void stopped(GoalExecutionSignal.Stop event) {
        runner.stopConversation(event.conversationId());
        GoalEntity goal = goals.findActiveByConversation(event.conversationId());
        if (goal != null && Boolean.TRUE.equals(goal.getPersistentExecution())) {
            runner.cancel(goal.getId());
            goals.pause(goal.getId(),goal.getCreatedBy());
            store.suspendConversation(event.conversationId(),"user_stopped");
        }
    }

    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT, fallbackExecution=true)
    public void resumed(GoalExecutionSignal.Resume event) {
        store.resume(event.goalId(),LocalDateTime.now(clock));
    }

    @EventListener
    public void turnFinished(GoalExecutionSignal.TurnFinished event) {
        store.turnFinished(event.conversationId(),LocalDateTime.now(clock));
    }

    @EventListener
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void approvalResolved(vip.mate.approval.event.ApprovalResolutionEvent event) {
        if ("denied".equals(event.resolutionNote()) || "TIMEOUT".equals(event.decisionSource())) {
            stopped(new GoalExecutionSignal.Stop(event.conversationId()));
        }
        // Approval execution belongs to the existing replay path. Only its
        // TurnFinished event releases waiting_approval; never consume/replay here.
    }

    @PreDestroy public void close() {
        closing=true;
        runner.cancelAll();
        if (executor instanceof java.util.concurrent.ExecutorService workers) {
            // Cancellation must finish checkpoint persistence without interrupting JDBC I/O.
            workers.shutdown();
            try {
                if (!workers.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("Goal workers did not finish shutdown persistence within 10 seconds");
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
