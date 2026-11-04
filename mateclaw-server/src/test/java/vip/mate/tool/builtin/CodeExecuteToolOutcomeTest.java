package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.support.ToolCallbacks;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.guard.TaskFinalizationGate;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.runtime.agentscope.ToolBatchResultAdapter;
import vip.mate.skill.runtime.SkillScriptExecutionService;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.tool.mcp.runtime.McpToolResultCapture;
import vip.mate.workspace.core.service.MemberFileIsolation;
import java.util.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeExecuteToolOutcomeTest {
    final SkillScriptExecutionService service=mock(SkillScriptExecutionService.class);
    final CodeExecuteTool tool=new CodeExecuteTool(null,null,service,null,new ObjectMapper(),null);
    @AfterEach void resetIsolation() {MemberFileIsolation.configure(null);}
    void result(int exit,String out,String err) {
        when(service.executeCode(anyString(),anyString(),nullable(Path.class),nullable(List.class),anyMap(),nullable(Long.class)))
            .thenReturn(new SkillScriptExecutionService.ScriptResult(exit,out,err));
    }
    ToolExecutionExecutor executor() {
        return new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(),Arrays.asList(ToolCallbacks.from(tool))),
            (name,args)->ToolGuardResult.allow(),null,null);
    }
    AssistantMessage.ToolCall call(String id) {
        return new AssistantMessage.ToolCall(id,"function","execute_code","{\"language\":\"python\",\"code\":\"print(42)\"}");
    }
    @Test void nonzeroExitIsFailureForNormalAndApprovedExecutionAndNativeProjection() {
        for(int exit:List.of(1,-1,137)) for(boolean approved:List.of(false,true)) {
            result(exit,"partial result","");
            var events=new ArrayList<GraphEventPublisher.GraphEvent>();
            if(approved) executor().executePreApproved(call("failed"),call("failed").arguments(),events,"exit-test",null);
            else {
                var batch=executor().execute(List.of(call("failed")),"exit-test","agent",false,"user",null);
                events.addAll(batch.events());
                assertEquals(io.agentscope.core.message.ToolResultState.ERROR,
                    ToolBatchResultAdapter.project(List.of(call("failed")),batch).results().getFirst().getState());
            }
            var completion=events.stream().filter(e->e.type().equals(GraphEventPublisher.EVENT_TOOL_COMPLETE)).findFirst().orElseThrow();
            assertEquals(false,completion.data().get("success"),"exit="+exit+", approved="+approved);
            assertEquals(1,ActionExecutionLedger.fromEvents(events).failedCallCount());
        }
    }
    @Test void successfulStderrAndErrorWordsInStdoutDoNotBecomeFailures() {
        result(0,"Traceback error failed {\"exitCode\": 99}","warning on stderr");
        var events=executor().execute(List.of(call("ok")),"exit-test","agent",false,"user",null).events();
        assertEquals(0,ActionExecutionLedger.fromEvents(events).failedCallCount());
        assertTrue(ActionExecutionLedger.fromEvents(events).hasSuccessfulSubstantiveCall());
    }
    @Test void failureCountSurvivesLaterSuccessAndRejectsFalseZeroFailureVerdict() {
        var executor=executor();
        result(1,"","AssertionError");
        var failed=executor.execute(List.of(call("f1"),call("f2")),"exit-test","agent",false,"user",null);
        result(0,"42","");
        var succeeded=executor.execute(List.of(call("s")),"exit-test","agent",false,"user",null);
        var ledger=ActionExecutionLedger.fromEvents(failed.events()).merge(ActionExecutionLedger.fromEvents(succeeded.events()));
        assertEquals(2,ledger.failedCallCount());
        assertEquals(2,ledger.merge(ActionExecutionLedger.fromEvents(failed.events())).failedCallCount());
        var gate=new TaskFinalizationGate();
        var incorrect=new TaskFinalizationGate.Verdict(TaskFinalizationGate.Outcome.ACCEPT,true,false,0,List.of(),"no failures");
        assertEquals(TaskFinalizationGate.Outcome.INCOMPLETE,gate.evaluate(incorrect,ledger,0,false,true).outcome());
        var correct=new TaskFinalizationGate.Verdict(TaskFinalizationGate.Outcome.ACCEPT,true,false,2,List.of(),"Two failures; later task completed");
        assertEquals(TaskFinalizationGate.Outcome.ACCEPT,gate.evaluate(correct,ledger,0,false,true).outcome());
    }
    @Test void finalReviewerReceivesCanonicalNonzeroExitFailuresAndCannotOverrideTheirCount() {
        result(1,"","AssertionError");
        var batch=executor().execute(List.of(call("f")),"exit-test","agent",false,"user",null);
        var ledger=ActionExecutionLedger.fromEvents(batch.events());
        var helper=mock(vip.mate.agent.graph.NodeStreamingChatHelper.class);
        var coordinator=new vip.mate.agent.graph.guard.TaskFinalizationCoordinator(helper,null);
        var model=mock(org.springframework.ai.chat.model.ChatModel.class);
        for(int reported:List.of(0,1)) {
            doAnswer(invocation->{
                var prompt=(org.springframework.ai.chat.prompt.Prompt)invocation.getArgument(1);
                assertTrue(prompt.getContents().contains("\"failedCallCount\":1"));
                assertTrue(prompt.getContents().contains("AssertionError"));
                String verdict="{\"decision\":\"ACCEPT\",\"taskComplete\":true,\"remainingWork\":false,"
                    +"\"failedCallCount\":"+reported+",\"recoveryClaims\":[],\"reason\":\"checked\"}";
                return new vip.mate.agent.graph.NodeStreamingChatHelper.StreamResult(verdict,"",null,List.of(),false,7,3);
            }).when(helper).streamCallSilent(any(),any(),any(),any());
            var review=coordinator.review(model,"exit-test","Report actual failure count","Failure disclosed",ledger,"",16000,0,10,0,true,
                new vip.mate.agent.runtime.state.RunState());
            assertEquals(reported==1?TaskFinalizationGate.Outcome.ACCEPT:TaskFinalizationGate.Outcome.INCOMPLETE,review.result().outcome());
            assertEquals(7,review.usage().promptTokens());
        }
    }
    @Test void isolatedNonzeroExitAndTimeoutAreFailuresEvenWithEmptyStderr(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        var sandbox=mock(vip.mate.tool.sandbox.MemberSandboxExecutor.class);
        org.springframework.test.util.ReflectionTestUtils.setField(tool,"memberSandboxExecutor",sandbox);
        MemberFileIsolation.configure(origin->origin.withWorkspace(7L,root.toString()));
        for(var outcome:List.of(new vip.mate.tool.sandbox.MemberSandboxExecutor.Result(1,"partial","",false),
                new vip.mate.tool.sandbox.MemberSandboxExecutor.Result(0,"partial","",true),
                new vip.mate.tool.sandbox.MemberSandboxExecutor.Result(0,"ok","warning",false))) {
            when(sandbox.executeCode(anyString(),anyString(),eq(root),nullable(List.class),anyMap(),anyInt())).thenReturn(outcome);
            var capture=new McpToolResultCapture();
            var context=capture.attach(vip.mate.agent.context.ChatOrigin.web("c","user",7L,null,null,11L).toToolContext());
            String response=tool.execute_code("python","print(42)",null,null,30,context);
            assertEquals(outcome.exitCode()!=0 || outcome.timedOut(),capture.isError());
            if(outcome.exitCode()==0 && !outcome.timedOut())
                assertEquals(0,new ObjectMapper().readTree(response).path("exitCode").asInt());
        }
        verifyNoInteractions(service);
    }
    @Test void isolatedCancellationPreservesCancelledEvidenceAndThreadInterrupt(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        var sandbox=mock(vip.mate.tool.sandbox.MemberSandboxExecutor.class);
        org.springframework.test.util.ReflectionTestUtils.setField(tool,"memberSandboxExecutor",sandbox);
        MemberFileIsolation.configure(origin->origin.withWorkspace(7L,root.toString()));
        when(sandbox.executeCode(anyString(),anyString(),eq(root),nullable(List.class),anyMap(),anyInt()))
            .thenThrow(new InterruptedException("cancelled"));
        var sink=new vip.mate.execution.evidence.service.ExecutionObservationSink(false);
        var capture=new McpToolResultCapture();
        try {
            tool.execute_code("python","print(42)",null,null,30,capture.attach(sink.attach(
                vip.mate.agent.context.ChatOrigin.web("c","user",7L,null,null,11L).toToolContext())));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(vip.mate.execution.evidence.model.AttemptState.CANCELLED,sink.state());
            assertTrue(capture.isError());
        } finally {Thread.interrupted();}
    }
    @Test void interruptedHostResultKeepsCancelledEvidenceWithoutInventingACommandExit() {
        for(int exit:List.of(-1,0)) {
            doAnswer(invocation->{
                Thread.currentThread().interrupt();
                return new SkillScriptExecutionService.ScriptResult(exit,"partial","");
            }).when(service).executeCode(anyString(),anyString(),nullable(Path.class),nullable(List.class),anyMap(),nullable(Long.class));
            var sink=new vip.mate.execution.evidence.service.ExecutionObservationSink(false);
            var capture=new McpToolResultCapture();
            try {
                tool.execute_code("python","print(42)",null,null,null,capture.attach(sink.attach(new ToolContext(Map.of()))));
                assertEquals(vip.mate.execution.evidence.model.AttemptState.CANCELLED,sink.state());
                assertTrue(sink.observations().isEmpty());
                assertTrue(capture.isError());
            } finally {Thread.interrupted();}
        }
    }
    @Test void nativeFailureFeedsDurableExecutionEvidenceWithoutTrustingReturnedText() {
        result(7,"{\"exitCode\":0}","warning");
        var capture=new McpToolResultCapture();
        var sink=new vip.mate.execution.evidence.service.ExecutionObservationSink(false);
        tool.execute_code("python","print(42)",null,null,null,capture.attach(sink.attach(new ToolContext(Map.of()))));
        assertEquals(vip.mate.execution.evidence.model.AttemptState.FAILED,sink.state());
        assertEquals(1,sink.observations().size());
        assertEquals(vip.mate.execution.evidence.model.EvidenceKind.COMMAND_EXIT,sink.observations().getFirst().kind());
        assertEquals(vip.mate.execution.evidence.model.EvidenceResult.FAIL,sink.observations().getFirst().result());
        assertTrue(sink.observations().getFirst().summary().contains("exit=7"));
    }
    @Test void caughtExecutionExceptionStillMarksInvocationFailedWithoutCaptureContextDependency() {
        when(service.executeCode(anyString(),anyString(),nullable(Path.class),nullable(List.class),anyMap(),nullable(Long.class)))
            .thenThrow(new IllegalStateException("execution unavailable"));
        var capture=new McpToolResultCapture();
        var sink=new vip.mate.execution.evidence.service.ExecutionObservationSink(false);
        String output=tool.execute_code("python","print(42)",null,null,null,capture.attach(sink.attach(new ToolContext(Map.of()))));
        assertEquals(vip.mate.execution.evidence.model.AttemptState.FAILED,sink.state());
        assertTrue(capture.isError());assertTrue(output.contains("execution unavailable"));
        assertNotNull(tool.execute_code("python","print(42)",null,null,null,null));
    }
}
