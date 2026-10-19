package vip.mate.llm.embedding;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.exception.MateClawException;
import vip.mate.llm.chatmodel.HttpTimeouts;
import vip.mate.llm.chatmodel.ProviderGenerateKwargs;
import vip.mate.llm.dashscope.NativeDashScopeConnectionProperties;
import vip.mate.llm.dashscope.NativeDashScopeEmbeddingModel;
import vip.mate.llm.dashscope.NativeDashScopeTransport;
import vip.mate.llm.model.EmbeddingProtocol;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Embedding 模型工厂
 * <p>
 * Builds native or compatible embedding clients and invalidates them after configuration changes.
 *
 * @author SnSclaw
 */
@Component
public class EmbeddingModelFactory {
    private static final Pattern VERSION_SUFFIX = Pattern.compile(".*/v\\d+$");
    private final ModelProviderService providerService;
    private final NativeDashScopeConnectionProperties properties;
    private final ConcurrentHashMap<Long, EmbeddingModel> cache = new ConcurrentHashMap<>();

    public EmbeddingModelFactory(ModelProviderService providerService, NativeDashScopeConnectionProperties properties) {
        this.providerService = providerService;
        this.properties = properties;
    }

    public EmbeddingModel build(ModelConfigEntity config) {
        if (config == null) throw new MateClawException("err.embedding.config_null", "Embedding model config is null");
        return config.getId() == null ? doBuild(config) : cache.computeIfAbsent(config.getId(), ignored -> doBuild(config));
    }
    public void evict(Long id) { if (id != null) cache.remove(id); }
    public void evictAll() { cache.clear(); }

    static EmbeddingProtocol resolveEmbeddingProtocol(String chatModel) {
        return chatModel != null && "DashScopeChatModel".equalsIgnoreCase(chatModel.trim())
                ? EmbeddingProtocol.DASHSCOPE_EMBEDDING : EmbeddingProtocol.OPENAI_EMBEDDING;
    }

    private EmbeddingModel doBuild(ModelConfigEntity config) {
        ModelProviderEntity provider = providerService.getProviderConfig(config.getProvider());
        if (provider == null) throw new MateClawException("err.embedding.provider_missing",
                "Embedding provider '" + config.getProvider() + "' not found in mate_model_provider");
        Map<String, Object> kwargs = providerService.readProviderGenerateKwargs(provider);
        Integer timeout = config.getRequestTimeoutSeconds() != null && config.getRequestTimeoutSeconds() > 0
                ? config.getRequestTimeoutSeconds() : ProviderGenerateKwargs.resolveIntegerOption("requestTimeoutSeconds", null, kwargs);
        Integer dimension = ProviderGenerateKwargs.resolveIntegerOption("dimensions", null, kwargs);
        return resolveEmbeddingProtocol(provider.getChatModel()) == EmbeddingProtocol.DASHSCOPE_EMBEDDING
                ? buildNative(config, provider, kwargs, timeout, dimension) : buildCompatible(config, provider, timeout, dimension);
    }

    private EmbeddingModel buildNative(ModelConfigEntity config, ModelProviderEntity provider,
                                      Map<String, Object> kwargs, Integer timeout, Integer dimension) {
        String apiKey = provider.getApiKey();
        if (!providerService.hasUsableApiKey(apiKey)) apiKey = properties.getApiKey();
        if (!providerService.hasUsableApiKey(apiKey)) throw new MateClawException("err.embedding.dashscope_key_missing",
                "DashScope API Key 未配置。请在模型设置中填写 API Key。");
        String baseUrl = StringUtils.hasText(provider.getBaseUrl()) ? provider.getBaseUrl() : properties.getBaseUrl();
        HttpClient http = HttpClient.newBuilder().connectTimeout(HttpTimeouts.CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1).build();
        Object configuredType = ProviderGenerateKwargs.findOptionValue(kwargs, "textType");
        String textType = configuredType == null ? "document" : String.valueOf(configuredType);
        return new NativeDashScopeEmbeddingModel(new NativeDashScopeTransport(apiKey, baseUrl, http,
                HttpTimeouts.resolveReadTimeout(timeout)), MetadataMode.EMBED,
                EmbeddingOptions.builder().model(config.getModelName()).dimensions(dimension).build(), textType);
    }

    private EmbeddingModel buildCompatible(ModelConfigEntity config, ModelProviderEntity provider,
                                          Integer timeout, Integer dimension) {
        if (!providerService.isProviderConfigured(provider.getProviderId())) throw new MateClawException(
                "err.embedding.provider_not_configured", "Provider 未完成配置: " + provider.getProviderId());
        String key = provider.getApiKey();
        boolean usableKey = providerService.hasUsableApiKey(key);
        if (!Boolean.FALSE.equals(provider.getRequireApiKey()) && !usableKey) throw new MateClawException(
                "err.embedding.openai_key_invalid", "Provider API Key 未配置或无效: " + provider.getProviderId());
        String url = provider.getBaseUrl();
        if (!StringUtils.hasText(url)) throw new MateClawException("err.embedding.openai_baseurl_missing",
                "Provider Base URL 未配置: " + provider.getProviderId());
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!VERSION_SUFFIX.matcher(url).matches()) url += "/v1";
        // The SDK's empty-string API-key mode omits Authorization for keyless local providers.
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder().model(config.getModelName())
                .baseUrl(url).apiKey(usableKey ? key.trim() : "").dimensions(dimension)
                .timeout(HttpTimeouts.resolveReadTimeout(timeout)).maxRetries(1).build();
        return OpenAiEmbeddingModel.builder().metadataMode(MetadataMode.EMBED).options(options).build();
    }
}
