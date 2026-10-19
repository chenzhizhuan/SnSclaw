package vip.mate.agent.graph.node;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.PrefixBudgetPlan;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.channel.web.ChatStreamTracker;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/** Exercises the actual streaming helper and actual caller recovery together. */
class PayloadModelViewIntegrationTest {
    @Test void rejectedActualModelViewIsReducedLocallyBeforeSingleProviderRetry() throws Exception {
        ChatModel model = mock(ChatModel.class);
        List<Prompt> sent = new ArrayList<>();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            if (sent.size() == 1) return Flux.error(new RestClientResponseException(
                    "payload rejected", 413, "Too Large", HttpHeaders.EMPTY, null, null));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Verified final result")))));
        });
        var helper = new NodeStreamingChatHelper(mock(ChatStreamTracker.class));
        var node = new ReasoningNode(model, AgentToolSet.fromCallbacks(List.of(),List.of()),
                null,false,helper,null,null,1024,null,null,null,null);
        node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,1_000_000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
        List<Message> canonical = new ArrayList<>();
        canonical.add(new UserMessage("ORIGINAL_OBJECTIVE"));
        for(int i=0;i<20;i++) {
            String id="call-"+i;
            canonical.add(AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall(id,"function","execute_code","{\"code\":\"old\"}"))).build());
            canonical.add(ToolResponseMessage.builder().responses(List.of(
                    new ToolResponseMessage.ToolResponse(id,"execute_code","x".repeat(500)))).build());
        }
        var output=node.apply(new RunState(Map.of(CONVERSATION_ID,"payload-integration",SYSTEM_PROMPT,"MANDATORY_SKILL",
                USER_MESSAGE,"ORIGINAL_OBJECTIVE",MESSAGES,canonical,MAX_ITERATIONS,100)));
        assertEquals(2,sent.size());
        assertTrue(sent.get(1).getInstructions().size()<sent.get(0).getInstructions().size());
        String retry=sent.get(1).getInstructions().toString();
        assertTrue(retry.contains("ORIGINAL_OBJECTIVE"));
        assertTrue(retry.contains("MANDATORY_SKILL"));
        Set<String> calls=new HashSet<>(),responses=new HashSet<>();
        for(Message m:sent.get(1).getInstructions()) {
            if(m instanceof AssistantMessage a) a.getToolCalls().forEach(c->calls.add(c.id()));
            if(m instanceof ToolResponseMessage t) t.getResponses().forEach(r->responses.add(r.id()));
        }
        assertEquals(calls,responses);
        assertFalse(calls.isEmpty());
        assertEquals(41,canonical.size());
        assertEquals(2,output.get(LLM_CALL_COUNT));
        verify(model,never()).call(any(Prompt.class));
    }

    @Test void smallWindowActuallyTrimsHistoryBeforeFirstModelCall() throws Exception {
        ChatModel model=mock(ChatModel.class);
        List<Prompt> sent=new ArrayList<>();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Verified bounded history")))));
        });
        var node=new ReasoningNode(model,AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,
                new NodeStreamingChatHelper(mock(ChatStreamTracker.class)),null,null,1024,null,null,null,null);
        node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.COMPACT,0,0,0,0,0,0,0));
        List<Message> canonical=new ArrayList<>();
        canonical.add(new UserMessage("ORIGINAL_OBJECTIVE"));
        for(int i=0;i<20;i++) {
            String id="large-"+i;
            canonical.add(AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall(id,"function","execute_code","{}"))).build());
            canonical.add(ToolResponseMessage.builder().responses(List.of(
                    new ToolResponseMessage.ToolResponse(id,"execute_code","x".repeat(10000)))).build());
        }
        node.apply(new RunState(Map.of(CONVERSATION_ID,"payload-controlled-loop-trim",SYSTEM_PROMPT,"MANDATORY_SKILL",
                USER_MESSAGE,"ORIGINAL_OBJECTIVE",MESSAGES,canonical,MAX_ITERATIONS,100)));
        assertEquals(1,sent.size());
        assertTrue(sent.get(0).getInstructions().size()<canonical.size(),"actual provider view must lose old history");
        assertTrue(sent.get(0).getInstructions().toString().contains("ORIGINAL_OBJECTIVE"));
        int input=vip.mate.agent.context.TokenEstimator.estimateTokens(sent.get(0).getInstructions());
        assertTrue(input+1024<=24000,"complete model view and output reserve must fit the controlled window");
        Set<String> calls=new HashSet<>(),responses=new HashSet<>();
        for(Message m:sent.get(0).getInstructions()) {
            if(m instanceof AssistantMessage a) a.getToolCalls().forEach(c->calls.add(c.id()));
            if(m instanceof ToolResponseMessage t) t.getResponses().forEach(r->responses.add(r.id()));
        }
        assertEquals(calls,responses);
        assertEquals(41,canonical.size());
        verify(model,never()).call(any(Prompt.class));
    }
}
