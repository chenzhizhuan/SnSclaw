package vip.mate.llm.chatmodel;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.exception.MateClawException;
import vip.mate.llm.dashscope.NativeDashScopeChatModel;
import vip.mate.llm.dashscope.NativeDashScopeChatOptions;
import vip.mate.llm.dashscope.NativeDashScopeConnectionProperties;
import vip.mate.llm.dashscope.NativeDashScopeTransport;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds native models directly from persisted configuration and environment defaults. */
@Component
@EnableConfigurationProperties(NativeDashScopeConnectionProperties.class)
public class DashScopeChatModelBuilder implements ChatModelBuilder {
    private static final List<String> SEARCH_MODELS = List.of("qwen-plus", "qwen-max", "qwen-turbo",
            "qwen3-max", "qwen3.5-flash", "qwen3.6-flash");
    private static final Set<String> TRANSPORT_OPTIONS = Set.of("request_timeout_seconds", "timeout", "read_timeout");
    private final NativeDashScopeConnectionProperties properties;
    private final ModelProviderService providerService;

    public DashScopeChatModelBuilder(NativeDashScopeConnectionProperties properties, ModelProviderService providerService) {
        this.properties = properties;
        this.providerService = providerService;
    }

    @Override public ModelProtocol supportedProtocol() { return ModelProtocol.DASHSCOPE_NATIVE; }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        String apiKey = provider != null ? provider.getApiKey() : null;
        if (!providerService.hasUsableApiKey(apiKey)) apiKey = properties.getApiKey();
        if (!providerService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.agent.dashscope_key_missing",
                    "DashScope API Key 未配置，请在模型设置中填写 API Key");
        }
        String baseUrl = provider != null && StringUtils.hasText(provider.getBaseUrl())
                ? provider.getBaseUrl() : properties.getBaseUrl();
        Map<String, Object> kwargs = providerService.readProviderGenerateKwargs(provider);
        Integer timeout = model.getRequestTimeoutSeconds() != null && model.getRequestTimeoutSeconds() > 0
                ? model.getRequestTimeoutSeconds() : ProviderGenerateKwargs.resolveIntegerOption("requestTimeoutSeconds", null, kwargs);
        HttpClient http = HttpClient.newBuilder().connectTimeout(HttpTimeouts.CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1).build();
        return new NativeDashScopeChatModel(new NativeDashScopeTransport(apiKey, baseUrl, http,
                HttpTimeouts.resolveReadTimeout(timeout)), buildDashScopeOptions(model, provider));
    }

    public boolean isBuiltinSearchEnabled(ModelConfigEntity model, ModelProviderEntity provider) {
        if (model != null && model.getEnableSearch() != null) return model.getEnableSearch();
        Object explicit = ProviderGenerateKwargs.findOptionValue(providerService.readProviderGenerateKwargs(provider), "enableSearch");
        if (explicit != null) return Boolean.TRUE.equals(explicit);
        String name = model != null && StringUtils.hasText(model.getModelName())
                ? model.getModelName() : properties.getChat().getOptions().getModel();
        if (!StringUtils.hasText(name)) return false;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return SEARCH_MODELS.stream().anyMatch(normalized::startsWith);
    }

    NativeDashScopeChatOptions buildDashScopeOptions(ModelConfigEntity model, ModelProviderEntity provider) {
        Map<String, Object> kwargs = providerService.readProviderGenerateKwargs(provider);
        var defaults = properties.getChat().getOptions();
        NativeDashScopeChatOptions.Builder builder = NativeDashScopeChatOptions.builder()
                .model(StringUtils.hasText(model.getModelName()) ? model.getModelName() : defaults.getModel())
                .temperature(model.getTemperature() != null ? model.getTemperature()
                        : ProviderGenerateKwargs.resolveDoubleOption("temperature", defaults.getTemperature(), kwargs))
                .maxTokens(model.getMaxTokens() != null ? model.getMaxTokens()
                        : ProviderGenerateKwargs.resolveIntegerOption("maxTokens", defaults.getMaxTokens(), kwargs))
                .topP(model.getTopP() != null ? model.getTopP()
                        : ProviderGenerateKwargs.resolveDoubleOption("topP", defaults.getTopP(), kwargs))
                .topK(ProviderGenerateKwargs.resolveIntegerOption("topK", null, kwargs));
        ProviderGenerateKwargs.collectPassthroughExtraBody(kwargs).forEach((key, value) -> {
            String nativeKey = key.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            if (!TRANSPORT_OPTIONS.contains(nativeKey)) builder.nativeParameter(nativeKey, value);
        });
        // Nested options use the same spelling and precedence as top-level provider settings.
        for (String key : List.of("enableThinking", "thinkingBudget", "repetitionPenalty", "seed", "stop", "responseFormat")) {
            Object value = ProviderGenerateKwargs.findOptionValue(kwargs, key);
            if (value != null) builder.nativeParameter(key.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT), value);
        }
        boolean search = isBuiltinSearchEnabled(model, provider);
        builder.enableSearch(search);
        if (search) {
            Object configured = ProviderGenerateKwargs.findOptionValue(kwargs, "searchOptions");
            Map<String, Object> searchOptions = new LinkedHashMap<>();
            if (configured instanceof Map<?, ?> map) map.forEach((key, value) -> searchOptions.put(String.valueOf(key), value));
            String strategy = StringUtils.hasText(model.getSearchStrategy()) ? model.getSearchStrategy()
                    : String.valueOf(ProviderGenerateKwargs.findOptionValue(kwargs, "searchStrategy"));
            if (StringUtils.hasText(strategy) && !"null".equals(strategy)) {
                searchOptions.put("search_strategy", strategy);
                searchOptions.put("enable_source", true);
                searchOptions.put("enable_citation", true);
            }
            if (!searchOptions.isEmpty()) builder.searchOptions(searchOptions);
        }
        return builder.build();
    }
}
