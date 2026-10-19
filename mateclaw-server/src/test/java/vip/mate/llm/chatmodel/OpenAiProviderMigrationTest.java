package vip.mate.llm.chatmodel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.retry.support.RetryTemplate;

import reactor.core.publisher.Flux;

import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class OpenAiProviderMigrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void cleanup() {
        if (server != null) server.stop(0);
        ThinkingLevelHolder.clear();
        AssistantThinkingRelay.clearAll();
        ReasoningContentCache.clear();
    }

    ModelConfigEntity model(String name) {
        var m = new ModelConfigEntity();
        m.setModelName(name);
        return m;
    }

    ModelProviderEntity provider(String id, String url) {
        var p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setBaseUrl(url);
        p.setRequireApiKey(false);
        return p;
    }

    OpenAiCompatibleChatModelBuilder builder(ModelProviderEntity p, Map<String, Object> kwargs) {
        var service = mock(ModelProviderService.class);
        when(service.isProviderConfigured(p.getProviderId())).thenReturn(true);
        when(service.readProviderGenerateKwargs(p)).thenReturn(kwargs);
        return new OpenAiCompatibleChatModelBuilder(service, null, null, null);
    }

    @Test
    void immutableOptionsPreserveModelFamilyAndProviderPassthrough() {
        var p = provider("openai", "http://127.0.0.1");
        var m = model("gpt-5");
        m.setTemperature(.3);
        m.setTopP(.9);
        m.setMaxTokens(222);
        var options =
                builder(
                                p,
                                Map.of(
                                        "reasoningEffort",
                                        "high",
                                        "vendor_extension",
                                        Map.of("setting", true)))
                        .buildOpenAiOptions(m, p);
        assertEquals(222, options.getMaxCompletionTokens());
        assertNull(options.getMaxTokens());
        assertNull(options.getTopP());
        assertEquals("high", options.getReasoningEffort());
        assertEquals(Map.of("vendor_extension", Map.of("setting", true)), options.getExtraBody());
        assertTrue(options.getStreamOptions().includeUsage());
    }

    @Test
    void deepSeekAndVllmDecoratorsRetainDefaultsAndDoNotMutateOptions() {
        var defaults =
                OpenAiChatOptions.builder()
                        .model("alias")
                        .temperature(.4)
                        .reasoningEffort("high")
                        .extraBody(
                                Map.of(
                                        "vendor",
                                        true,
                                        "chat_template_kwargs",
                                        Map.of("template_default", true)))
                        .build();
        AtomicReference<Prompt> seen = new AtomicReference<>();
        ChatModel fake =
                new ChatModel() {
                    public ChatOptions getOptions() {
                        return defaults;
                    }

                    public ChatResponse call(Prompt p) {
                        seen.set(p);
                        return new ChatResponse(
                                List.of(new Generation(new AssistantMessage("ok"))));
                    }

                    public Flux<ChatResponse> stream(Prompt p) {
                        return Flux.just(call(p));
                    }
                };
        ThinkingLevelHolder.set("off");
        new VllmThinkingDecorator(fake)
                .call(new Prompt("test", ChatOptions.builder().maxTokens(55).build()));
        var patched = (OpenAiChatOptions) seen.get().getOptions();
        assertEquals("alias", patched.getModel());
        assertEquals(.4, patched.getTemperature());
        assertEquals(55, patched.getMaxTokens());
        assertNull(patched.getReasoningEffort());
        assertEquals(
                Map.of("template_default", true, "enable_thinking", false),
                patched.getExtraBody().get("chat_template_kwargs"));
        new DeepSeekV4ThinkingDecorator(fake).call(new Prompt("test", defaults));
        var deep = (OpenAiChatOptions) seen.get().getOptions();
        assertEquals(Map.of("type", "disabled"), deep.getExtraBody().get("thinking"));
        assertNull(deep.getReasoningEffort());
        assertFalse(defaults.getExtraBody().containsKey("thinking"));
        assertEquals("high", defaults.getReasoningEffort());
        assertSame(defaults, new DeepSeekV4ThinkingDecorator(fake).getOptions());
    }

    @Test
    void finalWirePoliciesPreserveNumbersVideoSearchAndUnknownFields() throws Exception {
        ObjectNode input =
                (ObjectNode)
                        json.readTree(
                                "{\"model\":\"gpt-5\",\"reasoning_effort\":\"high\",\"tool_choice\":\"auto\",\"vendor\":true,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"tool\",\"parameters\":{\"maximum\":9007199254740991}}}],\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:video/mp4;base64,eA==\"}}]}]}");
        var output = OpenAiRequestRewriter.rewrite(input, provider("kimi-cn", "unused"), true);
        assertFalse(output.has("reasoning_effort"));
        assertFalse(output.has("tool_choice"));
        assertTrue(output.path("vendor").asBoolean());
        assertTrue(
                output.path("tools")
                        .get(0)
                        .path("function")
                        .path("parameters")
                        .path("maximum")
                        .isNumber());
        assertEquals(
                9007199254740991L,
                output.path("tools")
                        .get(0)
                        .path("function")
                        .path("parameters")
                        .path("maximum")
                        .longValue());
        assertEquals("builtin_function", output.path("tools").get(1).path("type").asText());
        assertEquals(
                "video_url",
                output.path("messages").get(0).path("content").get(0).path("type").asText());
        assertEquals(
                "data:video/mp4;base64,eA==",
                output.path("messages").get(0).path("content").get(0).path("video_url").asText());
        assertTrue(input.has("tool_choice"));
    }

    @Test
    void reasoningRelayPreservesTurnAlignmentAndNeverLeaksInternalToken() throws Exception {
        String token = AssistantThinkingRelay.stash(List.of("old", "current"), "original-user");
        ObjectNode request =
                (ObjectNode)
                        json.readTree(
                                "{\"model\":\"deepseek-reasoner\",\"user\":\""
                                        + token
                                        + "\",\"messages\":[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"old\"}]},{\"role\":\"user\",\"content\":\"new\"},{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"current\"}]}]}");
        var rewritten =
                OpenAiRequestRewriter.rewrite(request, provider("kimi-cn", "unused"), false);
        assertEquals("original-user", rewritten.path("user").asText());
        assertFalse(rewritten.path("messages").get(0).has("reasoning_content"));
        assertEquals(
                "current", rewritten.path("messages").get(2).path("reasoning_content").asText());
        assertFalse(rewritten.toString().contains(AssistantThinkingRelay.TOKEN_PREFIX));
        assertEquals(0, AssistantThinkingRelay.size());
    }

    @Test
    void actualSdkSyncAndStreamUseConfiguredPathHeadersAndKeylessAuth() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> ua = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<ObjectNode> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requests.incrementAndGet();
                    path.set(exchange.getRequestURI().getPath());
                    ua.set(exchange.getRequestHeaders().getFirst("User-Agent"));
                    auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    ObjectNode input = (ObjectNode) json.readTree(exchange.getRequestBody());
                    body.set(input);
                    String data;
                    if (input.path("stream").asBoolean()) {
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        data =
                                "data:"
                                    + " {\"id\":\"response\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"alias\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hello\",\"reasoning\":\"think\"},\"finish_reason\":null}]}\n\n"
                                    + "data:"
                                    + " {\"id\":\"response\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"alias\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                                    + "data: [DONE]\n\n";
                    } else {
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        data =
                                "{\"id\":\"response\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"alias\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}";
                    }
                    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        var p = provider("local", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3");
        var kwargs =
                Map.<String, Object>of(
                        "headers", Map.of("User-Agent", "custom-client", "X-Test", "present"));
        var chat = builder(p, kwargs).build(model("alias"), p, null);
        assertEquals("hello", chat.call(new Prompt("sync")).getResult().getOutput().getText());
        assertEquals("/api/v3/chat/completions", path.get());
        assertEquals("custom-client", ua.get());
        assertNull(auth.get());
        var responses = chat.stream(new Prompt("stream")).collectList().block();
        assertEquals(
                "hello",
                responses.stream()
                        .map(r -> r.getResult().getOutput().getText())
                        .filter(t -> t != null)
                        .reduce("", String::concat));
        assertEquals(
                "think",
                responses.getLast().getResult().getOutput().getMetadata().get("reasoningContent"));
        assertEquals(2, requests.get());
        assertTrue(body.get().path("stream_options").path("include_usage").asBoolean());
        assertNull(auth.get());
    }

    @Test
    void syncRetryRetainsRelayAndExplicitOneAttemptOverride() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> path = new AtomicReference<>();
        java.util.ArrayList<ObjectNode> bodies = new java.util.ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    path.set(exchange.getRequestURI().getPath());
                    int attempt = requests.incrementAndGet();
                    bodies.add((ObjectNode) json.readTree(exchange.getRequestBody()));
                    boolean error = attempt % 2 == 1;
                    String data =
                            error
                                    ? "{\"error\":{\"message\":\"unavailable\",\"type\":\"server_error\"}}"
                                    : "{\"id\":\"r\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"alias\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
                    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(error ? 503 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        var p = provider("deepseek", "http://127.0.0.1:" + server.getAddress().getPort());
        var chat =
                builder(p, Map.of())
                        .build(
                                model("deepseek-reasoner"),
                                p,
                                RetryTemplate.builder().maxAttempts(2).noBackoff().build());
        String token = AssistantThinkingRelay.stash(List.of("real thinking"), "original");
        var options = OpenAiChatOptions.builder().user(token).build();
        var prompt =
                new Prompt(
                        List.of(new UserMessage("question"), new AssistantMessage("prior")),
                        options);
        assertEquals("ok", chat.call(prompt).getResult().getOutput().getText());
        assertEquals(2, requests.get());
        assertEquals(bodies.getFirst(), bodies.getLast());
        assertEquals("original", bodies.getLast().path("user").asText());
        assertEquals(
                "real thinking",
                bodies.getLast().path("messages").get(1).path("reasoning_content").asText());
        assertFalse(bodies.getLast().has(OpenAiRequestRewriter.RELAY_FIELD));
        assertFalse(bodies.getLast().toString().contains(AssistantThinkingRelay.TOKEN_PREFIX));
        assertEquals(token, options.getUser());
        assertEquals(0, AssistantThinkingRelay.size());
        var one =
                builder(p, Map.of("completionsPath", "custom/completion"))
                        .build(
                                model("alias"),
                                p,
                                RetryTemplate.builder().maxAttempts(1).noBackoff().build());
        assertThrows(RuntimeException.class, () -> one.call(new Prompt("fail")));
        assertEquals(3, requests.get());
        assertEquals("/custom/completion", path.get());
    }

    @Test
    void providerReasoningPolicyPreservesExistingAndCachesOnlyPriorTurns() throws Exception {
        ReasoningContentCache.store(List.of("past"), "cached prior");
        ReasoningContentCache.store(List.of("current"), "wrong current");
        ObjectNode body =
                (ObjectNode)
                        json.readTree(
                                "{\"model\":\"deepseek-reasoner\",\"messages\":[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"past\"}]},{\"role\":\"user\",\"content\":\"new\"},{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"current\"}]},{\"role\":\"assistant\",\"reasoning_content\":\"keep\"}]}");
        var out = OpenAiRequestRewriter.rewrite(body, provider("deepseek", "unused"), false);
        assertEquals(
                "cached prior", out.path("messages").get(0).path("reasoning_content").asText());
        assertEquals(" ", out.path("messages").get(2).path("reasoning_content").asText());
        assertEquals("keep", out.path("messages").get(3).path("reasoning_content").asText());
    }

    @Test
    void immutableDecoratorRemovesBothThinkingMetadataConventions() {
        ChatModel fake =
                new ChatModel() {
                    public ChatOptions getOptions() {
                        return OpenAiChatOptions.builder().model("deepseek-v4-pro").build();
                    }

                    public ChatResponse call(Prompt p) {
                        return new ChatResponse(List.of());
                    }
                };
        var assistant =
                AssistantMessage.builder()
                        .content("text")
                        .properties(
                                Map.of(
                                        "reasoningContent",
                                        "camel",
                                        "reasoning_content",
                                        "snake",
                                        "other",
                                        "keep"))
                        .build();
        ThinkingLevelHolder.set("off");
        var transformed =
                new DeepSeekV4ThinkingDecorator(fake).transform(new Prompt(List.of(assistant)));
        var metadata = transformed.getInstructions().getFirst().getMetadata();
        assertFalse(metadata.containsKey("reasoningContent"));
        assertFalse(metadata.containsKey("reasoning_content"));
        assertEquals("keep", metadata.get("other"));
        assertEquals("camel", assistant.getMetadata().get("reasoningContent"));
    }

    @Test
    void optionalKeyAndGenericRuntimePreserveProviderDefaultsOnWire() throws Exception {
        AtomicReference<ObjectNode> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    body.set((ObjectNode) json.readTree(exchange.getRequestBody()));
                    auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] bytes =
                            "{\"id\":\"r\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"configured-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        var provider = provider("local", "http://127.0.0.1:" + server.getAddress().getPort());
        provider.setApiKey("test-only-optional-key");
        var service = mock(ModelProviderService.class);
        when(service.isProviderConfigured("local")).thenReturn(true);
        when(service.readProviderGenerateKwargs(provider)).thenReturn(Map.of("vendor", true));
        when(service.hasUsableApiKey("test-only-optional-key")).thenReturn(true);
        var model = model("configured-model");
        model.setTemperature(.4);
        model.setMaxTokens(80);
        var chat =
                new OpenAiCompatibleChatModelBuilder(service, null, null, null)
                        .build(model, provider, null);
        chat.call(new Prompt("question", ChatOptions.builder().maxTokens(55).build()));
        assertEquals("Bearer test-only-optional-key", auth.get());
        assertEquals("configured-model", body.get().path("model").asText());
        assertEquals(.4, body.get().path("temperature").asDouble());
        assertEquals(55, body.get().path("max_tokens").asInt());
        assertTrue(body.get().path("vendor").asBoolean());
        chat.call(
                new Prompt(
                        "question",
                        OpenAiChatOptions.builder()
                                .model("runtime-model")
                                .temperature(.8)
                                .build()));
        assertEquals("runtime-model", body.get().path("model").asText());
        assertEquals(.8, body.get().path("temperature").asDouble());
        assertEquals(80, body.get().path("max_tokens").asInt());
    }

    @Test
    void explicitEmptyToolCallbacksClearDefaultsAndSparseOptionsRetainThem() {
        ToolCallback callback = mock(ToolCallback.class);
        var defaults =
                OpenAiChatOptions.builder()
                        .model("default-model")
                        .toolCallbacks(List.of(callback))
                        .build();
        AtomicReference<Prompt> seen = new AtomicReference<>();
        ChatModel fake =
                new ChatModel() {
                    public ChatOptions getOptions() {
                        return defaults;
                    }

                    public ChatResponse call(Prompt prompt) {
                        seen.set(prompt);
                        return new ChatResponse(List.of());
                    }
                };
        var wrapper = new OpenAiWirePolicyChatModel(fake, null, null);
        wrapper.call(new Prompt("keep", ChatOptions.builder().maxTokens(55).build()));
        assertEquals(
                List.of(callback),
                ((OpenAiChatOptions) seen.get().getOptions()).getToolCallbacks());
        wrapper.call(
                new Prompt(
                        "summary",
                        ToolCallingChatOptions.builder().toolCallbacks(List.of()).build()));
        assertEquals(List.of(), ((OpenAiChatOptions) seen.get().getOptions()).getToolCallbacks());
        assertEquals("default-model", seen.get().getOptions().getModel());
        assertEquals(List.of(callback), defaults.getToolCallbacks());
        ThinkingLevelHolder.set("off");
        new DeepSeekV4ThinkingDecorator(wrapper)
                .call(
                        new Prompt(
                                "summary",
                                ToolCallingChatOptions.builder().toolCallbacks(List.of()).build()));
        assertEquals(List.of(), ((OpenAiChatOptions) seen.get().getOptions()).getToolCallbacks());
        new VllmThinkingDecorator(wrapper)
                .call(
                        new Prompt(
                                "summary",
                                ToolCallingChatOptions.builder().toolCallbacks(List.of()).build()));
        assertEquals(List.of(), ((OpenAiChatOptions) seen.get().getOptions()).getToolCallbacks());
    }
}
