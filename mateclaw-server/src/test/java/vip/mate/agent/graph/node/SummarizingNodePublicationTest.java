package vip.mate.agent.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.channel.web.ChatStreamTracker;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

class SummarizingNodePublicationTest {
    private RunState state() {
        return new RunState(Map.of(CONVERSATION_ID,"summary-private", USER_MESSAGE,"Finish all stages",
            OBSERVATION_HISTORY,List.of("stage 1: 42"), LLM_CALL_COUNT,3,
            ACTION_EXECUTION_LEDGER,ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("s","calculate","42",true)))));
    }
    @Test void toolBackedContextSummaryNeverEntersLiveOrPersistedAnswerAndCountsUsage() throws Exception {
        var tracker=new ChatStreamTracker(new ObjectMapper());
        try {
            tracker.register("summary-private");var capture=new Capture();tracker.attach("summary-private",capture);
            var model=mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(
                List.of(new Generation(new AssistantMessage("INTERNAL_UNVERIFIED_SUMMARY"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10,5)).build())));
            var out=new SummarizingNode(model,new NodeStreamingChatHelper(tracker),tracker).apply(state());
            tracker.broadcastObject("summary-private","flush",Map.of());
            assertFalse(capture.text().contains("INTERNAL_UNVERIFIED_SUMMARY"));
            assertFalse(capture.text().contains("content_delta"));
            assertEquals("INTERNAL_UNVERIFIED_SUMMARY",out.get(SUMMARIZED_CONTEXT));
            assertEquals("",out.get(STREAMED_CONTENT));assertEquals(false,out.get(CONTENT_STREAMED));
            assertEquals(4,out.get(LLM_CALL_COUNT));assertEquals(10,out.get(PROMPT_TOKENS));
            assertEquals(5,out.get(COMPLETION_TOKENS));
            assertTrue(String.valueOf(out.get(MESSAGES)).contains("INTERNAL_UNVERIFIED_SUMMARY"));
        } finally {tracker.onShutdown();}
    }
    @Test void cancelledOrFailedInternalSummaryDoesNotBecomeUnverifiedFinalAnswer() throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);
        for(boolean stopped:List.of(true,false)) {
            var result=new NodeStreamingChatHelper.StreamResult(stopped?"INTERNAL_PARTIAL":"","",null,List.of(),false,10,5,
                stopped,stopped?null:"transport failed",stopped?NodeStreamingChatHelper.ErrorType.NONE:NodeStreamingChatHelper.ErrorType.UNKNOWN,
                stopped,0,0,0);
            when(helper.streamCall(any(),any(),any(),any())).thenReturn(result);
            when(helper.streamCallBufferedContent(any(),any(),any(),any())).thenReturn(result);
            var out=new SummarizingNode(mock(ChatModel.class),helper).apply(state());
            assertNotEquals("INTERNAL_PARTIAL",out.get(FINAL_ANSWER));
            assertEquals("",out.get(STREAMED_CONTENT));assertEquals(false,out.get(CONTENT_STREAMED));
            assertEquals(4,out.get(LLM_CALL_COUNT));
            if(stopped) assertEquals("stopped",out.get(FINISH_REASON));
        }
    }
    static class Capture extends org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
        final List<String> chunks=new java.util.concurrent.CopyOnWriteArrayList<>();
        public void send(SseEventBuilder builder) {for(var data:builder.build())chunks.add(String.valueOf(data.getData()));}
        String text(){return String.join("",chunks);}
    }
}
