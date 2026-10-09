package vip.mate.planning.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.mockito.ArgumentCaptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.core.DecisionService;
import vip.mate.decision.provider.*;
import vip.mate.decision.record.DecisionRecordStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRoutingDecisionAdapterTest {
    @BeforeAll static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"routing-test"),AgentEntity.class);
    }
    AgentMapper agents = mock(AgentMapper.class);
    DecisionProperties properties = new DecisionProperties();
    AtomicInteger calls = new AtomicInteger();
    DecisionRecordStore records = mock(DecisionRecordStore.class);
    DecisionService core;
    AgentRoutingDecisionAdapter adapter;
    List<DecisionRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    String proposed = "AGENT:3";
    @BeforeEach void setup() {
        properties.setMode(DecisionMode.ACTIVE);
        core = new DecisionService(properties, List.of(new RuleDecisionProvider() {
            @Override public DecisionResult decide(DecisionRequest request) {
                calls.incrementAndGet();
                requests.add(request);
                return DecisionResult.proposed(new DecisionValue.Choice(proposed), 1, "test");
            }
        }), records, new SimpleMeterRegistry());
        adapter = new AgentRoutingDecisionAdapter(core, properties, agents);
        when(agents.selectList(any())).thenReturn(List.of(agent(2L, "Research"), agent(3L, "Writing")));
    }
    @AfterEach void close() { core.close(); }
    static AgentEntity agent(long id, String name) {
        var agent = new AgentEntity(); agent.setId(id); agent.setName(name);
        agent.setWorkspaceId(10L); agent.setEnabled(true); return agent;
    }
    @Test void selectsPeerAndPreservesStepCardinality() {
        var selected = adapter.select(10L, "1", "conversation", "summarize", List.of("read", "write"), Arrays.asList(2L, null));
        assertEquals(List.of(3L, 3L), adapter.assignments(selected));
        assertEquals(2, selected.steps().size());
    }
    @Test void explicitNameGuardsAllBaselineStepsEvenWhenPlannerMissesOrMismatches() {
        var selected = adapter.select(10L, "1", "conversation", "Ask Research to handle it", List.of("read", "write"), Arrays.asList(3L, null));
        assertEquals(Arrays.asList(3L, null), adapter.assignments(selected));
        assertEquals(0, calls.get());
    }
    @Test void ineligibleCandidatesCannotBeAdoptedAndStaleBaselineFallsBackLocal() {
        var disabled = agent(3, "Writing"); disabled.setEnabled(false);
        var other = agent(4, "Other"); other.setWorkspaceId(11L);
        when(agents.selectList(any())).thenReturn(List.of(agent(1,"Parent"), disabled, other, agent(2,"Research")));
        var selection = adapter.select(10L,"1","conversation","summarize", List.of("read"),List.of(2L));
        assertEquals(List.of(2L), adapter.assignments(selection));
        when(agents.selectList(any())).thenReturn(List.of());
        assertNull(adapter.assignments(selection));
    }
    @Test void staleProposedPeerFallsBackToStillEligibleBaseline() {
        var selected = adapter.select(10L,"1","conversation","summarize", List.of("read"),List.of(2L));
        when(agents.selectList(any())).thenReturn(List.of(agent(2,"Research")));
        assertEquals(List.of(2L), adapter.assignments(selected));
    }
    @Test void oversizedCandidateSetGuardsBaselineWithoutTruncating() {
        when(agents.selectList(any())).thenReturn(LongStream.range(2,66).mapToObj(id -> agent(id,"Peer"+id)).toList());
        var selected = adapter.select(10L,"1","conversation","summarize", List.of("read", "write"),Arrays.asList(65L,null));
        assertEquals(Arrays.asList(65L,null), adapter.assignments(selected));
        assertEquals(0,calls.get());
    }
    @Test void shadowRetainsExcludedLegacyBaselineInTicketAndAssignments() {
        properties.setMode(DecisionMode.SHADOW);
        var selected = adapter.select(10L,"1","conversation","summarize", List.of("read", "write"),Arrays.asList(99L,null));
        assertEquals(new DecisionValue.Choice("AGENT:99"), selected.steps().getFirst().ticket().effectiveValue());
        assertEquals(Arrays.asList(99L,null),adapter.assignments(selected));
    }
    @Test void shadowPreparationFailureReturnsLegacyPathAndActiveFailsClosed() {
        when(agents.selectList(any())).thenThrow(new IllegalStateException("unavailable"));
        properties.setMode(DecisionMode.SHADOW);
        assertNull(adapter.select(10L,"1","conversation","summarize", List.of("read"),List.of(2L)));
        properties.setMode(DecisionMode.ACTIVE);
        assertThrows(DecisionRecordingException.class, () -> adapter.select(10L,"1","conversation","summarize", List.of("read"),List.of(2L)));
    }
    @Test void disabledOrParentUserNameStillGuardsLocalButPlannerNameAloneDoesNot() {
        var disabled = agent(4,"Unavailable"); disabled.setEnabled(false);
        when(agents.selectList(any())).thenReturn(List.of(disabled,agent(1,"Parent"),agent(3,"Writing")));
        assertNull(adapter.assignments(adapter.select(10L,"1","conversation","Ask Unavailable", List.of("read"),null)));
        assertNull(adapter.assignments(adapter.select(10L,"1","conversation","Ask Parent", List.of("read"),null)));
        assertEquals(0,calls.get());
        assertEquals(List.of(3L),adapter.assignments(adapter.select(10L,"1","conversation","summarize", List.of("read"),List.of(3L))));
        assertEquals(1,calls.get());
    }

    @Test void revalidationIsScopedBeforeLockingAndNullWorkspaceDoesNotLock() {
        var selected=adapter.select(10L,"1","conversation","summarize", List.of("read"),List.of(99L));
        clearInvocations(agents);assertNull(adapter.assignments(selected));
        ArgumentCaptor<LambdaQueryWrapper<AgentEntity>> query=ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(agents).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("workspace_id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(10L));
        var unscoped=adapter.select(null,"1","conversation","summarize", List.of("read"),List.of(2L));
        clearInvocations(agents);assertNull(adapter.assignments(unscoped));verifyNoInteractions(agents);
    }
    @Test void localAssignmentsRetainNullListAndOffDoesNotReadCandidates() {
        proposed="LOCAL";
        assertNull(adapter.assignments(adapter.select(10L,"1","conversation","summarize", List.of("read", "write"),null)));
        properties.setMode(DecisionMode.OFF); clearInvocations(agents);
        assertNull(adapter.select(10L,"1","conversation","summarize", List.of("read", "write"),null));
        verifyNoInteractions(agents);
    }
    @Test void suppliesStepAndPublicCandidateMetadataWithoutPrivateConfiguration() {
        var parent = agent(1, "General"); parent.setDescription("General assistance");
        var peer = agent(3, "Quality analyst"); peer.setDescription("Query production quality metrics");
        peer.setTags("quality,sql"); peer.setAgentType("react");
        peer.setSystemPrompt("SECRET_SYSTEM"); peer.setRuntimeConfig("SECRET_CONFIG");
        when(agents.selectList(any())).thenReturn(List.of(parent, peer));
        var selected = adapter.select(10L, "1", "conversation", "PRIVATE_FULL_GOAL", List.of("Query last week yield", "Explain the decline"), null);
        assertEquals(List.of(3L, 3L), adapter.assignments(selected));
        assertEquals(2, requests.size());
        assertEquals(List.of("Query last week yield"), requests.get(0).facts().evidence());
        assertEquals(List.of("Explain the decline"), requests.get(1).facts().evidence());
        var question = (DecisionQuestion.Choice) requests.getFirst().question();
        assertEquals("agent-routing-v2", question.version());
        assertTrue(question.options().get(0).description().contains("General assistance"));
        assertTrue(question.options().get(1).description().contains("Query production quality metrics"));
        assertTrue(question.options().get(1).description().contains("quality,sql"));
        assertFalse(requests.toString().contains("SECRET_"));
        assertFalse(requests.toString().contains("PRIVATE_FULL_GOAL"));
        var recorded = ArgumentCaptor.forClass(vip.mate.decision.record.DecisionRecord.class);
        verify(records, times(2)).insert(recorded.capture());
        String audit = recorded.getAllValues().toString();
        assertFalse(audit.contains("Query last week yield"));
        assertFalse(audit.contains("Quality analyst"));
        assertFalse(audit.contains("Query production quality metrics"));
        assertFalse(audit.contains("SECRET_"));
    }
    @Test void missingOrOversizedStepAndCandidateBudgetGuardBaseline() {
        for (String step : List.of(" ", "x".repeat(2049))) {
            assertEquals(List.of(2L), adapter.assignments(adapter.select(10L,"1","conversation","summarize",List.of(step),List.of(2L))));
        }
        var peer = agent(3,"Writer"); peer.setDescription("x".repeat(1025));
        when(agents.selectList(any())).thenReturn(List.of(agent(2,"Research"), peer));
        assertEquals(List.of(2L), adapter.assignments(adapter.select(10L,"1","conversation","summarize",List.of("read"),List.of(2L))));
        when(agents.selectList(any())).thenReturn(LongStream.range(2,20).mapToObj(id -> {
            var a = agent(id,"Peer"+id); a.setDescription("x".repeat(700)); return a;
        }).toList());
        assertEquals(List.of(2L), adapter.assignments(adapter.select(10L,"1","conversation","summarize",List.of("read"),List.of(2L))));
        assertEquals(0, calls.get());
    }

}
