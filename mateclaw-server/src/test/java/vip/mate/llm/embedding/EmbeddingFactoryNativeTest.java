package vip.mate.llm.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import vip.mate.llm.dashscope.NativeDashScopeConnectionProperties;
import vip.mate.llm.dashscope.NativeDashScopeEmbeddingModel;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class EmbeddingFactoryNativeTest {
    @Test
    void nativeCredentialsFallbackAndCacheEvictionReachVerifiedAdapter() {
        var service = mock(ModelProviderService.class);
        var provider = new ModelProviderEntity();
        provider.setProviderId("dashscope");
        provider.setChatModel("DashScopeChatModel");
        provider.setApiKey("placeholder");
        when(service.getProviderConfig("dashscope")).thenReturn(provider);
        when(service.hasUsableApiKey(any()))
                .thenAnswer(call -> "env-key".equals(call.getArgument(0)));
        when(service.readProviderGenerateKwargs(any())).thenReturn(Map.of());
        var properties = new NativeDashScopeConnectionProperties();
        properties.setApiKey("env-key");
        var factory = new EmbeddingModelFactory(service, properties);
        var config = new ModelConfigEntity();
        config.setProvider("dashscope");
        config.setModelName("text-embedding-v3");
        config.setId(42L);
        var first = factory.build(config);
        assertInstanceOf(NativeDashScopeEmbeddingModel.class, first);
        assertSame(first, factory.build(config));
        factory.evict(42L);
        assertNotSame(first, factory.build(config));
    }

    @Test
    void sdk2KeylessEmbeddingPreservesVersionPathWithoutAuthorization() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    path.set(exchange.getRequestURI().getPath());
                    auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.getRequestBody().readAllBytes();
                    byte[] body =
                            "{\"object\":\"list\",\"model\":\"vec\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,2]}],\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var service = mock(ModelProviderService.class);
            var provider = new ModelProviderEntity();
            provider.setProviderId("local");
            provider.setChatModel("OpenAIChatModel");
            provider.setRequireApiKey(false);
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v4/");
            when(service.getProviderConfig("local")).thenReturn(provider);
            when(service.isProviderConfigured("local")).thenReturn(true);
            when(service.readProviderGenerateKwargs(any())).thenReturn(Map.of());
            var config = new ModelConfigEntity();
            config.setProvider("local");
            config.setModelName("vec");
            var factory =
                    new EmbeddingModelFactory(service, new NativeDashScopeConnectionProperties());
            assertArrayEquals(new float[] {1, 2}, factory.build(config).embed("one"));
            assertEquals("/api/v4/embeddings", path.get());
            assertNull(auth.get());
        } finally {
            server.stop(0);
        }
    }
}
