package vip.mate.decision;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.core.DecisionService;
import vip.mate.decision.provider.*;
import vip.mate.decision.record.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionScenarioPolicyTest {
    @Test void bindsPartialOverridesAndKeepsLegacyModeSyntax() {
        new ApplicationContextRunner().withUserConfiguration(DecisionWiringTest.Config.class)
                .withPropertyValues("mate.decision.provider=rule", "mate.decision.timeout-ms=800",
                        "mate.decision.scenarios.AGENT_ROUTING=OFF",
                        "mate.decision.scenario-policies.WORKER_RESULT.provider=llm",
                        "mate.decision.scenario-policies.WORKER_RESULT.confidence-threshold=0.95",
                        "mate.decision.scenario-policies.AGENT_ROUTING.timeout-ms=100")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var p = context.getBean(DecisionProperties.class);
                    assertEquals(DecisionMode.OFF, p.modeFor(DecisionType.AGENT_ROUTING));
                    assertEquals("llm", p.policyFor(DecisionType.WORKER_RESULT).provider());
                    assertEquals(.95, p.policyFor(DecisionType.WORKER_RESULT).confidenceThreshold());
                    assertEquals(800, p.policyFor(DecisionType.WORKER_RESULT).timeoutMs());
                    assertEquals("rule", p.policyFor(DecisionType.AGENT_ROUTING).provider());
                    assertEquals(100, p.policyFor(DecisionType.AGENT_ROUTING).timeoutMs());
                    assertEquals(.8, p.policyFor(DecisionType.GOAL_CONTINUATION).confidenceThreshold());
                });
    }
    @Test void invalidOverridesFailStartup() {
        for (String value : List.of("provider=invalid name", "confidence-threshold=NaN", "confidence-threshold=1.1", "timeout-ms=0", "timeout-ms=60001")) {
            new ApplicationContextRunner().withUserConfiguration(DecisionWiringTest.Config.class)
                    .withPropertyValues("mate.decision.scenario-policies.WORKER_RESULT." + value)
                    .run(context -> assertNotNull(context.getStartupFailure(), value));
        }
    }
    static DecisionRequest request(DecisionType type) {
        var r = DecisionServiceTest.request(null);
        return new DecisionRequest(type, r.scope(), r.phase(), r.question(), r.facts(), r.baseline(), null, null);
    }
    static DecisionProvider provider(String id) {
        var provider = mock(DecisionProvider.class);
        when(provider.id()).thenReturn(id);
        when(provider.capability()).thenReturn(DecisionCapability.standard());
        when(provider.decide(any())).thenReturn(DecisionResult.proposed(new DecisionValue.Choice("DEFER"), .9, "v1"));
        return provider;
    }
    @Test void providersAndThresholdsAreIsolatedAndAudited() {
        var p = new DecisionProperties(); p.setMode(DecisionMode.ACTIVE); p.setProvider("default");
        var override = new DecisionProperties.ScenarioPolicy(); override.setProvider("worker"); override.setConfidenceThreshold(.95);
        p.getScenarioPolicies().put(DecisionType.WORKER_RESULT, override);
        var defaultProvider = provider("default"); var worker = provider("worker");
        var store = mock(DecisionRecordStore.class);
        try (var service = new DecisionService(p, List.of(defaultProvider, worker), store, new SimpleMeterRegistry())) {
            assertEquals(new DecisionValue.Choice("CONTINUE"), service.decide(request(DecisionType.WORKER_RESULT)).effectiveValue());
            assertEquals(new DecisionValue.Choice("DEFER"), service.decide(request(DecisionType.GOAL_CONTINUATION)).effectiveValue());
            verify(worker).decide(argThat(r -> r.type() == DecisionType.WORKER_RESULT));
            verify(defaultProvider).decide(argThat(r -> r.type() == DecisionType.GOAL_CONTINUATION));
            var records = ArgumentCaptor.forClass(DecisionRecord.class); verify(store, times(2)).insert(records.capture());
            assertEquals("worker", records.getAllValues().get(0).provider());
            assertEquals("LOW_CONFIDENCE", records.getAllValues().get(0).reason());
            assertEquals("default", records.getAllValues().get(1).provider());
        }
    }
    @Test void scenarioTimeoutFallsBackAndUnknownProviderDoesNotUseGlobal() throws Exception {
        var p = new DecisionProperties(); p.setMode(DecisionMode.ACTIVE); p.setProvider("default"); p.setTimeoutMs(5000);
        var override = new DecisionProperties.ScenarioPolicy(); override.setTimeoutMs(30L);
        p.getScenarioPolicies().put(DecisionType.WORKER_RESULT, override);
        var provider = provider("default"); var release = new CountDownLatch(1);
        when(provider.decide(any())).thenAnswer(call -> { release.await(); return DecisionResult.unavailable(); });
        var store = mock(DecisionRecordStore.class);
        try (var service = new DecisionService(p, List.of(provider), store, new SimpleMeterRegistry())) {
            assertEquals(request(DecisionType.WORKER_RESULT).baseline(), service.decide(request(DecisionType.WORKER_RESULT)).effectiveValue());
            override.setProvider("missing");
            service.decide(request(DecisionType.WORKER_RESULT));
            var records = ArgumentCaptor.forClass(DecisionRecord.class); verify(store, times(2)).insert(records.capture());
            assertEquals("TIMEOUT", records.getAllValues().get(0).reason());
            assertEquals("UNAVAILABLE", records.getAllValues().get(1).reason());
            verify(provider, atMostOnce()).decide(any());
        } finally { release.countDown(); }
    }
    @Test void queuedShadowDecisionKeepsSubmissionPolicyAndNeverAppliesSuggestion() throws Exception {
        var p = new DecisionProperties(); p.setShadowThreads(1); p.setProvider("default");
        var override = new DecisionProperties.ScenarioPolicy(); override.setProvider("worker");
        p.getScenarioPolicies().put(DecisionType.WORKER_RESULT, override);
        var defaultProvider = provider("default"); var worker = provider("worker");
        var store = mock(DecisionRecordStore.class);
        var firstRecorded = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { firstRecorded.countDown(); release.await(); return null; }).when(store).insert(any());
        try (var service = new DecisionService(p, List.of(defaultProvider, worker), store, new SimpleMeterRegistry())) {
            service.decide(request(DecisionType.GOAL_CONTINUATION));
            assertTrue(firstRecorded.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var ticket = service.decide(request(DecisionType.WORKER_RESULT));
            assertEquals(request(DecisionType.WORKER_RESULT).baseline(), ticket.effectiveValue());
            override.setProvider("missing"); override.setConfidenceThreshold(.99);
            release.countDown();
            var records = ArgumentCaptor.forClass(DecisionRecord.class);
            verify(store, timeout(2000).times(2)).insert(records.capture());
            var record = records.getAllValues().stream().filter(r -> r.type() == DecisionType.WORKER_RESULT).findFirst().orElseThrow();
            assertEquals("worker", record.provider()); assertEquals("PROPOSED", record.reason());
            verify(worker).decide(any());
        } finally { release.countDown(); }
    }

}
