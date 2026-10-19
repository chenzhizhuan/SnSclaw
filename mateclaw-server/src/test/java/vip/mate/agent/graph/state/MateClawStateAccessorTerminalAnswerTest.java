package vip.mate.agent.graph.state;

import vip.mate.agent.runtime.state.RunState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the bridge that lets GoalEvaluationNode work uniformly across
 * graph flavors:
 * <ul>
 *   <li>ReAct path: FinalAnswerNode writes FINAL_ANSWER.</li>
 *   <li>Plan-Execute long path: PlanSummaryNode writes FINAL_SUMMARY.</li>
 *   <li>Plan-Execute short path: DirectAnswerNode writes DIRECT_ANSWER.</li>
 * </ul>
 */
class MateClawStateAccessorTerminalAnswerTest {

    private RunState mockState(String finalAnswer, String finalSummary, String directAnswer) {
        RunState s = mock(RunState.class);
        lenient().when(s.value(eq("final_answer"), eq(""))).thenReturn(finalAnswer);
        lenient().when(s.value(eq("final_summary"), eq(""))).thenReturn(finalSummary);
        lenient().when(s.value(eq("direct_answer"), eq(""))).thenReturn(directAnswer);
        return s;
    }

    @Test
    void reactPath_returnsFinalAnswer() {
        RunState s = mockState("ReAct answer", "", "");
        assertEquals("ReAct answer", new MateClawStateAccessor(s).terminalAnswer());
    }

    @Test
    void planExecuteLongPath_returnsFinalSummary() {
        RunState s = mockState("", "Plan summary text", "");
        assertEquals("Plan summary text", new MateClawStateAccessor(s).terminalAnswer());
    }

    @Test
    void planExecuteShortPath_returnsDirectAnswer() {
        RunState s = mockState("", "", "Direct quick answer");
        assertEquals("Direct quick answer", new MateClawStateAccessor(s).terminalAnswer());
    }

    @Test
    void allEmpty_returnsEmptyString() {
        RunState s = mockState("", "", "");
        assertEquals("", new MateClawStateAccessor(s).terminalAnswer());
    }

    @Test
    void finalAnswerWins_overFinalSummary() {
        // Defensive: if both happen to be populated (shouldn't, but state
        // is shared across graphs in tests), FINAL_ANSWER takes priority.
        RunState s = mockState("ReAct answer", "stale summary", "");
        assertEquals("ReAct answer", new MateClawStateAccessor(s).terminalAnswer());
    }
}
