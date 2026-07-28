package vip.mate.tool.video;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * 视频生成 Provider 细粒度能力声明
 * <p>
 * 参考 OpenClaw 的 VideoGenerationProviderCapabilities 设计，
 * 每个 Provider 显式声明支持的参数范围，Runtime 据此做就近归一化。
 *
 * @author SnSclaw Team
 */
@Data
@Builder
public class VideoProviderCapabilities {

    /** 支持的生成模式 */
    @Builder.Default
    private Set<VideoCapability> modes = Set.of(VideoCapability.GENERATE);

    /** 支持的画面比例 */
    @Builder.Default
    private List<String> aspectRatios = List.of("16:9", "9:16", "1:1");

    /** 支持的时长（秒），如 [5, 10] 表示只支持 5 秒和 10 秒 */
    @Builder.Default
    private List<Integer> supportedDurations = List.of(5);

    /** 最大时长（秒） */
    @Builder.Default
    private int maxDurationSeconds = 10;

    /** 最大并发视频数 */
    @Builder.Default
    private int maxVideos = 1;

    /** 是否支持音频 */
    @Builder.Default
    private boolean supportsAudio = false;

    /** 默认模型 */
    private String defaultModel;

    /** 可用模型列表 */
    @Builder.Default
    private List<String> models = List.of();

    /**
     * 将请求的 duration 就近匹配到 provider 支持的值
     */
    public int normalizeDuration(int requested) {
        if (supportedDurations == null || supportedDurations.isEmpty()) {
            return Math.min(requested, maxDurationSeconds);
        }
        int closest = supportedDurations.get(0);
        int minDiff = Math.abs(requested - closest);
        for (int d : supportedDurations) {
            int diff = Math.abs(requested - d);
            if (diff < minDiff) {
                minDiff = diff;
                closest = d;
            }
        }
        return closest;
    }

    /**
     * 将请求的 aspectRatio 就近匹配或回退到默认
     */
    public String normalizeAspectRatio(String requested) {
        if (aspectRatios.contains(requested)) {
            return requested;
        }
        return aspectRatios.isEmpty() ? "16:9" : aspectRatios.get(0);
    }
}
