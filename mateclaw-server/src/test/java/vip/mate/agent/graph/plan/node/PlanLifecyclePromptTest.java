package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.goal.model.*;
import vip.mate.goal.service.GoalService;
import vip.mate.planning.service.PlanningService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanLifecyclePromptTest {
    @Test void actualTriagePromptSeesPausedState() throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);var goals=mock(GoalService.class);
        var g=new GoalEntity();g.setId(9L);g.setStatus(GoalStatus.PAUSED);
        when(goals.findLatestByConversation("conv")).thenReturn(g);
        when(helper.streamCallSilent(any(),any(),any(),any())).thenReturn(
            new NodeStreamingChatHelper.StreamResult("{\"needs_planning\":false,\"direct_answer\":\"paused\"}","",null,List.of(),false,0,0));
        var node=new PlanGenerationNode(null,mock(PlanningService.class),helper,null,null,goals,null,null);
        node.apply(new OverAllState(new HashMap<>(Map.of(PlanStateKeys.GOAL,"当前状态",MateClawStateKeys.CONVERSATION_ID,"conv"))));
        var prompt=ArgumentCaptor.forClass(Prompt.class);
        verify(helper).streamCallSilent(any(),prompt.capture(),any(),any());
        assertTrue(prompt.getValue().getSystemMessage().getText().contains("paused"));
        assertTrue(prompt.getValue().getSystemMessage().getText().contains("resumeGoal"));
    }
    @Test void actualSummaryPromptDistinguishesPlanFromGoalCompletion() throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);
        when(helper.streamCall(any(),any(),any(),any())).thenReturn(
            new NodeStreamingChatHelper.StreamResult("summary","",null,List.of(),false,0,0));
        var goals=mock(GoalService.class);
        var goal=new GoalEntity();goal.setId(9L);goal.setStatus(GoalStatus.ACTIVE);
        when(goals.findActiveByConversation("conv")).thenReturn(goal);
        var node=new PlanSummaryNode(null,mock(PlanningService.class),helper);
        node.setGoalService(goals);
        node.apply(new OverAllState(new HashMap<>(Map.of(
            PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"summary",MateClawStateKeys.CONVERSATION_ID,"conv"))));
        var prompt=ArgumentCaptor.forClass(Prompt.class);
        verify(helper).streamCall(any(),prompt.capture(),any(),any());
        assertTrue(prompt.getValue().getSystemMessage().getText().contains("getGoalStatus"));
        assertTrue(prompt.getValue().getSystemMessage().getText().contains("status=active"));
        verify(goals).findActiveByConversation("conv");
    }
}
