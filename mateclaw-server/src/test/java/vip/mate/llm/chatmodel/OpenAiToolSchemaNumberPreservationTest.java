package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import vip.mate.config.JacksonConfig;
import vip.mate.llm.model.ModelProviderEntity;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiToolSchemaNumberPreservationTest {
    private static final String MAX_SAFE_INTEGER = "9007199254740991";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void preservesLongSchemaBoundsAsJsonNumbersWithApplicationMapper() throws Exception {
        String schema = """
                {"type":"object","properties":{"requestId":{"type":"integer",
                 "minimum":-9007199254740991,"maximum":9007199254740991,"examples":[9007199254740991]}}}
                """;
        Map<String, Object> original = JSON.readValue(schema, new TypeReference<>() { });
        ObjectMapper mapper = applicationMapper();
        JsonNode polluted = mapper.readTree(mapper.writeValueAsString(original));
        assertTrue(polluted.at("/properties/requestId/maximum").isTextual(),
                "the application serializer fixture must reproduce Long-to-string pollution");
        assertInstanceOf(Long.class, ((Map<?, ?>) ((Map<?, ?>) original.get("properties")).get("requestId")).get("maximum"));

        AtomicReference<JsonNode> wire = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            wire.set(JSON.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {"id":"r","object":"chat.completion","created":1,"model":"deepseek-chat",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ToolCallback callback = new ToolCallback() {
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name("browser_network_requests")
                            .description("Inspect browser network requests").inputSchema(schema).build();
                }
                public String call(String arguments) { throw new AssertionError("Model must not execute tool"); }
            };
            var options = OpenAiChatOptions.builder().baseUrl(base).apiKey("").model("deepseek-chat")
                    .maxRetries(0).timeout(Duration.ofSeconds(3)).strict(true).toolCallbacks(callback).build();
            var model = OpenAiChatModel.builder().options(options).httpClientBuilderCustomizer(builder ->
                    builder.interceptor(new OpenAiCompatibleHttpInterceptor(provider(), base + "/chat/completions",
                            Map.of(), false, Duration.ofSeconds(3)))).build();
            assertEquals("ok", model.call(new Prompt("Inspect")).getResult().getOutput().getText());
            JsonNode property = wire.get().at("/tools/0/function/parameters/properties/requestId");
            assertTrue(property.get("minimum").isIntegralNumber());
            assertTrue(property.get("maximum").isIntegralNumber());
            assertTrue(property.at("/examples/0").isIntegralNumber());
            assertEquals(MAX_SAFE_INTEGER, property.get("maximum").asText());
            assertEquals("-" + MAX_SAFE_INTEGER, property.get("minimum").asText());
            assertEquals("browser_network_requests", wire.get().at("/tools/0/function/name").asText());
            assertTrue(wire.get().at("/tools/0/function/strict").asBoolean());
            assertTrue(mapper.readTree(mapper.writeValueAsString(wire.get())).at("/tools/0/function/parameters/properties/requestId/maximum").isIntegralNumber());
        } finally { server.stop(0); }
    }

    @Test
    void preservesRequestContentWhenSchemaContainsNoLongs() throws Exception {
        ObjectNode request = (ObjectNode) JSON.readTree("""
                {"model":"deepseek-chat","messages":[],"tools":[{"type":"function","function":{
                "name":"browser_network_requests","strict":true,"parameters":{"type":"object",
                "properties":{"limit":{"type":"integer","maximum":100}}}}}]}
                """);
        ObjectNode rewritten = OpenAiRequestRewriter.rewrite(request, provider(), false);
        assertEquals(request, rewritten);
        assertNotSame(request, rewritten);
        assertTrue(rewritten.at("/tools/0/function/parameters/properties/limit/maximum").isIntegralNumber());
    }

    @Test
    void preservesRequestContentWhenNoToolsArePresent() {
        ObjectNode request = JSON.createObjectNode().put("model", "deepseek-chat");
        request.putArray("messages");
        assertEquals(request, OpenAiRequestRewriter.rewrite(request, provider(), false));
    }

    private static ModelProviderEntity provider() {
        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setProviderId("deepseek");
        return provider;
    }

    private static ObjectMapper applicationMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        return builder.build();
    }
}
