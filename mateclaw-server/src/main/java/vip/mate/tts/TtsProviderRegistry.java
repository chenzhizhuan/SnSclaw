package vip.mate.tts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TTS 提供商注册表
 *
 * @author SnSclaw Team
 */
@Slf4j
@Component
public class TtsProviderRegistry {

    private final List<TtsProvider> sortedProviders;
    private final Map<String, TtsProvider> providerMap;

    public TtsProviderRegistry(List<TtsProvider> providers) {
        this.sortedProviders = providers.stream()
                .sorted(Comparator.comparingInt(TtsProvider::autoDetectOrder))
                .toList();
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(TtsProvider::id, Function.identity()));
        log.info("注册 TTS 提供商 {} 个: {}", sortedProviders.size(),
                sortedProviders.stream().map(p -> p.id() + "(order=" + p.autoDetectOrder() + ")").toList());
    }

    public TtsProvider getById(String id) {
        return providerMap.get(id);
    }

    public List<TtsProvider> allSorted() {
        return sortedProviders;
    }

    /**
     * 根据当前配置，解析应使用的 provider
     */
    public TtsProvider resolve(SystemSettingsDTO config) {
        String configuredId = config.getTtsProvider();
        if (configuredId != null && !configuredId.isBlank() && !"auto".equals(configuredId)) {
            TtsProvider configured = providerMap.get(configuredId);
            if (configured != null && configured.isAvailable(config)) {
                return configured;
            }
        }

        // 自动探测
        for (TtsProvider p : sortedProviders) {
            if (p.isAvailable(config)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 获取可用于 fallback 的 provider 列表
     */
    public List<TtsProvider> fallbackCandidates(SystemSettingsDTO config, String excludeId) {
        return sortedProviders.stream()
                .filter(p -> !p.id().equals(excludeId))
                .filter(p -> p.isAvailable(config))
                .toList();
    }
}
