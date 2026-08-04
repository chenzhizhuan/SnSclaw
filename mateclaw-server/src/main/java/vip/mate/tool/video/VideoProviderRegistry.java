package vip.mate.tool.video;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 视频生成提供商注册表 — 收集所有 {@link VideoGenerationProvider} 实现，提供优先级排序与自动探测
 * <p>
 * 借鉴 {@link vip.mate.tool.search.SearchProviderRegistry} 的设计模式。
 *
 * @author SnSclaw
 */
@Slf4j
@Component
public class VideoProviderRegistry {

    private final List<VideoGenerationProvider> sortedProviders;
    private final Map<String, VideoGenerationProvider> providerMap;

    public VideoProviderRegistry(List<VideoGenerationProvider> providers) {
        this.sortedProviders = providers.stream()
                .sorted(Comparator.comparingInt(VideoGenerationProvider::autoDetectOrder))
                .toList();
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(VideoGenerationProvider::id, Function.identity()));
        log.info("注册视频生成提供商 {} 个: {}", sortedProviders.size(),
                sortedProviders.stream().map(p -> p.id() + "(order=" + p.autoDetectOrder() + ")").toList());
    }

    /** 按 ID 获取指定 provider */
    public VideoGenerationProvider getById(String id) {
        return providerMap.get(id);
    }

    /** 获取按 autoDetectOrder 排序的全部 provider 列表 */
    public List<VideoGenerationProvider> allSorted() {
        return sortedProviders;
    }

    /**
     * 根据当前配置，解析应使用的 provider
     *
     * @param config             系统配置
     * @param requiredCapability 需要的能力（如 GENERATE、IMAGE_TO_VIDEO）
     * @return 选中的 provider，或 null
     */
    public ResolvedProvider resolve(SystemSettingsDTO config, VideoCapability requiredCapability) {
        // 1. 用户显式配置的 primary provider
        String configuredId = config.getVideoProvider();
        if (configuredId != null && !configuredId.isBlank() && !"auto".equals(configuredId)) {
            VideoGenerationProvider configured = providerMap.get(configuredId);
            if (configured != null && configured.isAvailable(config)
                    && configured.capabilities().contains(requiredCapability)) {
                return new ResolvedProvider(configured, "configured");
            }
        }

        // 2. 自动探测：按优先级遍历，找第一个可用且支持该能力的
        for (VideoGenerationProvider p : sortedProviders) {
            if (p.isAvailable(config) && p.capabilities().contains(requiredCapability)) {
                return new ResolvedProvider(p, "auto-detect");
            }
        }

        return null;
    }

    /**
     * 获取所有可用于 fallback 的 provider（按优先级排序，排除 primary）
     */
    public List<VideoGenerationProvider> fallbackCandidates(SystemSettingsDTO config,
                                                             VideoCapability requiredCapability,
                                                             String excludeId) {
        return sortedProviders.stream()
                .filter(p -> !p.id().equals(excludeId))
                .filter(p -> p.isAvailable(config))
                .filter(p -> p.capabilities().contains(requiredCapability))
                .toList();
    }

    public record ResolvedProvider(VideoGenerationProvider provider, String source) {
    }
}
