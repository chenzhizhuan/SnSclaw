package vip.mate.decision.config;

import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
import vip.mate.decision.api.DecisionMode;
import vip.mate.decision.api.DecisionType;
import java.util.EnumMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "mate.decision")
public class DecisionProperties {
    private DecisionMode mode = DecisionMode.SHADOW;
    private Map<DecisionType, DecisionMode> scenarios = new EnumMap<>(DecisionType.class);
    private Map<DecisionType, ScenarioPolicy> scenarioPolicies = new EnumMap<>(DecisionType.class);
    private String provider = "rule";
    private double confidenceThreshold = 0.8;
    private long timeoutMs = 250;
    private int providerThreads = 4;
    private int shadowThreads = 2;
    private int queueCapacity = 64;
    private int retentionDays = 30;
    private int retentionBatchSize = 500;
    public DecisionMode modeFor(DecisionType type) { return scenarios.getOrDefault(type, mode); }
    @Data
    public static class ScenarioPolicy {
        private String provider;
        private Double confidenceThreshold;
        private Long timeoutMs;
    }
    /** Immutable per-call snapshot; omitted scenario fields inherit global values. */
    public record ResolvedPolicy(String provider, double confidenceThreshold, long timeoutMs) {
        public ResolvedPolicy {
            if (provider == null || !provider.matches("[A-Za-z0-9_.:-]{1,80}")
                    || !Double.isFinite(confidenceThreshold) || confidenceThreshold < 0 || confidenceThreshold > 1
                    || timeoutMs < 1 || timeoutMs > 60000)
                throw new IllegalArgumentException("Invalid decision policy");
        }
    }
    public ResolvedPolicy policyFor(DecisionType type) {
        var override = scenarioPolicies.get(type);
        return new ResolvedPolicy(override == null || override.provider == null ? provider : override.provider,
                override == null || override.confidenceThreshold == null ? confidenceThreshold : override.confidenceThreshold,
                override == null || override.timeoutMs == null ? timeoutMs : override.timeoutMs);
    }
    public void validate() {
        if (mode == null || scenarios == null || scenarioPolicies == null || provider == null || !provider.matches("[A-Za-z0-9_.:-]{1,80}")
                || !Double.isFinite(confidenceThreshold) || confidenceThreshold < 0 || confidenceThreshold > 1
                || timeoutMs < 1 || timeoutMs > 60000 || providerThreads < 1 || providerThreads > 32
                || shadowThreads < 1 || shadowThreads > 16 || queueCapacity < 1 || queueCapacity > 10000
                || retentionDays < 1 || retentionBatchSize < 1 || retentionBatchSize > 10000)
            throw new IllegalArgumentException("Invalid decision configuration");
        if (scenarios.containsValue(null) || scenarioPolicies.containsValue(null))
            throw new IllegalArgumentException("Null scenario configuration");
        for (DecisionType type : DecisionType.values()) policyFor(type);
    }
}
