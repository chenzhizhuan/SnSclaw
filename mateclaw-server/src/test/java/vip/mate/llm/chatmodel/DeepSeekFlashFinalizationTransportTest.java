package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.guard.TaskFinalizationCoordinator;
import vip.mate.agent.graph.guard.TaskFinalizationGate;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.graph.state.MateClawStateKeys;
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

/** Reproduces #672 when the current Flash model name bypasses thinking controls. */
class DeepSeekFlashFinalizationTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void verifierDisablesFlashThinkingWithoutChangingRunThinkingOrReplayingFailures() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            requests.add(request);
            boolean disabled = "disabled".equals(request.path("thinking").path("type").asText());
            // DeepSeek defaults to thinking enabled. Increasing max_tokens alone
            // cannot ensure that a short verifier verdict will fit.
            Map<String, String> delta = disabled
                    ? Map.of("content", "{\"decision\":\"ACCEPT\",\"taskComplete\":true,\"remainingWork\":false,"
                            + "\"failedCallCount\":5,\"recoveryClaims\":[],\"reason\":\"The answer accurately reports all five failures.\"}")
                    : Map.of("reasoning_content", "Reviewing failed operations");
            String event = JSON.writeValueAsString(Map.of("id", "flash-fixture", "object", "chat.completion.chunk",
                    "created", 1, "model", "deepseek-flash", "choices", List.of(Map.of("index", 0,
                            "delta", delta, "finish_reason", disabled ? "stop" : "length"))));
            byte[] body = ("data: " + event + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var service = mock(ModelProviderService.class);
            var provider = new ModelProviderEntity();
            provider.setProviderId("deepseek-fixture");
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
            config.setModelName("deepseek-flash");
            config.setMaxTokens(8192);
            var model = builder.build(config, provider, RetryTemplate.builder().maxAttempts(1).build());
            var tracker = mock(ChatStreamTracker.class);
            var helper = new NodeStreamingChatHelper(tracker);
            var coordinator = new TaskFinalizationCoordinator(helper, tracker);
            var ledger = ActionExecutionLedger.fromEvents(java.util.stream.IntStream.range(0, 5)
                    .mapToObj(i -> GraphEventPublisher.toolComplete("failed-" + i, "execute_code",
                            "Execution failed: invalid code", false)).toList());
            var state = new RunState();
            state.replace(MateClawStateKeys.REQUESTED_THINKING_LEVEL, "high");
            ThinkingLevelHolder.set("high");
            var review = coordinator.review(model, "issue-672-flash", "Report the outcomes of five failed calls",
                    "All five calls failed; no successful execution or recovery was observed.", ledger,
                    "", 16000, 0, 8, 5, true, state);
            assertEquals(TaskFinalizationGate.Outcome.ACCEPT, review.result().outcome());
            assertEquals(1, review.calls(), "Disabling thinking should avoid verifier budget retries");
            assertEquals(1, requests.size());
            var verifier = requests.get(0);
            assertEquals("deepseek-flash", verifier.path("model").asText());
            assertEquals("disabled", verifier.path("thinking").path("type").asText());
            assertFalse(verifier.has("reasoning_effort"));
            assertTrue(verifier.path("tools").isEmpty());
            assertEquals(1024, verifier.path("max_tokens").asInt());
            assertEquals("high", ThinkingLevelHolder.get());
            assertEquals("high", state.value(MateClawStateKeys.REQUESTED_THINKING_LEVEL, String.class).orElseThrow());

            model.stream(new Prompt(List.of(new UserMessage("hello")))).collectList().block();
            assertEquals("enabled", requests.get(1).path("thinking").path("type").asText());
        } finally {
            ThinkingLevelHolder.clear();
            server.stop(0);
        }
    }
}
