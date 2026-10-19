package vip.mate.llm.chatmodel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import vip.mate.llm.dashscope.NativeDashScopeConnectionProperties;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class DashScopeNativeFactoryTest {
    @Test
    void createsWithoutAutoConfiguredModelAndPreservesProviderCredentialPrecedence()
            throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        HttpServer server = server(authorization, payload);
        try {
            var properties = new NativeDashScopeConnectionProperties();
            properties.setApiKey("env-key");
            properties.setBaseUrl(url(server));
            var service =
                    service(
                            Map.of(
                                    "temperature",
                                    0.1,
                                    "max_tokens",
                                    90,
                                    "top_p",
                                    0.4,
                                    "enable_thinking",
                                    false,
                                    "search_strategy",
                                    "turbo",
                                    "stop",
                                    List.of("END")));
            var builder = new DashScopeChatModelBuilder(properties, service);
            var model = model();
            model.setTemperature(0.6);
            model.setMaxTokens(40);
            model.setEnableSearch(false);
            var provider = new ModelProviderEntity();
            provider.setApiKey("provider-key");
            provider.setBaseUrl(url(server) + "/compatible-mode/v1");
            assertEquals(
                    "Hi",
                    builder.build(model, provider, null)
                            .call(new Prompt("hi"))
                            .getResult()
                            .getOutput()
                            .getText());
            assertEquals("Bearer provider-key", authorization.get());
            assertEquals(0.6, payload.get().at("/parameters/temperature").asDouble());
            assertEquals(40, payload.get().at("/parameters/max_tokens").asInt());
            assertEquals(0.4, payload.get().at("/parameters/top_p").asDouble());
            assertFalse(payload.get().at("/parameters/enable_thinking").asBoolean(true));
            assertFalse(payload.get().at("/parameters/enable_search").asBoolean(true));
            assertEquals("END", payload.get().at("/parameters/stop/0").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidProviderKeyFallsBackToConfiguredKey() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = server(auth, new AtomicReference<>());
        try {
            var properties = new NativeDashScopeConnectionProperties();
            properties.setApiKey("env-key");
            properties.setBaseUrl(url(server));
            var provider = new ModelProviderEntity();
            provider.setApiKey("placeholder");
            new DashScopeChatModelBuilder(properties, service(Map.of()))
                    .build(model(), provider, null)
                    .call(new Prompt("hi"));
            assertEquals("Bearer env-key", auth.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitSearchAndThinkingFalseOverrideProviderTrue() {
        var properties = new NativeDashScopeConnectionProperties();
        properties.setApiKey("env-key");
        var builder =
                new DashScopeChatModelBuilder(
                        properties,
                        service(
                                Map.of(
                                        "enable_search",
                                        true,
                                        "enable_thinking",
                                        false,
                                        "thinking_budget",
                                        300)));
        var model = model();
        model.setEnableSearch(false);
        var options = builder.buildDashScopeOptions(model, new ModelProviderEntity());
        assertEquals(false, options.getNativeParameters().get("enable_search"));
        assertEquals(false, options.getNativeParameters().get("enable_thinking"));
        assertEquals(300, options.getNativeParameters().get("thinking_budget"));
    }

    @Test
    void configuredYamlDefaultsBindAndFillMissingModelValues() {
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "spring.ai.dashscope.api-key",
                                "env-key",
                                "spring.ai.dashscope.chat.options.model",
                                "qwen-turbo",
                                "spring.ai.dashscope.chat.options.temperature",
                                "0.2",
                                "spring.ai.dashscope.chat.options.max-tokens",
                                "70"));
        var properties =
                new Binder(source)
                        .bind(
                                "spring.ai.dashscope",
                                Bindable.of(NativeDashScopeConnectionProperties.class))
                        .get();
        var builder = new DashScopeChatModelBuilder(properties, service(Map.of()));
        var options =
                builder.buildDashScopeOptions(new ModelConfigEntity(), new ModelProviderEntity());
        assertEquals("qwen-turbo", options.getModel());
        assertEquals(0.2, options.getTemperature());
        assertEquals(70, options.getMaxTokens());
        assertEquals("env-key", properties.getApiKey());
    }

    @Test
    void modelSearchStrategyOverridesProviderStrategyAndRetainsSourceMetadata() {
        var properties = new NativeDashScopeConnectionProperties();
        var builder =
                new DashScopeChatModelBuilder(
                        properties,
                        service(Map.of("enableSearch", true, "searchStrategy", "turbo")));
        var model = model();
        model.setSearchStrategy("pro");
        var options = builder.buildDashScopeOptions(model, new ModelProviderEntity());
        assertEquals(
                Map.of("search_strategy", "pro", "enable_source", true, "enable_citation", true),
                options.getNativeParameters().get("search_options"));
    }

    private ModelConfigEntity model() {
        var model = new ModelConfigEntity();
        model.setModelName("qwen-plus");
        return model;
    }

    private ModelProviderService service(Map<String, Object> kwargs) {
        var service = mock(ModelProviderService.class);
        when(service.hasUsableApiKey(any()))
                .thenAnswer(
                        call ->
                                call.getArgument(0) instanceof String key
                                        && !key.isBlank()
                                        && !key.equals("placeholder"));
        when(service.readProviderGenerateKwargs(any())).thenReturn(kwargs);
        return service;
    }

    private String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private HttpServer server(AtomicReference<String> auth, AtomicReference<JsonNode> payload)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    payload.set(new ObjectMapper().readTree(exchange.getRequestBody()));
                    byte[] body =
                            "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Hi\"}}]}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        return server;
    }
}
