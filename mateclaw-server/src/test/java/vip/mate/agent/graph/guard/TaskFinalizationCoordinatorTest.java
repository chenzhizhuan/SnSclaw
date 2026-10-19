package vip.mate.agent.graph.guard;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskFinalizationCoordinatorTest {
    final NodeStreamingChatHelper helper=mock(NodeStreamingChatHelper.class);
    final ChatModel model=mock(ChatModel.class);
    final TaskFinalizationCoordinator coordinator=new TaskFinalizationCoordinator(helper,null);
    final ActionExecutionLedger ledger=ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("f","calculate","missing language",false)));
    static String json(String decision,String claims){return "{\"decision\":\""+decision+"\",\"taskComplete\":true,\"remainingWork\":false,\"failedCallCount\":1,\"recoveryClaims\":"+claims+",\"reason\":\"checked\"}";}
    NodeStreamingChatHelper.StreamResult result(String text){return new NodeStreamingChatHelper.StreamResult(text,"checker thinking",null,List.of(),false,7,3,false,null,NodeStreamingChatHelper.ErrorType.NONE,false,2,1,1);}
    TaskFinalizationCoordinator.Review review(int window,int attempts,int used){return coordinator.review(model,"c","Calculate all stages","candidate",ledger,"untrusted progress",window,attempts,10,used,true,new RunState());}
    @Test void revisionFeedbackRequestsSpecificOffendingClauseAndGroundedReplacement() {
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
            String prompt=((Prompt)call.getArgument(1)).getContents();
            assertTrue(prompt.contains("quote the short offending clause"));
            assertTrue(prompt.contains("give a concrete grounded replacement"));
            assertTrue(prompt.contains("at most 256 characters"));
            return result(json("REVISE","[]"));
        });
        assertEquals(TaskFinalizationGate.Outcome.REVISE,review(16000,0,0).result().outcome());
    }
    @Test void latestAggregateAfterPreviewIsGroundedFromExactRetainedResponse() {
        String output="x".repeat(600)+" totalSquareSum=2094116873200";
        var receipts=ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("aggregate","execute_code",output,true)));
        var response=new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("aggregate","execute_code",output);
        for(String source:List.of(MateClawStateKeys.TOOL_RESULTS,MateClawStateKeys.MESSAGES,MateClawStateKeys.PENDING_EVENTS)) {
            reset(helper);
            Object retained=source.equals(MateClawStateKeys.TOOL_RESULTS)?List.of(response):source.equals(MateClawStateKeys.MESSAGES)
                ?List.of(org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(response)).build())
                :List.of(GraphEventPublisher.toolComplete("aggregate","execute_code",output,true));
            when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
                String prompt=((Prompt)call.getArgument(1)).getContents();
                assertTrue(prompt.contains("totalSquareSum=2094116873200"));
                assertTrue(prompt.contains("\"resultComplete\":true"));
                assertTrue(prompt.contains("\"omittedResultChars\":0"));
                return result(json("ACCEPT","[]").replace("\"failedCallCount\":1","\"failedCallCount\":0"));
            });
            var check=coordinator.review(model,"c","aggregate","table",receipts,"",16000,0,10,0,true,new RunState(Map.of(source,retained)));
            assertEquals(TaskFinalizationGate.Outcome.ACCEPT,check.result().outcome(),source);
            verify(helper).streamCallSilent(any(),any(),anyString(),anyString());
        }
    }
    @Test void canonicalCompletionWinsOverDisplayAugmentedResponse() {
        String output="x".repeat(600)+" total=42";
        var event=GraphEventPublisher.toolComplete("aggregate","execute_code",output,true);
        var receipts=ActionExecutionLedger.fromEvents(List.of(event));
        var augmented=new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("aggregate","execute_code",output+" display directive");
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
            String prompt=((Prompt)call.getArgument(1)).getContents();
            assertTrue(prompt.contains("total=42"));assertFalse(prompt.contains("display directive"));
            return result(json("ACCEPT","[]").replace("\"failedCallCount\":1","\"failedCallCount\":0"));
        });
        var check=coordinator.review(model,"c","aggregate","table",receipts,"",16000,0,10,0,true,
            new RunState(Map.of(MateClawStateKeys.TOOL_RESULTS,List.of(augmented),MateClawStateKeys.PENDING_EVENTS,List.of(event))));
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT,check.result().outcome());
    }
    @Test void missingWrongIdentityAndOversizedLatestEvidenceFailBeforeChecker() {
        for(String output:List.of("x".repeat(600)+" actual-total", "x".repeat(30000)+" actual-total")) {
            var receipts=ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("aggregate","execute_code",output,true)));
            for(String id:List.of("aggregate","wrong-id")) {
                reset(helper);
                var response=new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(id,"execute_code",output);
                var state=new RunState(Map.of(MateClawStateKeys.TOOL_RESULTS,List.of(response)));
                if(id.equals("aggregate") && output.length()<1000) state=new RunState();
                var check=coordinator.review(model,"c","aggregate","table",receipts,"",16000,0,10,0,true,state);
                assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,check.result().outcome());
                assertEquals(0,check.calls());verifyNoInteractions(helper);
            }
        }
    }
    @Test void malformedAndForgedVerdictsFailClosedWithUsage() {
        for(String verdict:List.of("bad",json("ACCEPT","[{\"failedCallId\":\"f\",\"successfulCallId\":\"forged\"}]"))) {
            when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenReturn(result(verdict));
            var review=review(16000,0,0);assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,review.result().outcome());
            assertEquals(1,review.calls());assertEquals(7,review.usage().promptTokens());assertEquals(2,review.usage().cacheReadTokens());
        }
    }
    @Test void requiredObjectiveAndCandidateAreNeverTruncatedToSpendCall() {
        var check=coordinator.review(model,"c","x".repeat(20000),"candidate",ledger,"",2048,0,10,0,true,new RunState());
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,check.result().outcome());assertEquals(0,check.calls());verifyNoInteractions(helper);
    }
    @Test void callCeilingAndMissingCoreRejectBeforeTransport() {
        assertEquals(0,review(16000,0,50).calls());assertEquals(0,review(64,0,0).calls());verifyNoInteractions(helper);
    }
    @Test void checkerHasNoCallbacksAndRestoresThinkingScopeOnErrorAndCancel() {
        var callback=mock(ToolCallback.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("test").toolCallbacks(List.of(callback)).maxTokens(10000).build());
        for(RuntimeException error:List.of(new IllegalStateException("offline"),new CancellationException("stop"))) {
            reset(helper);
            when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
                Prompt prompt=call.getArgument(1);assertEquals("off",ThinkingLevelHolder.get());
                assertTrue(((ToolCallingChatOptions)prompt.getOptions()).getToolCallbacks().isEmpty());
                assertEquals(1024,prompt.getOptions().getMaxTokens());throw error;
            });
            ThinkingLevelHolder.set("high");
            try {
                var review=review(16000,0,0);assertEquals("high",ThinkingLevelHolder.get());
                assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,review.result().outcome());assertEquals(1,review.calls());
                assertEquals(error instanceof CancellationException,review.stopped());verifyNoInteractions(callback);
            } finally {ThinkingLevelHolder.clear();}
        }
    }
    @Test void checkerInspectsRetryProseEvenWhenRecoveryClaimsArrayIsEmpty() {
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
            String instruction=((Prompt)call.getArgument(1)).getInstructions().getFirst().getText();
            for(String required:List.of("随后补齐 language=\"python\" 重试","补齐参数后成功","retried successfully",
                    "empty recoveryClaims does not excuse unsupported candidate prose", "original-call retry remains unverified",
                    "recoveredEarlierFailure=true", "not evidence that the original failed call was retried or recovered"))
                assertTrue(instruction.contains(required),required);
            return result(json("REVISE","[]"));
        });
        assertEquals(TaskFinalizationGate.Outcome.REVISE,review(16000,0,0).result().outcome());
    }
    @Test void reviewerProtocolGivesConciseReasonLimitAndStrictlyValidExample() {
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
            String instruction=((Prompt)call.getArgument(1)).getInstructions().getFirst().getText();
            assertTrue(instruction.contains("reason must be at most 256 characters"));
            assertTrue(instruction.contains("Decision choices: ACCEPT, REVISE, CONTINUE"));
            assertTrue(instruction.contains("No markdown fences, preamble, trailing text, or extra explanations"));
            assertFalse(instruction.contains("ACCEPT|REVISE|CONTINUE"));
            int start=instruction.indexOf("{\"decision\":");
            String example=instruction.substring(start,instruction.indexOf('}',start)+1);
            var parsed=new TaskFinalizationGate().parse(example).orElseThrow();
            assertEquals(TaskFinalizationGate.Outcome.ACCEPT,parsed.decision());
            assertTrue(parsed.reason().length()<=256);
            return result(json("ACCEPT","[]"));
        });
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT,review(16000,0,0).result().outcome());
        assertEquals(1024,TaskFinalizationGate.MAX_REASON_CHARS);
    }
    @Test void checkerPromptSeparatesObjectiveCandidateCanonicalEvidenceAndRuntime() {
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenAnswer(call->{
            String prompt=((Prompt)call.getArgument(1)).getContents();
            for(String required:List.of("Calculate all stages","candidate","CANONICAL FACTS","RUNTIME: ACTIVE","failedCallCount","untrusted progress","print(1)","budget exhausted"))assertTrue(prompt.contains(required),required);
            return result(json("ACCEPT","[]"));
        });
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT,review(16000,2,0).result().outcome());
    }
    @Test void stoppedOrPartialCheckerNeverAcceptsAndPreservesUsage() {
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult(json("ACCEPT","[]"),"",null,List.of(),false,7,3,true,null,NodeStreamingChatHelper.ErrorType.NONE,true,2,1,1));
        var check=review(16000,0,0);assertTrue(check.stopped());assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,check.result().outcome());assertEquals(7,check.usage().promptTokens());
    }
    @Test void safeDiagnosticsDistinguishSchemaAndCanonicalFactRejectionWithoutRawText() {
        var tracker=mock(vip.mate.channel.web.ChatStreamTracker.class);
        var observed=new TaskFinalizationCoordinator(helper,tracker);
        var cases=Map.of("SECRET_INVALID_VERDICT","INVALID_VERDICT",
                json("ACCEPT","[]").replace("\"failedCallCount\":1","\"failedCallCount\":9"),"FAILED_CALL_COUNT_MISMATCH",
                json("ACCEPT","[{\"failedCallId\":\"f\",\"successfulCallId\":\"forged\"}]"),"UNSUPPORTED_RECOVERY");
        for(var entry:cases.entrySet()) {
            reset(helper,tracker);
            when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenReturn(result(entry.getKey()));
            var check=observed.review(model,"c","SECRET_CURRENT_OBJECTIVE","SECRET_CANDIDATE",ledger,"SECRET_PROGRESS",16000,0,10,0,true,new RunState());
            assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,check.result().outcome());
            var data=org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(tracker,times(2)).broadcastObject(eq("c"),eq("finalization_check"),data.capture());
            Map<?,?> started=data.getAllValues().get(0),completed=data.getAllValues().get(1);
            for(String field:List.of("objectiveTokens","candidateTokens","progressHintsTokens"))
                assertTrue(started.get(field) instanceof Integer,field);
            assertEquals(entry.getValue(),completed.get("diagnostic"));
            assertEquals(entry.getValue().equals("INVALID_VERDICT")?"INVALID_JSON":"NONE",completed.get("parseFailureCode"));
            assertTrue(completed.get("responseTextChars") instanceof Integer);
            assertTrue(completed.get("responseThinkingChars") instanceof Integer);
            assertFalse(data.getAllValues().toString().contains("SECRET_"));
            assertFalse(data.getAllValues().toString().contains("forged"));
        }
    }
    @Test void fencedVerdictRemainsRejectedAndReportsOnlyFixedParseCategory() {
        var tracker=mock(vip.mate.channel.web.ChatStreamTracker.class);
        var observed=new TaskFinalizationCoordinator(helper,tracker);
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenReturn(result("```json\n"+json("ACCEPT","[]")+"\n```"));
        var check=observed.review(model,"c","objective","candidate",ledger,"",16000,0,10,0,true,new RunState());
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,check.result().outcome());
        var data=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(tracker,times(2)).broadcastObject(eq("c"),eq("finalization_check"),data.capture());
        assertEquals("FENCED_JSON",data.getAllValues().get(1).get("parseFailureCode"));
        assertFalse(data.getAllValues().toString().contains("```"));
    }
}
