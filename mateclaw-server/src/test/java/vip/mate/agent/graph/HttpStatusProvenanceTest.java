package vip.mate.agent.graph;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.HttpHeaders;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.failover.*;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpStatusProvenanceTest {
    private String classify(Throwable error) throws Exception {
        Method method=NodeStreamingChatHelper.class.getDeclaredMethod("classifyError",Throwable.class);
        method.setAccessible(true); return method.invoke(null,error).toString();
    }
    private Throwable serviceBusy(String requestId) {
        return new RuntimeException("wrapper",new RestClientResponseException("request_id="+requestId+" service busy",503,
                "Service Unavailable",HttpHeaders.EMPTY,"busy".getBytes(),null));
    }
    @Test void structured503IgnoresStatusLikeRequestIdDigits() throws Exception {
        for(String id:List.of("985d183d-4faf-4018-9214-942bd9449240","4299","5299","4028","5000","4008"))
            assertEquals("SERVER_ERROR",classify(serviceBusy(id)),id);
    }
    @Test void incidentalNumbersAreNotUnstructuredHttpStatuses() throws Exception {
        for(String id:List.of("4018","4299","5299","4028","5000","5028","5038","5048","4008"))
            assertEquals("UNKNOWN",classify(new RuntimeException("request_id="+id)),id);
        assertEquals("AUTH_ERROR",classify(new RuntimeException("HTTP 401")));
        assertEquals("RATE_LIMIT",classify(new RuntimeException("429 rate limited")));
        assertEquals("SERVER_ERROR",classify(new RuntimeException("HTTP/1.1 503")));
    }
    @Test void structured503DoesNotRemoveHealthyProviderAsAuthenticationFailure() {
        var model=mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(serviceBusy("985d183d-4faf-4018-9214-942bd9449240")));
        var pool=new AvailableProviderPool();pool.add("deepseek");
        var tracker=mock(ChatStreamTracker.class);
        var helper=new NodeStreamingChatHelper(tracker,List.of(),null,
                new ProviderHealthTracker(new ProviderHealthProperties()),"deepseek",pool);
        helper.setRetryTimingForTest(1,1,1000);
        var result=helper.streamCall(model,new Prompt("goal"),"status","test");
        assertEquals(NodeStreamingChatHelper.ErrorType.SERVER_ERROR,result.errorType());
        assertTrue(pool.contains("deepseek"));
    }
}
