package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.model.AgentEntity;
import vip.mate.decision.api.*;
import vip.mate.planning.service.*;
import vip.mate.planning.model.PlanEntity;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.service.TeamPlanBridge;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanGenerationRoutingTest {
    PlanningService planning=mock(PlanningService.class);
    NodeStreamingChatHelper streaming=mock(NodeStreamingChatHelper.class);
    AgentService agents=mock(AgentService.class);
    AgentRoutingDecisionAdapter adapter=mock(AgentRoutingDecisionAdapter.class);
    PlanGenerationNode node;
    AgentRoutingDecisionAdapter.Selection selection;
    @BeforeEach void setup() {
        node=new PlanGenerationNode(null,planning,streaming,null,null,null,null,agents);
        node.setRoutingAdapter(adapter);when(adapter.enabled()).thenReturn(true);
        selection=new AgentRoutingDecisionAdapter.Selection(10L,"1",List.of(
                new AgentRoutingDecisionAdapter.Step(2L,new DecisionTicket("ticket",DecisionMode.SHADOW,new DecisionValue.Choice("AGENT:2"))),
                new AgentRoutingDecisionAdapter.Step(null,new DecisionTicket("ticket2",DecisionMode.SHADOW,new DecisionValue.Choice("LOCAL")))));
        when(adapter.select(any(),any(),any(),any(),anyList(),any())).thenReturn(selection);
        var peer=new AgentEntity();peer.setId(2L);peer.setName("Research");peer.setEnabled(true);peer.setWorkspaceId(10L);
        when(agents.listAgentsByWorkspace(10L,true)).thenReturn(List.of(peer));
        var plan=new PlanEntity();plan.setId(50L);
        when(planning.createPlan(anyString(),anyString(),anyString(),anyList(),any())).thenReturn(plan);
        when(planning.createPlan(anyString(),anyString(),anyString(),anyList(),any(),any())).thenReturn(plan);
        reply("[\"Research\"]");
    }
    void reply(String names) {
        when(streaming.streamCallSilent(any(),any(),any(),any())).thenReturn(new NodeStreamingChatHelper.StreamResult(
                "{\"needs_planning\":true,\"steps\":[\"read\",\"write\"],\"step_agents\":"+names+"}","",null,List.of(),false,0,0));
    }
    OverAllState state() {
        var values=new HashMap<String,Object>();values.put(PlanStateKeys.GOAL,"Ask Research to summarize");
        values.put(MateClawStateKeys.AGENT_ID,"1");values.put(MateClawStateKeys.CONVERSATION_ID,"conversation");
        values.put(MateClawStateKeys.CHAT_ORIGIN,ChatOrigin.web("conversation","admin",10L,null).withAgent(1L));
        return new OverAllState(values);
    }
    @Test void enabledNonTeamUsesTicketOverloadAndOriginalBaseline() throws Exception {
        node.apply(state());verify(adapter).select(10L,"1","conversation","Ask Research to summarize",List.of("read","write"),Arrays.asList(2L,null));
        verify(planning).createPlan("1","conversation","Ask Research to summarize",List.of("read","write"),Arrays.asList(2L,null),selection);
        verify(streaming,times(1)).streamCallSilent(any(),any(),any(),any());
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void teamSuccessAndSerialFallbackBypassAdapter(boolean resolved) throws Exception {
        var bridge=mock(TeamPlanBridge.class);node.setTeamPlanBridge(bridge);
        var team=new AgentTeamEntity();team.setId(20L);team.setName("Team");
        when(bridge.leadTeam(1L)).thenReturn(Optional.of(team));
        when(bridge.resolveMembers(any(),any(),any())).thenReturn(resolved?List.of(2L,2L):null);
        when(bridge.delegatePlan(any(),any(),any(),any(),any(),any(),any())).thenReturn("delegated");
        node.apply(state());verifyNoInteractions(adapter);
        verify(planning).createPlan(eq("1"),eq("conversation"),anyString(),eq(List.of("read","write")),eq(resolved?List.of(2L,2L):Arrays.asList(2L,null)));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void absentAndOffUseLegacyOverload(boolean absent) throws Exception {
        if(absent)node.setRoutingAdapter(null);else when(adapter.enabled()).thenReturn(false);
        node.apply(state());verify(planning).createPlan(anyString(),anyString(),anyString(),anyList(),eq(Arrays.asList(2L,null)));
        verify(planning,never()).createPlan(anyString(),anyString(),anyString(),anyList(),any(),any());
    }
    @Test void missingNamesRetainNullList() throws Exception {
        reply("[]");node.apply(state());verify(adapter).select(any(),any(),any(),any(),eq(List.of("read","write")),isNull());
    }
    @Test void duplicateNamesKeepLegacyLastWinnerAndExtraNamesAreIgnored() throws Exception {
        var first=new AgentEntity();first.setId(2L);first.setName("Research");
        var last=new AgentEntity();last.setId(3L);last.setName("Research");
        when(agents.listAgentsByWorkspace(10L,true)).thenReturn(List.of(first,last));reply("[\"Research\",\"unknown\",\"Research\"]");
        node.apply(state());verify(adapter).select(any(),any(),any(),any(),eq(List.of("read","write")),eq(Arrays.asList(3L,null)));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void auditFailureNeverFallsBackToUnrecordedPlan(boolean wrapped) {
        var failure=new DecisionRecordingException();
        when(planning.createPlan(anyString(),anyString(),anyString(),anyList(),any(),any())).thenThrow(wrapped?new IllegalStateException(failure):failure);
        assertThrows(DecisionRecordingException.class,()->node.apply(state()));
        verify(planning,never()).createPlan(anyString(),anyString(),anyString(),anyList());
    }
}
