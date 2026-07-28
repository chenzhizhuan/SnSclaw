package vip.mate.channel;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 指数退避工具类
 * <p>
 * 用于断线重连、Token 刷新失败重试等场景。
 * 每次调用 {@link #nextDelayMs()} 返回递增的延迟时间（带上限），
 * 重连成功后调用 {@link #reset()} 重置计数器。
 *
 * <p><b>RFC-024 Change 5</b>：新增可选 {@code jitter} 参数（0.0 ~ 1.0）。
 * 默认构造保持 {@code jitter=0.0} 完全等价既有行为；WeChat 等高并发场景构造时传 0.2 启用
 * ±20% 随机扰动，避免多实例同步重连造成"雷群效应"。
 *
 * @author SnSclaw Team
 */
public class ExponentialBackoff {

    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double factor;
    private final int maxAttempts;
    /** 随机扰动比例，0 表示无扰动（默认），0.2 表示 ±20% */
    private final double jitter;
    private final AtomicInteger attempts = new AtomicInteger(0);

    /**
     * @param initialDelayMs 初始延迟（毫秒）
     * @param maxDelayMs     最大延迟上限（毫秒）
     * @param factor         退避倍数（通常为 2.0）
     * @param maxAttempts    最大重试次数（-1 表示无限重试）
     * @param jitter         随机扰动比例（0 ~ 1），0 为无扰动
     */
    public ExponentialBackoff(long initialDelayMs, long maxDelayMs, double factor,
                              int maxAttempts, double jitter) {
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.factor = factor;
        this.maxAttempts = maxAttempts;
        // 夹到合法区间 [0, 1)
        this.jitter = Math.max(0.0, Math.min(jitter, 0.999));
    }

    /** 兼容旧调用：jitter 默认 0 */
    public ExponentialBackoff(long initialDelayMs, long maxDelayMs, double factor, int maxAttempts) {
        this(initialDelayMs, maxDelayMs, factor, maxAttempts, 0.0);
    }

    /** 默认配置：2s 起步，30s 上限，2 倍递增，无限重试，无 jitter */
    public ExponentialBackoff() {
        this(2000, 30000, 2.0, -1, 0.0);
    }

    /**
     * 计算下一次延迟（毫秒），并递增尝试次数。
     * <p>jitter &gt; 0 时在 base delay 上叠加 ±jitter 比例的随机扰动，
     * 最终结果仍夹到 [0, maxDelayMs] 区间。</p>
     *
     * @return 延迟毫秒数
     */
    public long nextDelayMs() {
        int attempt = attempts.getAndIncrement();
        long base = (long) (initialDelayMs * Math.pow(factor, attempt));
        long capped = Math.min(base, maxDelayMs);
        if (jitter <= 0.0) return capped;
        // 均匀分布 ±jitter
        double noise = (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * jitter;
        long withNoise = capped + (long) (capped * noise);
        return Math.max(0L, Math.min(withNoise, maxDelayMs));
    }

    /**
     * 是否已超过最大重试次数
     */
    public boolean isExhausted() {
        if (maxAttempts < 0) return false;
        return attempts.get() >= maxAttempts;
    }

    /**
     * 重置退避计数器（重连成功后调用）
     */
    public void reset() {
        attempts.set(0);
    }

    /**
     * 当前已尝试次数
     */
    public int getAttempts() {
        return attempts.get();
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    public double getJitter() {
        return jitter;
    }
}
