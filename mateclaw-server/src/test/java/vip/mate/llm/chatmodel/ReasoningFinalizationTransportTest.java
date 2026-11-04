package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.guard.TaskFinalizationCoordinator;
import vip.mate.agent.graph.guard.TaskFinalizationGate;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real SDK and SSE helper against a reasoning-provider protocol fixture. */
class ReasoningFinalizationTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void reasoningOnlyLengthResponseRecoversBeforePublishingVerdict() throws Exception {
        assertRecovers("");
    }

    @Test
    void lengthResponseWithTruncatedJsonRecoversBeforePublishingVerdict() throws Exception {
        assertRecovers("{\"decision\":\"ACCEPT\",\"taskComplete\":true,");
    }

    private void assertRecovers(String exhaustedContent) throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            requests.add(request);
            boolean exhausted = request.path("max_tokens").asInt() <= 1024;
            String verdict = "{\"decision\":\"ACCEPT\",\"taskComplete\":true,\"remainingWork\":false,"
                    + "\"failedCallCount\":0,\"recoveryClaims\":[],\"reason\":\"Forecast matches tool evidence\"}";
            String content = exhausted ? exhaustedContent : verdict;
            // SenseNova documents reasoning (rather than reasoning_content) and
            // an empty finish_reason on intermediate chunks. max_tokens includes reasoning.
            String events = "data: " + chunk(Map.of("reasoning", "Checking the fifteen daily forecasts"), "")
                    + "\n\ndata: " + chunk(Map.of("content", content), exhausted ? "length" : "stop")
                    + "\n\ndata: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                    + "\"model\":\"sensenova-6.8-flash-lite\",\"choices\":[],\"usage\":{\"prompt_tokens\":200,"
                    + "\"completion_tokens\":" + (exhausted ? 1024 : 1400)
                    + ",\"total_tokens\":" + (exhausted ? 1224 : 1600) + "}}\n\ndata: [DONE]\n\n";
            byte[] body = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var service = mock(ModelProviderService.class);
            var provider = new ModelProviderEntity();
            provider.setProviderId("sensenova-fixture");
            provider.setRequireApiKey(false);
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            when(service.isProviderConfigured(provider.getProviderId())).thenReturn(true);
            when(service.readProviderGenerateKwargs(provider)).thenReturn(Map.of());
            var beans = new StaticListableBeanFactory();
            beans.addBean("observations", ObservationRegistry.NOOP);
            var builder = new OpenAiCompatibleChatModelBuilder(service,
                    beans.getBeanProvider(RestClient.Builder.class), beans.getBeanProvider(WebClient.Builder.class),
                    beans.getBeanProvider(ObservationRegistry.class));
            var config = new ModelConfigEntity();
            config.setModelName("sensenova-6.8-flash-lite");
            config.setMaxTokens(8192);
            var model = builder.build(config, provider, RetryTemplate.builder().maxAttempts(1).build());
            var tracker = mock(ChatStreamTracker.class);
            var helper = new NodeStreamingChatHelper(tracker);
            var coordinator = new TaskFinalizationCoordinator(helper, tracker);
            var ledger = ActionExecutionLedger.fromEvents(List.of(
                    GraphEventPublisher.toolComplete("forecast", "execute_code", "15 days of Shanghai forecast data", true)));
            var result = coordinator.review(model, "test-672", "上海未来15天天气", "Forecast summary", ledger,
                    "", 16000, 0, 8, 3, true, new RunState());
            assertEquals(TaskFinalizationGate.Outcome.ACCEPT, result.result().outcome());
            assertEquals(2, result.calls());
            assertEquals(2, requests.size());
            assertEquals(1024, requests.get(0).path("max_tokens").asInt());
            assertEquals(8192, requests.get(1).path("max_tokens").asInt());
            assertEquals(requests.get(0).path("messages"), requests.get(1).path("messages"));
            assertTrue(requests.stream().allMatch(request -> request.path("tools").isEmpty()));
            assertNull(ThinkingLevelHolder.get());
        } finally {
            ThinkingLevelHolder.clear();
            server.stop(0);
        }
    }

    private static String chunk(Map<String, String> delta, String finish) throws java.io.IOException {
        return JSON.writeValueAsString(Map.of("id", "fixture", "object", "chat.completion.chunk", "created", 1,
                "model", "sensenova-6.8-flash-lite", "choices", List.of(Map.of("index", 0, "delta", delta,
                        "finish_reason", finish))));
    }
}
