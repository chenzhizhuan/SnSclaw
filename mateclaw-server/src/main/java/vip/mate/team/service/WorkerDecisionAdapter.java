package vip.mate.team.service;

import org.springframework.stereotype.Service;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.core.DecisionService;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;

/** Judges result quality without granting new attempts or bypassing task approval. */
@Service
public class WorkerDecisionAdapter {
    private final DecisionService decisions;
    private final DecisionProperties properties;
    private final TeamService teams;

    public WorkerDecisionAdapter(DecisionService decisions, DecisionProperties properties, TeamService teams) {
        this.decisions = decisions; this.properties = properties; this.teams = teams;
    }

    public record Snapshot(Long taskId, Long owner, Integer dispatchCount, String conversationId, String status) {
        public static Snapshot capture(TeamTaskEntity task) {
            return new Snapshot(task.getId(), task.getOwnerAgentId(), task.getDispatchCount(),
                    task.getConversationId(), task.getStatus());
        }
        public Snapshot withConversation(String conversation) {
            return new Snapshot(taskId, owner, dispatchCount, conversation, status);
        }
        public boolean matches(TeamTaskEntity task) {
            return task != null && taskId != null && owner != null && dispatchCount != null
                    && conversationId != null && !conversationId.isBlank()
                    && TeamTaskStatus.IN_PROGRESS.equals(status)
                    && Objects.equals(taskId, task.getId()) && Objects.equals(owner, task.getOwnerAgentId())
                    && Objects.equals(dispatchCount, task.getDispatchCount())
                    && Objects.equals(conversationId, task.getConversationId()) && Objects.equals(status, task.getStatus());
        }
    }

    public record Judgment(DecisionTicket ticket, boolean accepted) {}

    public boolean enabled() { return properties.modeFor(DecisionType.WORKER_RESULT) != DecisionMode.OFF; }

    public boolean active() { return properties.modeFor(DecisionType.WORKER_RESULT) == DecisionMode.ACTIVE; }

    public Judgment judge(TeamTaskEntity task, String guard, String reply) {
        boolean valid = "VALID".equals(guard) || "EXPLICIT_COMPLETION".equals(guard) || "CHECKPOINT_ACK".equals(guard);
        DecisionValue value = new DecisionValue.BooleanValue(valid);
        if (properties.modeFor(DecisionType.WORKER_RESULT) == DecisionMode.OFF) {
            return new Judgment(new DecisionTicket(null, DecisionMode.OFF, value), valid);
        }
        boolean guarded = !"VALID".equals(guard);
        String subject = task.getSubject();
        String description = task.getDescription();
        if (!guarded && (subject == null || subject.isBlank() || reply == null)) {
            guard = "EVIDENCE_UNAVAILABLE"; guarded = true;
        }
        int evidenceLength = (subject == null ? 0 : subject.length())
                + (description == null ? 0 : description.length()) + (reply == null ? 0 : reply.length());
        if (!guarded && evidenceLength > 8192) { guard = "EVIDENCE_OVERSIZE"; guarded = true; }
        List<String> evidence = guarded ? List.of()
                : List.of(subject, description == null ? "" : description, reply);
        AgentTeamEntity team;
        try { team = teams.getTeam(task.getTeamId()); }
        catch (RuntimeException failure) {
            if (active()) throw new DecisionRecordingException();
            team = null;
        }
        var facts = new DecisionFacts(Map.of(guard, true, "REQUIRES_APPROVAL", Boolean.TRUE.equals(task.getRequireApproval())),
                Map.of("DISPATCH_COUNT", task.getDispatchCount() == null ? 0d : task.getDispatchCount().doubleValue()), evidence);
        DecisionTicket ticket = decisions.decide(new DecisionRequest(DecisionType.WORKER_RESULT,
                new DecisionScope(team == null ? null : team.getWorkspaceId(), task.getId(), task.getRunId(),
                        identifier(task.getConversationId()), null), "EXPLICIT_COMPLETION".equals(guard) ? guard : "SETTLEMENT",
                new DecisionQuestion.BooleanQuestion("worker-result-v1", "Does this worker result satisfy the assigned task?"),
                facts, value, guarded ? value : null, null));
        return new Judgment(ticket, ((DecisionValue.BooleanValue) ticket.effectiveValue()).value());
    }

    public void outcome(DecisionTicket ticket, boolean applied, String status) {
        if (ticket == null) return;
        decisions.recordOutcome(ticket, applied
                        ? ticket.mode() == DecisionMode.SHADOW ? DecisionOutcome.OBSERVED : DecisionOutcome.APPLIED
                        : DecisionOutcome.NOT_APPLIED,
                new DecisionValue.Choice(status.toUpperCase(Locale.ROOT)));
    }

    public void observeCompletion(TeamTaskEntity task, String status) {
        var judgment = judge(task, "EXPLICIT_COMPLETION", null);
        decisions.recordOutcome(judgment.ticket(), DecisionOutcome.OBSERVED,
                new DecisionValue.Choice(status.toUpperCase(Locale.ROOT)));
    }

    private static String identifier(String value) {
        return value == null || value.isBlank() || value.length() > 128 ? null : value;
    }
}
