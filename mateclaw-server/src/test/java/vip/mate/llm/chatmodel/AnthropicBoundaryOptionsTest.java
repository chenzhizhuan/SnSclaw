package vip.mate.llm.chatmodel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import vip.mate.llm.cache.AnthropicCacheOptionsFactory;
import vip.mate.llm.cache.CacheProperties;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.time.Duration;

class AnthropicBoundaryOptionsTest {
    @AfterEach
    void clearThinking() {
        ThinkingLevelHolder.clear();
    }

    @Test
    void decoratorExposesTypedDefaultOptionsThroughAi2Contract() {
        AnthropicChatOptions options =
                AnthropicChatOptions.builder().model("claude-custom").build();
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.getOptions()).thenReturn(options);
        assertSame(options, new ClaudeCodeIdentityChatModelDecorator(delegate).getOptions());
    }

    @Test
    void clientOptionsPreserveTimeoutCredentialsAndDisableSdkRetries() {
        ModelProviderService service = mock(ModelProviderService.class);
        when(service.isProviderConfigured("provider")).thenReturn(true);
        when(service.hasUsableApiKey(" test-key ")).thenReturn(true);
        AnthropicChatModelBuilder builder = builder(service);
        ModelConfigEntity model = new ModelConfigEntity();
        model.setModelName("claude-sonnet-4");
        model.setMaxTokens(1234);
        model.setRequestTimeoutSeconds(25);
        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setProviderId("provider");
        provider.setApiKey(" test-key ");
        provider.setBaseUrl(" http://localhost:1 ");
        AnthropicChatOptions options = builder.buildClientOptions(model, provider);
        assertEquals("test-key", options.getApiKey());
        assertEquals("http://localhost:1", options.getBaseUrl());
        assertEquals(Duration.ofSeconds(25), options.getTimeout());
        assertEquals(0, options.getMaxRetries());
        assertEquals(1234, options.getMaxTokens());
    }

    @Test
    void immutableThinkingOptionsKeepBudgetAndSamplingContract() {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setModelName("claude-opus-4-8");
        model.setTemperature(0.3);
        model.setTopP(0.8);
        ThinkingLevelHolder.set("xhigh");
        AnthropicChatOptions options =
                builder(mock(ModelProviderService.class)).buildAnthropicOptions(model);
        assertEquals(24576, options.getThinking().asEnabled().budgetTokens());
        assertEquals(28672, options.getMaxTokens());
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
        assertNull(options.getTopK());
        assertNotNull(options.getCacheOptions());
    }

    @SuppressWarnings("unchecked")
    private AnthropicChatModelBuilder builder(ModelProviderService service) {
        ObjectProvider<ObservationRegistry> observations = mock(ObjectProvider.class);
        return new AnthropicChatModelBuilder(
                service,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                observations,
                new AnthropicCacheOptionsFactory(new CacheProperties()));
    }
}
