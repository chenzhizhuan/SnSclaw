package vip.mate.agent.graph.plan.node;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.planning.service.PlanningService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanPayloadRecoveryTest {
    private NodeStreamingChatHelper.StreamResult payload() {
        return new NodeStreamingChatHelper.StreamResult(null,"",new AssistantMessage(""),List.of(),false,0,0,false,
                "HTTP 413",NodeStreamingChatHelper.ErrorType.PAYLOAD_TOO_LARGE);
    }
    @SuppressWarnings("unchecked")
    private void assertIncomplete(Map<String,Object> output) {
        assertEquals("incomplete", output.get(MateClawStateKeys.FINISH_REASON));
        assertTrue(output.values().stream().anyMatch(v -> v instanceof List<?> list && list.stream().anyMatch(e ->
                e instanceof GraphEventPublisher.GraphEvent event && "incomplete".equals(event.data().get("reason")))));
    }
    @Test void generationMandatoryPayloadTerminatesWithoutCreatingFallbackPlan() throws Exception {
        var planning=mock(PlanningService.class); var helper=mock(NodeStreamingChatHelper.class);
        when(helper.streamCallSilent(any(),any(),any(),any())).thenReturn(payload());
        var node=new PlanGenerationNode(mock(ChatModel.class),planning,helper,null,null,null,null,null);
        var output=node.apply(new RunState(Map.of(PlanStateKeys.GOAL,"goal",MateClawStateKeys.CONVERSATION_ID,"conv")));
        verify(helper,times(1)).streamCallSilent(any(),any(),any(),any());
        verifyNoInteractions(planning);
        assertEquals(false,output.get(PlanStateKeys.NEEDS_PLANNING));
        assertTrue(output.get(PlanStateKeys.DIRECT_ANSWER).toString().contains("任务未完成"));
        assertIncomplete(output);
    }
    @Test void executionMandatoryPayloadAbortsWithoutCompletingOrReplanning() throws Exception {
        var planning=mock(PlanningService.class); var helper=mock(NodeStreamingChatHelper.class);
        when(helper.streamCall(any(),any(),any(),any())).thenReturn(payload());
        var node=new StepExecutionNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,
                planning,null,null,helper,null);
        var output=node.apply(new RunState(Map.of(PlanStateKeys.GOAL,"goal",PlanStateKeys.PLAN_STEPS,List.of("step"),
                PlanStateKeys.PLAN_ID,1L,MateClawStateKeys.CONVERSATION_ID,"conv")));
        verify(helper,times(1)).streamCall(any(),any(),any(),any());
        verify(planning,never()).updateSubPlanResult(any(),anyInt(),any());
        assertEquals("plan_aborted",output.get(MateClawStateKeys.CURRENT_PHASE));
        assertTrue(output.get(PlanStateKeys.FINAL_SUMMARY).toString().contains("任务未完成"));
        assertIncomplete(output);
    }
}
