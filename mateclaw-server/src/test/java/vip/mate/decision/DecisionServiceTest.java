package vip.mate.decision;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.core.DecisionService;
import vip.mate.decision.provider.*;
import vip.mate.decision.record.DecisionRecordStore;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionServiceTest {
    static DecisionRequest request(DecisionValue guard) {
        return new DecisionRequest(DecisionType.GOAL_CONTINUATION, new DecisionScope(1L, 2L, 3L), "FOLLOWUP",
                new DecisionQuestion.Choice("v1", "Private description", List.of(new DecisionQuestion.Option("CONTINUE", "go"), new DecisionQuestion.Option("DEFER", "wait"))),
                new DecisionFacts(Map.of(), Map.of(), List.of("private evidence")), new DecisionValue.Choice("CONTINUE"), guard, null);
    }
    @Test void offHasNoProviderOrStorageDependencies() {
        var p = new DecisionProperties(); p.setMode(DecisionMode.OFF);
        var provider = mock(DecisionProvider.class); when(provider.id()).thenReturn("test");
        var store = mock(DecisionRecordStore.class);
        try (var service = new DecisionService(p, List.of(provider), store, new SimpleMeterRegistry())) {
            clearInvocations(provider);
            var ticket = service.decide(request(null));
            assertNull(ticket.id()); assertEquals(request(null).baseline(), ticket.effectiveValue());
            service.recordOutcome(ticket, DecisionOutcome.APPLIED, ticket.effectiveValue());
            verifyNoInteractions(provider, store);
        }
    }
    @Test void guardShortCircuitsAndActiveAuditFailureIsDistinct() {
        var p = new DecisionProperties(); p.setMode(DecisionMode.ACTIVE);
        var provider = mock(DecisionProvider.class); when(provider.id()).thenReturn("rule");
        var store = mock(DecisionRecordStore.class);
        try (var service = new DecisionService(p, List.of(provider), store, new SimpleMeterRegistry())) {
            clearInvocations(provider);
            assertEquals(new DecisionValue.Choice("DEFER"), service.decide(request(new DecisionValue.Choice("DEFER"))).effectiveValue());
            verifyNoInteractions(provider);
            doThrow(new IllegalStateException("private database exception")).when(store).insert(any());
            assertThrows(DecisionRecordingException.class, () -> service.decide(request(new DecisionValue.Choice("DEFER"))));
        }
    }
    @Test void activeAcceptsConfidentTypedSuggestion() {
        var p = new DecisionProperties(); p.setMode(DecisionMode.ACTIVE); p.setProvider("test");
        var provider = mock(DecisionProvider.class); when(provider.id()).thenReturn("test");
        when(provider.capability()).thenReturn(DecisionCapability.standard());
        when(provider.decide(any())).thenReturn(DecisionResult.proposed(new DecisionValue.Choice("DEFER"), .95, "v1"));
        try (var service = new DecisionService(p, List.of(provider), mock(DecisionRecordStore.class), new SimpleMeterRegistry())) {
            assertEquals(new DecisionValue.Choice("DEFER"), service.decide(request(null)).effectiveValue());
        }
    }
}
