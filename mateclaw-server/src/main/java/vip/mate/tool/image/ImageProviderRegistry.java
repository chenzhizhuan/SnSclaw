package vip.mate.tool.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 图片生成提供商注册表 — 收集所有 {@link ImageGenerationProvider} 实现，提供优先级排序与自动探测
 *
 * @author SnSclaw Team
 */
@Slf4j
@Component
public class ImageProviderRegistry {

    private final List<ImageGenerationProvider> sortedProviders;
    private final Map<String, ImageGenerationProvider> providerMap;

    public ImageProviderRegistry(List<ImageGenerationProvider> providers) {
        this.sortedProviders = providers.stream()
                .sorted(Comparator.comparingInt(ImageGenerationProvider::autoDetectOrder))
                .toList();
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(ImageGenerationProvider::id, Function.identity()));
        log.info("注册图片生成提供商 {} 个: {}", sortedProviders.size(),
                sortedProviders.stream().map(p -> p.id() + "(order=" + p.autoDetectOrder() + ")").toList());
    }

    /** 按 ID 获取指定 provider */
    public ImageGenerationProvider getById(String id) {
        return providerMap.get(id);
    }

    /** 获取按 autoDetectOrder 排序的全部 provider 列表 */
    public List<ImageGenerationProvider> allSorted() {
        return sortedProviders;
    }

    /**
     * 根据当前配置，解析应使用的 provider
     */
    public ResolvedProvider resolve(SystemSettingsDTO config, ImageCapability requiredCapability) {
        // 1. 用户显式配置的 primary provider
        String configuredId = config.getImageProvider();
        if (configuredId != null && !configuredId.isBlank() && !"auto".equals(configuredId)) {
            ImageGenerationProvider configured = providerMap.get(configuredId);
            if (configured != null && configured.isAvailable(config)
                    && configured.capabilities().contains(requiredCapability)) {
                return new ResolvedProvider(configured, "configured");
            }
        }

        // 2. 自动探测：按优先级遍历，找第一个可用且支持该能力的
        for (ImageGenerationProvider p : sortedProviders) {
            if (p.isAvailable(config) && p.capabilities().contains(requiredCapability)) {
                return new ResolvedProvider(p, "auto-detect");
            }
        }

        return null;
    }

    /**
     * 获取所有可用于 fallback 的 provider（按优先级排序，排除 primary）
     */
    public List<ImageGenerationProvider> fallbackCandidates(SystemSettingsDTO config,
                                                             ImageCapability requiredCapability,
                                                             String excludeId) {
        return sortedProviders.stream()
                .filter(p -> !p.id().equals(excludeId))
                .filter(p -> p.isAvailable(config))
                .filter(p -> p.capabilities().contains(requiredCapability))
                .toList();
    }

    public record ResolvedProvider(ImageGenerationProvider provider, String source) {
    }
}
