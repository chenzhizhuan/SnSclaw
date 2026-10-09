package vip.mate.decision.record;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Repository
public class JdbcDecisionRecordStore implements DecisionRecordStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final TransactionTemplate joined;
    private final DecisionProperties properties;
    public JdbcDecisionRecordStore(JdbcTemplate jdbc, PlatformTransactionManager manager, DecisionProperties properties) {
        this.jdbc = jdbc; this.properties = properties;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        joined = new TransactionTemplate(manager);
    }
    @Override public void insert(DecisionRecord r) {
        independent.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO mate_decision_record
                (id, decision_type, workspace_id, subject_id, run_id, conversation_id, attempt_id, phase,
                 question_version, policy_version, mode, provider, provider_version, value_kind,
                 baseline_value, proposed_value, effective_value, confidence, reason, override_reason, elapsed_ms, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, r.id(), r.type().name(), r.scope().workspaceId(), r.scope().subjectId(), r.scope().runId(),
                r.scope().conversationId(), r.scope().attemptId(), r.phase(), r.questionVersion(), r.policyVersion(), r.mode().name(),
                r.provider(), r.providerVersion(), r.valueKind(), r.baseline(), r.proposed(), r.effective(),
                r.confidence(), r.reason(), r.overrideReason(), r.elapsedMs(), Timestamp.from(Instant.now())));
    }
    @Override public void outcome(String id, DecisionOutcome outcome, DecisionValue value) {
        joined.executeWithoutResult(status -> {
            // Serialize with retention when the proposal already exists. Missing proposals
            // are expected for asynchronous outcomes, so duplicate inserts still use a savepoint.
            jdbc.queryForList("SELECT id FROM mate_decision_record WHERE id = ? FOR UPDATE", String.class, id);
            Object savepoint = status.createSavepoint();
            try {
                jdbc.update("INSERT INTO mate_decision_outcome (decision_id, outcome, actual_value, create_time) VALUES (?, ?, ?, ?)",
                        id, outcome.name(), value.encoded(), Timestamp.from(Instant.now()));
            } catch (DuplicateKeyException ex) {
                status.rollbackToSavepoint(savepoint);
                // A locking read observes the committed winner even under repeatable-read isolation.
                var existing = jdbc.query("SELECT outcome, actual_value FROM mate_decision_outcome WHERE decision_id = ? FOR UPDATE",
                        (rs, row) -> new StoredOutcome(rs.getString(1), rs.getString(2)), id);
                if (existing.isEmpty() || !existing.getFirst().equals(new StoredOutcome(outcome.name(), value.encoded())))
                    throw new IllegalStateException("Decision outcome is immutable");
            } finally {
                status.releaseSavepoint(savepoint);
            }
        });
    }
    @Scheduled(fixedDelayString = "${mate.decision.retention-interval-ms:3600000}")
    public void purgeExpired() {
        if (properties.getMode() == DecisionMode.OFF && properties.getScenarios().values().stream().noneMatch(m -> m != DecisionMode.OFF)) return;
        var cutoff = Timestamp.from(Instant.now().minus(properties.getRetentionDays(), ChronoUnit.DAYS));
        independent.executeWithoutResult(status -> {
            var ids = jdbc.queryForList("""
                    SELECT r.id FROM mate_decision_record r
                    LEFT JOIN mate_decision_outcome o ON o.decision_id = r.id
                    WHERE r.create_time < ? AND (o.decision_id IS NULL OR o.create_time < ?)
                    ORDER BY r.create_time LIMIT ?
                    """, String.class, cutoff, cutoff, properties.getRetentionBatchSize());
            for (String id : ids) {
                var proposalTimes = jdbc.queryForList("SELECT create_time FROM mate_decision_record WHERE id = ? FOR UPDATE", Timestamp.class, id);
                if (proposalTimes.isEmpty() || !proposalTimes.getFirst().before(cutoff)) continue;
                var outcomeTimes = jdbc.queryForList("SELECT create_time FROM mate_decision_outcome WHERE decision_id = ? FOR UPDATE", Timestamp.class, id);
                if (!outcomeTimes.isEmpty() && !outcomeTimes.getFirst().before(cutoff)) continue;
                jdbc.update("DELETE FROM mate_decision_outcome WHERE decision_id = ? AND create_time < ?", id, cutoff);
                jdbc.update("DELETE FROM mate_decision_record WHERE id = ?", id);
            }
            int remaining = properties.getRetentionBatchSize() - ids.size();
            if (remaining > 0) {
                var orphans = jdbc.queryForList("""
                        SELECT o.decision_id FROM mate_decision_outcome o
                        WHERE o.create_time < ? AND NOT EXISTS (SELECT 1 FROM mate_decision_record r WHERE r.id = o.decision_id)
                        ORDER BY o.create_time LIMIT ?
                        """, String.class, cutoff, remaining);
                for (String id : orphans) jdbc.update("DELETE FROM mate_decision_outcome WHERE decision_id = ? AND create_time < ?", id, cutoff);
            }
        });
    }
    private record StoredOutcome(String outcome, String value) {}
}
