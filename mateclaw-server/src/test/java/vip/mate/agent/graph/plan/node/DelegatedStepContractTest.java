package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.agent.graph.plan.edge.StepProgressDispatcher;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.tool.builtin.DelegateAgentTool;
import vip.mate.planning.service.PlanningService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DelegatedStepContractTest {
    final PlanningService planning = mock(PlanningService.class);
    final DelegateAgentTool delegate = mock(DelegateAgentTool.class);
    final StepExecutionNode node = new StepExecutionNode(null, null, null, planning, null, null, null, null, 1000);

    Map<String, Object> run(String reply, List<String> previous) throws Exception {
        node.setDelegateAgentTool(delegate);
        when(planning.getStepAssignedAgent(42L, 1)).thenReturn(20L);
        when(delegate.delegateByAgentIdStructured(eq(20L), anyString(), any())).thenReturn(
                new DelegateAgentTool.ChildResult(0, "worker", true, reply, null, 1, "success", reply.length(), reply.length(), 0, 0));
        return node.apply(new OverAllState(new HashMap<>(Map.of(
                PlanStateKeys.PLAN_ID, 42L, PlanStateKeys.PLAN_STEPS, List.of("计算良率", "计算98%与前一步良率差"),
                PlanStateKeys.CURRENT_STEP_INDEX, 1, PlanStateKeys.COMPLETED_RESULTS, previous,
                PlanStateKeys.WORKING_CONTEXT, "PRIVATE_FULL_CONTEXT", PlanStateKeys.GOAL, "PRIVATE_ORIGINAL_GOAL",
                MateClawStateKeys.AGENT_ID, "10"))));
    }

    @Test void preservesRuntimeWorkerIdentityForSummaryAndAcceptance() throws Exception {
        var adapter = mock(vip.mate.goal.service.GoalDecisionAdapter.class);
        when(adapter.enabled()).thenReturn(true); node.setGoalDecisionAdapter(adapter);
        var out = run("{\"status\":\"COMPLETED\",\"result\":\"3个百分点\",\"evidence\":\"98%-95%=3\"}", List.of("95%"));
        String completed = out.get(PlanStateKeys.COMPLETED_RESULTS).toString();
        assertTrue(completed.contains("assignedAgentId"));
        assertTrue(completed.contains("20"));
        assertTrue(completed.contains("worker"));
        assertTrue(completed.contains("COMPLETED"));
        assertTrue(completed.contains("3个百分点"));
        assertFalse(completed.contains("PRIVATE_ORIGINAL_GOAL"));
    }

    @Test void passesBoundedDependencyEvidenceWithoutFullContext() throws Exception {
        run("{\"status\":\"COMPLETED\",\"result\":\"3个百分点\",\"evidence\":\"98%-95%=3个百分点\"}",List.of("步骤1：95%"));
        var task = ArgumentCaptor.forClass(String.class);
        verify(delegate).delegateByAgentIdStructured(eq(20L), task.capture(), any());
        assertTrue(task.getValue().contains("95%"));
        assertTrue(task.getValue().contains("COMPLETED"));
        assertFalse(task.getValue().contains("PRIVATE_FULL_CONTEXT"));
        assertFalse(task.getValue().contains("PRIVATE_ORIGINAL_GOAL"));
        verify(planning).updateSubPlanResult(42L, 1, "3个百分点");
    }

    @Test void blocksUnstructuredCannotCalculateInsteadOfCompleting() throws Exception {
        var out = run("无法计算，缺少前一步结果", List.of());
        assertEquals("plan_aborted",out.get(MateClawStateKeys.CURRENT_PHASE));
        verify(planning, never()).updateSubPlanResult(any(), anyInt(), anyString());
        verify(planning).updateSubPlanFailure(eq(42L), eq(1), anyString());
        verify(planning).markPlanFailed(eq(42L), anyString());
        assertFalse(out.containsKey(PlanStateKeys.COMPLETED_RESULTS));
        assertEquals(StateGraph.END,new StepProgressDispatcher().apply(new OverAllState(out)));
    }

    @Test void blockedStatusDoesNotBecomeSuccessEvenWithNonemptyResult() throws Exception {
        var out = run("{\"status\":\"BLOCKED\",\"result\":\"缺数据\",\"evidence\":\"缺少良率\"}", List.of());
        assertEquals("plan_aborted",out.get(MateClawStateKeys.CURRENT_PHASE));
    }

    @Test void completedRequiresEvidence() throws Exception {
        var out = run("{\"status\":\"COMPLETED\",\"result\":\"好了\",\"evidence\":\"\"}", List.of());
        assertEquals("plan_aborted",out.get(MateClawStateKeys.CURRENT_PHASE));
    }

    @Test void hugeDependencyContextIsBoundedAndMarked() throws Exception {
        run("invalid", List.of("x".repeat(20000), "步骤1：95%"));
        var task = ArgumentCaptor.forClass(String.class);
        verify(delegate).delegateByAgentIdStructured(eq(20L), task.capture(), any());
        assertTrue(task.getValue().length()<12000);
        assertTrue(task.getValue().contains("truncated"));
        assertTrue(task.getValue().contains("95%"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "null", "[]", "{}", "{\"status\":true,\"result\":\"ok\",\"evidence\":\"test\"}",
        "{\"status\":\"UNKNOWN\",\"result\":\"ok\",\"evidence\":\"test\"}",
        "{\"status\":\"COMPLETED\",\"result\":\"ok\",\"evidence\":\"test\"} {}",
        "{\"status\":\"COMPLETED\",\"result\":\" \",\"evidence\":\"test\"}"
    })
    void malformedOrUnsupportedContractsFailClosed(String reply) {
        assertFalse(DelegatedStepContract.parse(reply).completed());
    }

}
