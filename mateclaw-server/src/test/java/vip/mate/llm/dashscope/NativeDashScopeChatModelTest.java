package vip.mate.llm.dashscope;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class NativeDashScopeChatModelTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private NativeDashScopeChatModel model;
    private String response;
    private String contentType = "application/json";
    private int status = 200;
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> streamHeader = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    request.set(mapper.readTree(exchange.getRequestBody()));
                    path.set(exchange.getRequestURI().getPath());
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    streamHeader.set(exchange.getRequestHeaders().getFirst("X-DashScope-SSE"));
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        NativeDashScopeTransport transport =
                new NativeDashScopeTransport(
                        "test-key",
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        HttpClient.newHttpClient(),
                        Duration.ofSeconds(3));
        model =
                new NativeDashScopeChatModel(
                        transport,
                        NativeDashScopeChatOptions.builder()
                                .model("qwen-plus")
                                .temperature(0.3)
                                .maxTokens(100)
                                .enableSearch(true)
                                .searchOptions(
                                        Map.of("enable_source", true, "enable_citation", true))
                                .build());
        response =
                """
{"request_id":"req-1","output":{"choices":[{"finish_reason":"stop","message":
{"role":"assistant","content":"Hello","reasoning_content":"Reason"}}],
"search_info":{"search_results":[{"title":"Source","url":"https://example.test"}]}},
"usage":{"input_tokens":20,"output_tokens":5,"total_tokens":25,
"prompt_tokens_details":{"cached_tokens":12}}}
""";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void mapsNativeResponseAndRuntimeOptions() {
        ChatResponse result =
                model.call(
                        new Prompt(
                                List.of(new SystemMessage("Rules"), new UserMessage("Hi")),
                                ChatOptions.builder().maxTokens(50).temperature(0.7).build()));
        assertEquals(1, result.getResults().size());
        assertEquals("Hello", result.getResult().getOutput().getText());
        assertEquals(
                "Reason", result.getResult().getOutput().getMetadata().get("reasoningContent"));
        assertInstanceOf(
                Map.class, result.getResult().getOutput().getMetadata().get("search_info"));
        assertEquals("req-1", result.getMetadata().getId());
        assertEquals("qwen-plus", result.getMetadata().getModel());
        assertEquals(25, result.getMetadata().getUsage().getTotalTokens());
        assertEquals(12L, result.getMetadata().getUsage().getCacheReadInputTokens());
        assertEquals("/api/v1/services/aigc/text-generation/generation", path.get());
        assertEquals("Bearer test-key", authorization.get());
        assertEquals(50, request.get().at("/parameters/max_tokens").asInt());
        assertEquals(0.7, request.get().at("/parameters/temperature").asDouble());
        assertTrue(request.get().at("/parameters/enable_search").asBoolean());
        assertEquals("message", request.get().at("/parameters/result_format").asText());
    }

    @Test
    void fragmentedParallelToolCallsAreEmittedOnceWithoutExecution() {
        contentType = "text/event-stream";
        response =
                """
data: {"request_id":"r","output":{"choices":[{"index":0,"message":{"role":"assistant","reasoning_content":"Think","tool_calls":[{"index":0,"id":"a","type":"function","function":{"name":"echo","arguments":"{\\"x\\":"}},{"index":1,"id":"b","type":"function","function":{"name":"echo","arguments":"{\\"x\\":"}}]}}]}}

data: {"request_id":"r","output":{"choices":[{"index":0,"message":{"tool_calls":[{"index":1,"function":{"arguments":"2}"}},{"index":0,"function":{"arguments":"1}"}}]}}]}}

data: {"request_id":"r","output":{"choices":[{"index":0,"finish_reason":"tool_calls","message":{"content":""}}]},"usage":{"input_tokens":10,"output_tokens":4,"total_tokens":14}}

data: [DONE]

""";
        AtomicInteger executions = new AtomicInteger();
        ToolCallback callback =
                new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("echo")
                                .description("Echo")
                                .inputSchema("{\"type\":\"object\"}")
                                .build();
                    }

                    @Override
                    public String call(String input) {
                        executions.incrementAndGet();
                        return input;
                    }
                };
        List<ChatResponse> chunks =
                model.stream(
                                new Prompt(
                                        "Use tools",
                                        NativeDashScopeChatOptions.builder()
                                                .toolCallbacks(callback)
                                                .build()))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        List<AssistantMessage.ToolCall> calls =
                chunks.stream()
                        .flatMap(c -> c.getResults().stream())
                        .flatMap(g -> g.getOutput().getToolCalls().stream())
                        .toList();
        assertEquals(2, calls.size());
        assertEquals("a", calls.get(0).id());
        assertEquals("{\"x\":1}", calls.get(0).arguments());
        assertEquals("b", calls.get(1).id());
        assertEquals("{\"x\":2}", calls.get(1).arguments());
        assertEquals(0, executions.get());
        assertEquals("enable", streamHeader.get());
        assertTrue(request.get().at("/parameters/incremental_output").asBoolean());
        assertEquals("echo", request.get().at("/parameters/tools/0/function/name").asText());
        assertEquals(
                "object",
                request.get().at("/parameters/tools/0/function/parameters/type").asText());
        assertTrue(
                chunks.stream()
                        .flatMap(c -> c.getResults().stream())
                        .anyMatch(
                                g ->
                                        "Think"
                                                .equals(
                                                        g.getOutput()
                                                                .getMetadata()
                                                                .get("reasoningContent"))));
    }

    @Test
    void nativeMediaUsesMultimodalEndpointAndPreservesToolHistory() {
        UserMessage user =
                UserMessage.builder()
                        .text("Describe")
                        .media(
                                new Media(
                                        MimeTypeUtils.IMAGE_PNG,
                                        URI.create("https://example.test/image.png")))
                        .build();
        AssistantMessage assistant =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "a", "function", "echo", "{}")))
                        .build();
        ToolResponseMessage tool =
                ToolResponseMessage.builder()
                        .responses(
                                List.of(
                                        new ToolResponseMessage.ToolResponse(
                                                "a", "echo", "{\"ok\":true}")))
                        .build();
        assertEquals(1, model.call(new Prompt(List.of(user, assistant, tool))).getResults().size());
        assertEquals("/api/v1/services/aigc/multimodal-generation/generation", path.get());
        assertEquals(
                "https://example.test/image.png",
                request.get().at("/input/messages/0/content/0/image").asText());
        assertEquals("Describe", request.get().at("/input/messages/0/content/1/text").asText());
        assertEquals("a", request.get().at("/input/messages/1/tool_calls/0/id").asText());
        assertEquals("a", request.get().at("/input/messages/2/tool_call_id").asText());
    }

    @Test
    void nativeErrorEnvelopeIsNotAnEmptySuccessfulResponse() {
        response =
                "{\"code\":\"InvalidParameter\",\"message\":\"unknown"
                        + " model\",\"request_id\":\"bad\"}";
        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> model.call(new Prompt("Hi")));
        assertTrue(failure.getMessage().contains("InvalidParameter"));
        assertTrue(failure.getMessage().contains("unknown model"));
    }

    @Test
    void usageOnlyTerminalChunkPreservesCumulativeCounters() {
        contentType = "text/event-stream";
        response =
                """
data: {"request_id":"r","output":{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}]},"usage":{"input_tokens":10,"output_tokens":1,"total_tokens":11}}

data: {"request_id":"r","usage":{"input_tokens":10,"output_tokens":1,"total_tokens":11}}

""";
        List<ChatResponse> chunks =
                model.stream(new Prompt("Hi")).collectList().block(Duration.ofSeconds(5));
        assertNotNull(chunks);
        assertEquals(2, chunks.size());
        assertEquals(11, chunks.getLast().getMetadata().getUsage().getTotalTokens());
        assertEquals("Hi", chunks.getFirst().getResult().getOutput().getText());
    }

    @Test
    void streamingJsonErrorEnvelopeDoesNotDisappearWithoutSseFraming() {
        response = "{\"code\":\"Throttling\",\"message\":\"rate limited\"}";
        RuntimeException failure =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                model.stream(new Prompt("Hi"))
                                        .collectList()
                                        .block(Duration.ofSeconds(5)));
        assertTrue(failure.getMessage().contains("Throttling"));
    }

    @Test
    void truncatedToolArgumentsAreRejectedBeforeExecution() {
        contentType = "text/event-stream";
        response =
                "data:"
                    + " {\"request_id\":\"r\",\"output\":{\"choices\":[{\"message\":{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"echo\",\"arguments\":\"{\"}}]}}]}}\n\n";
        RuntimeException failure =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                model.stream(new Prompt("Hi"))
                                        .collectList()
                                        .block(Duration.ofSeconds(5)));
        assertTrue(failure.getMessage().contains("completion marker"));
    }

    @Test
    void nativeCacheWriteCountersRemainAvailable() {
        response =
                "{\"output\":{\"choices\":[{\"message\":{\"content\":\"Hi\"}}]},\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"prompt_tokens_details\":{\"cache_creation_input_tokens\":8}}}";
        assertEquals(
                8L,
                model.call(new Prompt("Hi")).getMetadata().getUsage().getCacheWriteInputTokens());
    }

    @Test
    void directVideoFileUsesNativeStringValue() {
        UserMessage user =
                UserMessage.builder()
                        .text("Describe video")
                        .media(
                                new Media(
                                        MimeType.valueOf("video/mp4"),
                                        URI.create("https://example.test/clip.mp4")))
                        .build();
        model.call(new Prompt(user));
        JsonNode video = request.get().at("/input/messages/0/content/0/video");
        assertTrue(video.isTextual());
        assertEquals("https://example.test/clip.mp4", video.asText());
    }

    @Test
    void videoFrameImagesUseNativeArrayValue() {
        UserMessage user =
                UserMessage.builder()
                        .text("Describe frames")
                        .metadata(Map.of("messageFormat", "VIDEO"))
                        .media(
                                new Media(
                                        MimeTypeUtils.IMAGE_PNG,
                                        URI.create("https://example.test/frame1.png")),
                                new Media(
                                        MimeTypeUtils.IMAGE_PNG,
                                        URI.create("https://example.test/frame2.png")))
                        .build();
        model.call(new Prompt(user));
        JsonNode video = request.get().at("/input/messages/0/content/0/video");
        assertTrue(video.isArray());
        assertEquals(2, video.size());
        assertEquals("https://example.test/frame1.png", video.get(0).asText());
    }

    @Test
    void unsupportedGenericFrequencyPenaltyIsNotSentAsRepetitionPenalty() {
        model.call(new Prompt("Hi", ChatOptions.builder().frequencyPenalty(0.9).build()));
        assertFalse(request.get().path("parameters").has("frequency_penalty"));
        assertFalse(request.get().path("parameters").has("repetition_penalty"));
    }

    @Test
    void successfulEmptyCodeFieldIsAccepted() {
        response = "{\"code\":\"\",\"output\":{\"choices\":[{\"message\":{\"content\":\"Hi\"}}]}}";
        assertDoesNotThrow(() -> model.call(new Prompt("Hi")));
    }

    @Test
    void synchronousUsageOnlyEnvelopeIsNotSuccessfulCompletion() {
        response =
                "{\"request_id\":\"missing\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0,\"total_tokens\":10}}";
        assertThrows(NativeDashScopeException.class, () -> model.call(new Prompt("Hi")));
    }
}
