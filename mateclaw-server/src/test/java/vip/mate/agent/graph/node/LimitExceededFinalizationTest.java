package vip.mate.agent.graph.node;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.guard.TaskFinalizationCoordinator;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.runtime.state.RunState;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;
class LimitExceededFinalizationTest {
 @Test void terminalToolRunUsesReceiptFallbackWithoutSpendingOrPublishingUnverifiedModelProse() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);
  var node=new LimitExceededNode(mock(ChatModel.class),null,helper);
  // Explicit integration setter required before the terminal branch is enabled.
  try {node.getClass().getMethod("setFinalizationCoordinator",TaskFinalizationCoordinator.class)
    .invoke(node,new TaskFinalizationCoordinator(helper,null));}
  catch(NoSuchMethodException missing){throw new AssertionError("Terminal path must share publication guard",missing);}
  var state=new RunState(Map.of(CONVERSATION_ID,"terminal",USER_MESSAGE,"do all stages",MAX_ITERATIONS,2,
    LLM_CALL_COUNT,10,ACTION_EXECUTION_LEDGER,ActionExecutionLedger.fromEvents(List.of(
    GraphEventPublisher.toolComplete("f","calculate","failed",false),GraphEventPublisher.toolComplete("s","calculate","42",true)))));
  var output=node.apply(state);
  assertEquals("max_iterations_reached",output.get(FINISH_REASON));
  assertEquals(false,output.get(CONTENT_STREAMED));assertEquals(10,output.get(LLM_CALL_COUNT));
  assertTrue(String.valueOf(output.get(FINAL_ANSWER_DRAFT)).contains("尚未完成"));
  assertFalse(String.valueOf(output.get(FINAL_ANSWER_DRAFT)).contains("operationIdentity"));
  assertTrue(String.valueOf(output.get(FINAL_ANSWER_DRAFT)).contains("失败 1"));verifyNoInteractions(helper);
 }
}
