package vip.mate.decision;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.record.*;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DecisionRecordStoreTest {
    @Test void allDialectScriptsExecuteInCompatibilityModes() {
        for (String dialect : new String[]{"h2", "mysql", "kingbase"}) database(dialect);
    }
    @Test void proposalSurvivesDomainRollbackAndOutcomeDoesNot() {
        var jdbc = database("h2");
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var store = new JdbcDecisionRecordStore(jdbc, manager, new DecisionProperties());
        var tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> {
            store.insert(record("id1"));
            store.outcome("id1", DecisionOutcome.APPLIED, new DecisionValue.Choice("DEFER"));
            status.setRollbackOnly();
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome", Integer.class));
    }
    @Test void outcomeBeforeProposalIsPreservedAndRetentionIsBounded() {
        var jdbc = database("h2");
        var properties = new DecisionProperties(); properties.setRetentionBatchSize(1);
        var store = new JdbcDecisionRecordStore(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), properties);
        for (String id : new String[]{"id1", "id2"}) {
            store.outcome(id, DecisionOutcome.OBSERVED, new DecisionValue.Choice("CONTINUE"));
            store.insert(record(id));
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome", Integer.class));
        jdbc.update("UPDATE mate_decision_record SET create_time = TIMESTAMP '2000-01-01 00:00:00'");
        jdbc.update("UPDATE mate_decision_outcome SET create_time = TIMESTAMP '2000-01-01 00:00:00'");
        store.purgeExpired();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record", Integer.class));
    }
    @Test void expiredPendingProposalsArePurgedButRecentRacingOutcomesSurvive() {
        var jdbc = database("h2"); var properties = new DecisionProperties(); properties.setRetentionBatchSize(1);
        var store = new JdbcDecisionRecordStore(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), properties);
        store.insert(record("pending"));
        jdbc.update("UPDATE mate_decision_record SET create_time = TIMESTAMP '2000-01-01 00:00:00'");
        store.outcome("racing", DecisionOutcome.OBSERVED, new DecisionValue.Choice("CONTINUE"));
        store.purgeExpired();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome", Integer.class));
    }
    @Test void outcomeIsIdempotentButConflictingHistoryCannotBeRewritten() {
        var jdbc = database("h2");
        var store = new JdbcDecisionRecordStore(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), new DecisionProperties());
        store.outcome("id", DecisionOutcome.APPLIED, new DecisionValue.Choice("DEFER"));
        store.outcome("id", DecisionOutcome.APPLIED, new DecisionValue.Choice("DEFER"));
        assertThrows(IllegalStateException.class, () -> store.outcome("id", DecisionOutcome.NOT_APPLIED, new DecisionValue.Choice("CONTINUE")));
        assertEquals("APPLIED", jdbc.queryForObject("SELECT outcome FROM mate_decision_outcome", String.class));
    }
    @Test void nonnumericConversationAndAttemptIdsRoundTripInAllDialects() {
        for (String dialect : new String[]{"h2", "mysql", "kingbase"}) {
            var jdbc = database(dialect);
            var store = new JdbcDecisionRecordStore(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), new DecisionProperties());
            var original = record("id");
            var scope = new DecisionScope(1L, 2L, 3L, "conversation-abc", "ab051cc7-4cdb-41c1-a72c-1638107d3f24");
            store.insert(new DecisionRecord(original.id(), original.type(), scope, original.phase(), original.questionVersion(), original.mode(),
                    original.provider(), original.providerVersion(), original.valueKind(), original.baseline(), original.proposed(), original.effective(),
                    original.confidence(), original.reason(), original.overrideReason(), original.elapsedMs()));
            assertEquals(scope.conversationId(), jdbc.queryForObject("SELECT conversation_id FROM mate_decision_record", String.class));
            assertEquals(scope.attemptId(), jdbc.queryForObject("SELECT attempt_id FROM mate_decision_record", String.class));
            assertEquals("v1", jdbc.queryForObject("SELECT policy_version FROM mate_decision_record", String.class));
        }
    }
    @Test void concurrentIdenticalOutcomesCommitBothDomainTransactions() throws Exception {
        var database = database("h2");
        database.execute("CREATE TABLE domain_change (id INT PRIMARY KEY)");
        var bothInserts = new CountDownLatch(2);
        var jdbc = new JdbcTemplate(database.getDataSource()) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("INSERT INTO mate_decision_outcome")) {
                    bothInserts.countDown();
                    try { assertTrue(bothInserts.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                }
                return super.update(sql, args);
            }
        };
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var store = new JdbcDecisionRecordStore(jdbc, manager, new DecisionProperties());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.update("INSERT INTO domain_change VALUES (1)");
                store.outcome("racing", DecisionOutcome.APPLIED, new DecisionValue.Choice("DEFER"));
            }));
            var second = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.update("INSERT INTO domain_change VALUES (2)");
                store.outcome("racing", DecisionOutcome.APPLIED, new DecisionValue.Choice("DEFER"));
            }));
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM domain_change", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome", Integer.class));
    }
    @Test void retentionRechecksFreshOutcomeForSameExpiredPendingProposal() throws Exception {
        var database = database("h2"); var selected = new CountDownLatch(1); var inserted = new CountDownLatch(1);
        var jdbc = new JdbcTemplate(database.getDataSource()) {
            @Override public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
                var result = super.queryForList(sql, elementType, args);
                if (sql.contains("SELECT r.id FROM mate_decision_record")) {
                    selected.countDown();
                    try { assertTrue(inserted.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                }
                return result;
            }
        };
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var store = new JdbcDecisionRecordStore(jdbc, manager, new DecisionProperties());
        store.insert(record("pending"));
        jdbc.update("UPDATE mate_decision_record SET create_time = TIMESTAMP '2000-01-01 00:00:00'");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var purge = executor.submit(store::purgeExpired);
            assertTrue(selected.await(5, TimeUnit.SECONDS));
            store.outcome("pending", DecisionOutcome.APPLIED, new DecisionValue.Choice("CONTINUE"));
            inserted.countDown(); purge.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome", Integer.class));
    }
    static DecisionRecord record(String id) {
        return new DecisionRecord(id, DecisionType.GOAL_CONTINUATION, new DecisionScope(1L, 2L, 3L), "FOLLOWUP", "v1",
                DecisionMode.ACTIVE, "rule", "rule-v1", "CHOICE", "CONTINUE", null, "CONTINUE", null, "BASELINE", "NONE", 1);
    }
    static JdbcTemplate database(String dialect) {
        String mode = dialect.equals("kingbase") ? "PostgreSQL" : "MySQL";
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=" + mode, "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + dialect + "/V203__decision_record.sql")).execute(ds);
        return new JdbcTemplate(ds);
    }
}
