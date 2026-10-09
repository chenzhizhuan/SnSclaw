package vip.mate.team.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.core.DecisionService;
import vip.mate.decision.provider.*;
import vip.mate.decision.record.DecisionRecordStore;
import vip.mate.team.model.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerDecisionAdapterTest {
    static TeamTaskEntity task() {
        var task = new TeamTaskEntity(); task.setId(1L); task.setTeamId(2L);
        task.setOwnerAgentId(3L); task.setDispatchCount(1); task.setConversationId("worker-conversation");
        task.setSubject("Analyze results"); task.setStatus(TeamTaskStatus.IN_PROGRESS); return task;
    }
    @Test void activeRejectionIsBooleanAndDeterministicGuardsBypassProvider() {
        var properties = new DecisionProperties(); properties.setMode(DecisionMode.ACTIVE); properties.setProvider("judge");
        var calls = new AtomicInteger();
        DecisionProvider provider = new DecisionProvider() {
            public String id() { return "judge"; }
            public DecisionCapability capability() { return DecisionCapability.standard(); }
            public DecisionResult decide(DecisionRequest request) {
                calls.incrementAndGet();
                assertInstanceOf(DecisionQuestion.BooleanQuestion.class, request.question());
                return DecisionResult.proposed(new DecisionValue.BooleanValue(false), 1, "test-v1");
            }
        };
        try (var core = new DecisionService(properties, List.of(provider), mock(DecisionRecordStore.class), new SimpleMeterRegistry())) {
            var adapter = new WorkerDecisionAdapter(core, properties, mock(TeamService.class));
            var task = task();
            assertFalse(adapter.judge(task, "VALID", "completed evidence").accepted());
            for (String guard : List.of("BLANK", "PLACEHOLDER", "ARTIFACT_MISSING", "CLARIFICATION", "CHECKPOINT_WAIT", "STALE_ATTEMPT")) {
                assertFalse(adapter.judge(task, guard, "sensitive evidence").accepted());
            }
            assertEquals(1, calls.get());
        }
    }
    @Test void incompleteOrOversizeEvidenceKeepsBaselineWithoutProviderCall() {
        var properties = new DecisionProperties(); properties.setMode(DecisionMode.ACTIVE); properties.setProvider("judge");
        var provider = mock(DecisionProvider.class); when(provider.id()).thenReturn("judge");
        try (var core = new DecisionService(properties, List.of(provider), mock(DecisionRecordStore.class), new SimpleMeterRegistry())) {
            var teams = mock(TeamService.class);
            var adapter = new WorkerDecisionAdapter(core, properties, teams);
            assertTrue(adapter.judge(task(), "VALID", "x".repeat(9000)).accepted());
            var incomplete = task(); incomplete.setSubject(null);
            assertTrue(adapter.judge(incomplete, "VALID", "done").accepted());
            verify(provider, never()).decide(any());
            clearInvocations(teams);
            properties.setMode(DecisionMode.OFF);
            assertTrue(adapter.judge(task(), "VALID", "done").accepted());
            verifyNoInteractions(teams);
        }
    }

    @Test void attemptSnapshotRejectsMissingOrChangedIdentity() {
        var task = task(); var snapshot = WorkerDecisionAdapter.Snapshot.capture(task);
        assertTrue(snapshot.matches(task));
        task.setConversationId("replacement"); assertFalse(snapshot.matches(task));
        task = task(); task.setOwnerAgentId(99L); assertFalse(snapshot.matches(task));
        task = task(); task.setDispatchCount(2); assertFalse(snapshot.matches(task));
        task = task(); task.setStatus(TeamTaskStatus.COMPLETED); assertFalse(snapshot.matches(task));
        task = task(); task.setConversationId(null); assertFalse(WorkerDecisionAdapter.Snapshot.capture(task).matches(task));
    }
}
