package vip.mate.decision;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.core.DecisionService;
import vip.mate.decision.provider.*;
import vip.mate.decision.record.*;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionPipelineTest {
    static Stream<DecisionResult> invalidResults() {
        return Stream.of(
                new DecisionResult(DecisionResult.Status.PROPOSED, new DecisionValue.Choice("DEFER"), null, "v1"),
                DecisionResult.proposed(new DecisionValue.Choice("DEFER"), Double.NaN, "v1"),
                DecisionResult.proposed(new DecisionValue.Choice("DEFER"), Double.POSITIVE_INFINITY, "v1"),
                DecisionResult.proposed(new DecisionValue.Choice("DEFER"), 1.1, "v1"),
                DecisionResult.proposed(new DecisionValue.Choice("DEFER"), -0.1, "v1"),
                DecisionResult.proposed(new DecisionValue.Choice("DEFER"), .79, "v1"),
                DecisionResult.proposed(new DecisionValue.Choice("INVALID"), 1, "v1"),
                DecisionResult.proposed(new DecisionValue.BooleanValue(true), 1, "v1"),
                new DecisionResult(DecisionResult.Status.ABSTAIN, null, null, "v1"),
                DecisionResult.unavailable(),
                DecisionResult.proposed(new DecisionValue.Choice("DEFER"), 1, "private provider free text"));
    }
    @ParameterizedTest @MethodSource("invalidResults") void invalidOrUnavailableResultsPreserveBaseline(DecisionResult result) {
        var p = active();
        var provider = provider(r -> result);
        try (var service = new DecisionService(p, List.of(provider), mock(DecisionRecordStore.class), new SimpleMeterRegistry())) {
            assertEquals(DecisionServiceTest.request(null).baseline(), service.decide(DecisionServiceTest.request(null)).effectiveValue());
        }
    }
    @Test void ruleIsExplicitBaselineAndLlmIsUnavailable() {
        var request = DecisionServiceTest.request(null);
        var rule = new RuleDecisionProvider().decide(request);
        assertEquals(DecisionResult.Status.BASELINE, rule.status()); assertNull(rule.confidence());
        assertEquals(DecisionResult.Status.UNAVAILABLE, new LlmDecisionProvider().decide(request).status());
    }
    @Test void unsupportedCapabilitySkipsProvider() {
        var provider = mock(DecisionProvider.class); when(provider.id()).thenReturn("test");
        when(provider.capability()).thenReturn(new DecisionCapability(Set.of(DecisionCapability.Kind.BOOLEAN), Set.of("und"), 1, 0));
        try (var service = new DecisionService(active(), List.of(provider), mock(DecisionRecordStore.class), new SimpleMeterRegistry())) {
            assertEquals(DecisionServiceTest.request(null).baseline(), service.decide(DecisionServiceTest.request(null)).effectiveValue());
            verify(provider, never()).decide(any());
        }
    }
    @Test void timeoutAndSaturationRemainBoundedWhenProviderIgnoresInterrupt() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1); var calls = new AtomicInteger();
        var p = active(); p.setTimeoutMs(30); p.setProviderThreads(1); p.setQueueCapacity(1);
        var provider = provider(r -> {
            calls.incrementAndGet(); entered.countDown();
            while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) {} }
            return DecisionResult.unavailable();
        });
        var store = mock(DecisionRecordStore.class);
        try (var service = new DecisionService(p, List.of(provider), store, new SimpleMeterRegistry())) {
            for (int i = 0; i < 6; i++) assertEquals(DecisionServiceTest.request(null).baseline(), service.decide(DecisionServiceTest.request(null)).effectiveValue());
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertEquals(1, calls.get());
            var records = ArgumentCaptor.forClass(DecisionRecord.class); verify(store, times(6)).insert(records.capture());
            assertTrue(records.getAllValues().stream().anyMatch(r -> r.reason().equals("SATURATED")));
        } finally { release.countDown(); }
    }
    @Test void shadowReturnsImmediatelyAndFailuresCannotEscape() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var p = active(); p.setMode(DecisionMode.SHADOW); p.setTimeoutMs(1000);
        var provider = provider(r -> { entered.countDown(); try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } return DecisionResult.unavailable(); });
        var store = mock(DecisionRecordStore.class); doThrow(new IllegalStateException("private")).when(store).insert(any());
        try (var service = new DecisionService(p, List.of(provider), store, new SimpleMeterRegistry())) {
            var ticket = service.decide(DecisionServiceTest.request(null));
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertEquals(DecisionMode.SHADOW, ticket.mode());
            assertEquals(DecisionServiceTest.request(null).baseline(), ticket.effectiveValue());
        } finally { release.countDown(); }
    }
    @Test void auditContainsNoEvidenceDescriptionsOrExceptionMessages() {
        var store = mock(DecisionRecordStore.class);
        try (var service = new DecisionService(active(), List.of(provider(r -> { throw new IllegalStateException("secret-provider-failure"); })), store, new SimpleMeterRegistry())) {
            service.decide(DecisionServiceTest.request(null));
            var record = ArgumentCaptor.forClass(DecisionRecord.class); verify(store).insert(record.capture());
            String serialized = record.getValue().toString();
            assertFalse(serialized.contains("private evidence")); assertFalse(serialized.contains("Private description"));
            assertFalse(serialized.contains("secret-provider-failure")); assertTrue(serialized.contains("PROVIDER_ERROR"));
        }
    }
    @Test void activeOutcomeParticipatesInDomainRollback() {
        var jdbc = DecisionRecordStoreTest.database("h2"); var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var store = new JdbcDecisionRecordStore(jdbc, manager, active());
        try (var service = new DecisionService(active(), List.of(provider(r -> DecisionResult.proposed(new DecisionValue.Choice("DEFER"), 1, "v1"))), store, new SimpleMeterRegistry())) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                var ticket = service.decide(DecisionServiceTest.request(null));
                service.recordOutcome(ticket, DecisionOutcome.APPLIED, ticket.effectiveValue()); status.setRollbackOnly();
            });
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_record", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_decision_outcome", Integer.class));
        }
    }
    @Test void shadowOutcomeIsNeverEnqueuedForRolledBackTransaction() {
        var jdbc = DecisionRecordStoreTest.database("h2"); var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var store = mock(DecisionRecordStore.class); var p = active(); p.setMode(DecisionMode.SHADOW);
        try (var service = new DecisionService(p, List.of(), store, new SimpleMeterRegistry())) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                service.recordOutcome(new DecisionTicket("id", DecisionMode.SHADOW, new DecisionValue.Choice("CONTINUE")), DecisionOutcome.APPLIED, new DecisionValue.Choice("CONTINUE"));
                status.setRollbackOnly();
            });
            verifyNoInteractions(store);
        }
    }
    @Test void shadowRecordsEvaluatedValueSeparatelyFromActualBaseline() {
        var p = active(); p.setMode(DecisionMode.SHADOW); var store = mock(DecisionRecordStore.class);
        try (var service = new DecisionService(p, List.of(provider(r -> DecisionResult.proposed(new DecisionValue.Choice("DEFER"), 1, "v1"))), store, new SimpleMeterRegistry())) {
            var ticket = service.decide(DecisionServiceTest.request(null));
            assertEquals(new DecisionValue.Choice("CONTINUE"), ticket.effectiveValue());
            var records = ArgumentCaptor.forClass(DecisionRecord.class); verify(store, timeout(1500)).insert(records.capture());
            assertEquals("DEFER", records.getValue().effective());
        }
    }
    @Test void policyOverrideRetainsFallbackReason() {
        var original = DecisionServiceTest.request(null);
        var request = new DecisionRequest(original.type(), original.scope(), original.phase(), original.question(), original.facts(),
                original.baseline(), null, new DecisionValue.Choice("DEFER"));
        var store = mock(DecisionRecordStore.class);
        try (var service = new DecisionService(active(), List.of(provider(r -> DecisionResult.unavailable())), store, new SimpleMeterRegistry())) {
            assertEquals(new DecisionValue.Choice("DEFER"), service.decide(request).effectiveValue());
            var records = ArgumentCaptor.forClass(DecisionRecord.class); verify(store).insert(records.capture());
            assertEquals("UNAVAILABLE", records.getValue().reason());
            assertEquals("POLICY_OVERRIDE", records.getValue().overrideReason());
        }
    }
    @Test void booleanAndScoreQuestionsFlowThroughTypedValidation() {
        var store = mock(DecisionRecordStore.class);
        for (DecisionQuestion question : List.of(new DecisionQuestion.BooleanQuestion("v1", "Allow?"), new DecisionQuestion.Score("v1", "Score?", 0, 10))) {
            DecisionValue baseline = question instanceof DecisionQuestion.Score ? new DecisionValue.Score(2) : new DecisionValue.BooleanValue(false);
            DecisionValue proposed = question instanceof DecisionQuestion.Score ? new DecisionValue.Score(8) : new DecisionValue.BooleanValue(true);
            var request = new DecisionRequest(DecisionType.WORKER_RESULT, new DecisionScope(1L, 2L, 3L), "JUDGE", question,
                    new DecisionFacts(Map.of(), Map.of()), baseline, null, null);
            try (var service = new DecisionService(active(), List.of(provider(r -> DecisionResult.proposed(proposed, .8, "v1"))), store, new SimpleMeterRegistry())) {
                assertEquals(proposed, service.decide(request).effectiveValue());
            }
        }
    }
    @Test void scenarioOffBypassesGlobalActiveAndInvalidConfigurationFailsEarly() {
        var p = active(); p.getScenarios().put(DecisionType.GOAL_CONTINUATION, DecisionMode.OFF);
        var store = mock(DecisionRecordStore.class);
        try (var service = new DecisionService(p, List.of(), store, new SimpleMeterRegistry())) {
            assertEquals(DecisionMode.OFF, service.decide(DecisionServiceTest.request(null)).mode()); verifyNoInteractions(store);
        }
        p.setConfidenceThreshold(Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> new DecisionService(p, List.of(), store, new SimpleMeterRegistry()));
    }
    @Test void shadowQueueDropsAreObservableAndNeverDelayCaller() throws Exception {
        var p = active(); p.setMode(DecisionMode.SHADOW); p.setShadowThreads(1); p.setQueueCapacity(1); p.setTimeoutMs(1000);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var metrics = new SimpleMeterRegistry();
        var provider = provider(r -> { entered.countDown(); try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } return DecisionResult.unavailable(); });
        try (var service = new DecisionService(p, List.of(provider), mock(DecisionRecordStore.class), metrics)) {
            service.decide(DecisionServiceTest.request(null)); assertTrue(entered.await(1, TimeUnit.SECONDS));
            service.decide(DecisionServiceTest.request(null)); service.decide(DecisionServiceTest.request(null));
            assertEquals(1, metrics.get("mate.decision.failure").tag("stage", "dropped").counter().count());
        } finally { release.countDown(); }
    }
    @Test void shadowOutcomeOnlyWritesAfterSuccessfulCommit() {
        var jdbc = DecisionRecordStoreTest.database("h2"); var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var store = mock(DecisionRecordStore.class); var p = active(); p.setMode(DecisionMode.SHADOW);
        var value = new DecisionValue.Choice("CONTINUE");
        try (var service = new DecisionService(p, List.of(), store, new SimpleMeterRegistry())) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                service.recordOutcome(new DecisionTicket("id", DecisionMode.SHADOW, value), DecisionOutcome.OBSERVED, value);
                verifyNoInteractions(store);
            });
            verify(store, timeout(1000)).outcome("id", DecisionOutcome.OBSERVED, value);
        }
    }
    @Test void comparisonMetricsExcludeGuardsAndPreserveLowConfidenceDivergence() {
        var metrics = new SimpleMeterRegistry();
        try (var service = new DecisionService(active(), List.of(provider(r -> DecisionResult.proposed(new DecisionValue.Choice("DEFER"), .5, "v1"))), mock(DecisionRecordStore.class), metrics)) {
            service.decide(DecisionServiceTest.request(new DecisionValue.Choice("CONTINUE")));
            service.decide(DecisionServiceTest.request(null));
            assertEquals(2, metrics.get("mate.decision.requests").counter().count());
            assertEquals(1, metrics.get("mate.decision.comparison").tag("stage", "proposed").tag("agreement", "false").counter().count());
            assertEquals(1, metrics.get("mate.decision.comparison").tag("stage", "effective").tag("agreement", "true").counter().count());
        }
    }
    static DecisionProperties active() { var p = new DecisionProperties(); p.setMode(DecisionMode.ACTIVE); p.setProvider("test"); return p; }
    static DecisionProvider provider(Function<DecisionRequest, DecisionResult> action) {
        return new DecisionProvider() {
            public String id() { return "test"; }
            public DecisionCapability capability() { return DecisionCapability.standard(); }
            public DecisionResult decide(DecisionRequest request) { return action.apply(request); }
        };
    }
}
