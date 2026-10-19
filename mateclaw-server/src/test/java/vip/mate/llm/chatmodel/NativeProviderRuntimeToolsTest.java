package vip.mate.llm.chatmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import vip.mate.llm.dashscope.NativeDashScopeChatModel;
import vip.mate.llm.dashscope.NativeDashScopeChatOptions;
import vip.mate.llm.dashscope.NativeDashScopeTransport;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

class NativeProviderRuntimeToolsTest {
    static Stream<Arguments> cases() {
        return Stream.of("dashscope", "anthropic")
                .flatMap(
                        provider ->
                                Stream.of(false, true)
                                        .flatMap(
                                                stream ->
                                                        Stream.of(
                                                                        "absent",
                                                                        "typed-null",
                                                                        "generic-null",
                                                                        "typed-empty",
                                                                        "generic-empty",
                                                                        "typed-replace",
                                                                        "generic-replace")
                                                                .map(
                                                                        mode ->
                                                                                Arguments.of(
                                                                                        provider,
                                                                                        stream,
                                                                                        mode))));
    }

    @ParameterizedTest(name = "{0} stream={1} callbacks={2}")
    @MethodSource("cases")
    void runtimeCallbacksReplaceOnlyWhenExplicit(String provider, boolean stream, String mode)
            throws Exception {
        var captured = new AtomicReference<JsonNode>();
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    captured.set(json.readTree(exchange.getRequestBody()));
                    String nativeJson =
                            "{\"request_id\":\"fixture\",\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
                    String anthropicJson =
                            "{\"id\":\"fixture\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
                    String response = provider.equals("dashscope") ? nativeJson : anthropicJson;
                    if (stream && provider.equals("dashscope"))
                        response = "data: " + nativeJson + "\n\ndata: [DONE]\n\n";
                    if (stream && provider.equals("anthropic"))
                        response =
                                "event: message_start\n"
                                    + "data:"
                                    + " {\"type\":\"message_start\",\"message\":{\"id\":\"fixture\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n"
                                    + "event: content_block_start\n"
                                    + "data:"
                                    + " {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                                    + "event: content_block_delta\n"
                                    + "data:"
                                    + " {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                                    + "event: content_block_stop\n"
                                    + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                                    + "event: message_delta\n"
                                    + "data:"
                                    + " {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}\n\n"
                                    + "event: message_stop\n"
                                    + "data: {\"type\":\"message_stop\"}\n\n";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .set("Content-Type", stream ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ToolCallback defaults = callback("default_tool");
            ToolCallback replacement = callback("replacement_tool");
            ChatModel model;
            ChatOptions runtime = null;
            List<ToolCallback> callbacks =
                    mode.endsWith("empty")
                            ? List.of()
                            : mode.endsWith("replace") ? List.of(replacement) : null;
            if (provider.equals("dashscope")) {
                model =
                        new NativeDashScopeChatModel(
                                new NativeDashScopeTransport(
                                        "dummy", base, client, Duration.ofSeconds(3)),
                                NativeDashScopeChatOptions.builder()
                                        .model("qwen-test")
                                        .toolCallbacks(defaults)
                                        .build());
                if (mode.startsWith("typed"))
                    runtime = NativeDashScopeChatOptions.builder().toolCallbacks(callbacks).build();
            } else {
                model =
                        AnthropicSdkChatModel.create(
                                AnthropicChatOptions.builder()
                                        .model("claude-test")
                                        .maxTokens(100)
                                        .apiKey("dummy")
                                        .baseUrl(base)
                                        .timeout(Duration.ofSeconds(3))
                                        .toolCallbacks(defaults)
                                        .build(),
                                ObservationRegistry.NOOP,
                                Map.of(),
                                false);
                if (mode.startsWith("typed"))
                    runtime =
                            AnthropicChatOptions.builder()
                                    .model("claude-test")
                                    .maxTokens(100)
                                    .toolCallbacks(callbacks)
                                    .build();
            }
            if (mode.startsWith("generic"))
                runtime = ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
            Prompt prompt = new Prompt("hello", runtime);
            if (stream)
                assertNotNull(model.stream(prompt).collectList().block(Duration.ofSeconds(5)));
            else assertNotNull(model.call(prompt));
            JsonNode tools =
                    provider.equals("dashscope")
                            ? captured.get().path("parameters").path("tools")
                            : captured.get().path("tools");
            var names = new ArrayList<String>();
            tools.forEach(
                    tool ->
                            names.add(
                                    provider.equals("dashscope")
                                            ? tool.path("function").path("name").asText()
                                            : tool.path("name").asText()));
            List<String> expected =
                    mode.endsWith("empty")
                            ? List.of()
                            : mode.endsWith("replace")
                                    ? List.of("replacement_tool")
                                    : List.of("default_tool");
            assertEquals(expected, names);
        } finally {
            server.stop(0);
        }
    }

    private static ToolCallback callback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("Fixture")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            @Override
            public String call(String input) {
                throw new AssertionError("Fixture must not execute tools");
            }
        };
    }
}
