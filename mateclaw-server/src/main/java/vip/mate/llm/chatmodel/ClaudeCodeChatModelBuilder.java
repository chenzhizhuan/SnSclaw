package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import vip.mate.llm.anthropic.oauth.ClaudeCodeApiHeaders;
import vip.mate.llm.anthropic.oauth.ClaudeCodeOAuthService;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strategy implementation for {@link ModelProtocol#ANTHROPIC_CLAUDE_CODE}.
 *
 * <p>Sends Anthropic Messages API requests authenticated with the user's
 * Claude Code OAuth subscription token instead of an API key — letting users
 * with a Claude Pro/Max plan run SnSclaw against their existing entitlement.
 * Constructs the official SDK transport with Claude Code OAuth identity headers.
 */
@Slf4j
@Component
public class ClaudeCodeChatModelBuilder implements ChatModelBuilder {

    private final AnthropicChatModelBuilder anthropicBuilder;
    private final ClaudeCodeOAuthService oauthService;
    private final ClaudeCodeApiHeaders apiHeaders;
    private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;
    private final ObjectProvider<WebClient.Builder> webClientBuilderProvider;
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;
    private final ObjectMapper objectMapper;

    public ClaudeCodeChatModelBuilder(
            AnthropicChatModelBuilder anthropicBuilder,
            ClaudeCodeOAuthService oauthService,
            ClaudeCodeApiHeaders apiHeaders,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectMapper objectMapper) {
        this.anthropicBuilder = anthropicBuilder;
        this.oauthService = oauthService;
        this.apiHeaders = apiHeaders;
        this.restClientBuilderProvider = restClientBuilderProvider;
        this.webClientBuilderProvider = webClientBuilderProvider;
        this.observationRegistryProvider = observationRegistryProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.ANTHROPIC_CLAUDE_CODE;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        String accessToken = oauthService.getValidToken();
        AnthropicChatOptions options = anthropicBuilder.buildAnthropicOptions(model).mutate()
                .apiKey("oauth-transport-placeholder")
                .timeout(HttpTimeouts.resolveReadTimeout(model.getRequestTimeoutSeconds())).maxRetries(0).build();
        ChatModel raw = AnthropicSdkChatModel.create(options,
                observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP),
                buildOauthHeaders(accessToken), true, retry);
        return new ClaudeCodeIdentityChatModelDecorator(raw);
    }

    Map<String, String> buildOauthHeaders(String accessToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", apiHeaders.bearerAuth(accessToken));
        headers.put("User-Agent", apiHeaders.userAgent());
        headers.put("Accept", "application/json");
        headers.put("anthropic-beta", apiHeaders.allBetas());
        headers.put("anthropic-dangerous-direct-browser-access", "true");
        headers.put("x-app", apiHeaders.xApp());
        return Map.copyOf(headers);
    }
}
