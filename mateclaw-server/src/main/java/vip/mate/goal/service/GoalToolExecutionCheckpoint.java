package vip.mate.goal.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ExecutionAttribution;
import vip.mate.agent.graph.executor.ToolExecutionCheckpoint;
import vip.mate.agent.graph.executor.ToolExecutionCheckpointRejectedException;
import vip.mate.goal.model.GoalEntity;
import vip.mate.tool.builtin.DelegationContext;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Persists uncertainty under a live Goal fence before entering a tool callback. */
@Service
public final class GoalToolExecutionCheckpoint implements ToolExecutionCheckpoint {
    public static final String UNKNOWN_OUTCOME = "tool_outcome_uncertain";
    private final GoalAttemptStore attempts;
    private final GoalContinuationStore continuations;
    private final GoalService goals;
    private final GoalRunCoordinator coordinator;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public GoalToolExecutionCheckpoint(
            GoalAttemptStore attempts,
            GoalContinuationStore continuations,
            GoalService goals,
            GoalRunCoordinator coordinator,
            PlatformTransactionManager manager,
            JdbcTemplate jdbc) {
        this(attempts, continuations, goals, coordinator, manager, jdbc, Clock.systemDefaultZone());
    }

    GoalToolExecutionCheckpoint(
            GoalAttemptStore attempts,
            GoalContinuationStore continuations,
            GoalService goals,
            GoalRunCoordinator coordinator,
            PlatformTransactionManager manager,
            JdbcTemplate jdbc,
            Clock clock) {
        this.attempts = attempts;
        this.continuations = continuations;
        this.goals = goals;
        this.coordinator = coordinator;
        this.transactions = new TransactionTemplate(manager);
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void beforeInvocation(ChatOrigin origin, String invocationKey, String providerCallId) {
        checkpoint(origin, false);
    }

    @Override
    public void markUnknownOutcome(ChatOrigin origin, Throwable cause) {
        checkpoint(origin, true);
    }

    private void checkpoint(ChatOrigin origin, boolean unknown) {
        ExecutionAttribution attribution = origin == null ? null : origin.executionAttribution();
        // Cron and approval-only attribution do not imply a Goal attempt.
        if (attribution == null
                || attribution.goalId() == null && attribution.goalAttemptId() == null) return;
        // Joining a caller transaction could enter an external callback before the fence commits.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ToolExecutionCheckpointRejectedException(
                    "Goal tool checkpoint cannot join an active transaction");
        }
        try {
            Boolean accepted =
                    transactions.execute(
                            status -> {
                                if (attribution.goalId() == null
                                        || attribution.goalAttemptId() == null
                                        || attribution.ownerFence() == null
                                        || !continuations.lockGoal(attribution.goalId()))
                                    return false;
                                GoalEntity goal = goals.getById(attribution.goalId());
                                var attempt = attempts.getForUpdate(attribution.goalAttemptId());
                                var continuation = continuations.get(attribution.goalId());
                                if (goal == null
                                        || attempt == null
                                        || continuation == null
                                        || !Objects.equals(attempt.goalId(), goal.getId())
                                        || !Objects.equals(
                                                attempt.conversationId(), goal.getConversationId())
                                        || !Objects.equals(
                                                goal.getWorkspaceId(), origin.workspaceId())
                                        || !Objects.equals(
                                                goal.getCreatedBy(), origin.requesterId())
                                        || !Objects.equals(
                                                attempt.leaseToken(), attribution.ownerFence())
                                        || !validConversationScope(goal, origin)) return false;
                                if (UNKNOWN_OUTCOME.equals(attempt.checkpointType()) && !unknown)
                                    return false;
                                return coordinator.checkpoint(
                                        new GoalRunCoordinator.ClaimedRun(
                                                continuation,
                                                goal,
                                                attempt,
                                                continuation.revision()),
                                        "uncertain",
                                        unknown ? UNKNOWN_OUTCOME : "tool_started",
                                        null,
                                        LocalDateTime.now(clock));
                            });
            if (!Boolean.TRUE.equals(accepted))
                throw new ToolExecutionCheckpointRejectedException(
                        "Goal tool execution fence rejected");
        } catch (ToolExecutionCheckpointRejectedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ToolExecutionCheckpointRejectedException(
                    "Goal tool checkpoint could not be persisted", failure);
        }
    }

    private boolean validConversationScope(GoalEntity goal, ChatOrigin origin) {
        if (Objects.equals(goal.getConversationId(), origin.conversationId())) {
            return Objects.equals(goal.getAgentId(), origin.agentId());
        }
        var frame = DelegationContext.current();
        if (!frame.isDelegated()
                || !Objects.equals(frame.rootConversationId(), goal.getConversationId()))
            return false;
        Set<String> visited = new HashSet<>();
        String conversationId = origin.conversationId();
        String expectedParent = frame.parentConversationId();
        for (int depth = 0; depth <= frame.depth(); depth++) {
            if (conversationId == null || !visited.add(conversationId)) return false;
            var rows =
                    jdbc.queryForList(
                            "SELECT agent_id,workspace_id,username,parent_conversation_id FROM"
                                    + " mate_conversation WHERE conversation_id=? AND deleted=0",
                            conversationId);
            if (rows.size() != 1) return false;
            var row = rows.getFirst();
            Long workspaceId =
                    row.get("workspace_id") instanceof Number number ? number.longValue() : null;
            if (!Objects.equals(workspaceId, origin.workspaceId())) return false;
            Long agentId = row.get("agent_id") instanceof Number number ? number.longValue() : null;
            if (depth == 0
                    && (!Objects.equals(agentId, origin.agentId())
                            || !Objects.equals(row.get("parent_conversation_id"), expectedParent)))
                return false;
            if (Objects.equals(conversationId, goal.getConversationId()))
                return Objects.equals(agentId, goal.getAgentId())
                        && Objects.equals(row.get("username"), goal.getCreatedBy());
            conversationId = (String) row.get("parent_conversation_id");
        }
        return false;
    }
}
