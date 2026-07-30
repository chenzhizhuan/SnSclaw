package vip.mate.channel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for inbound channel-message deduplication.
 *
 * <p>IM platforms redeliver the same message when an acknowledgement is late,
 * lost, or answered with a non-200 — DingTalk, WeCom and Feishu all do this.
 * Every redelivery that reaches the router starts a full, independent agent
 * turn, so the user sees the same answer twice (and the conversation gains a
 * duplicate user/assistant pair). {@link InboundMessageDeduplicator} keeps a
 * short-lived record of the message identities already claimed so a
 * redelivery is dropped instead of answered again.
 *
 * <p>入站渠道消息去重配置。平台重投同一条消息时，若不去重则每次重投都会跑一轮完整
 * 的 Agent 回合，用户看到重复答复。
 *
 * <pre>
 * mate:
 *   channel:
 *     dedup:
 *       enabled: true
 *       ttl: 5m
 *       max-size: 2000
 * </pre>
 */
@ConfigurationProperties(prefix = "mate.channel.dedup")
public class ChannelDedupProperties {

    /**
     * Master switch. When false every message is treated as new — only useful
     * when debugging a suspected false-positive drop.
     */
    private boolean enabled = true;

    /**
     * How long a claimed message identity keeps suppressing redeliveries.
     *
     * <p>Must comfortably exceed the platforms' redelivery windows (seconds to
     * low minutes) while staying short enough that a user who genuinely resends
     * the identical payload later is not silenced. Note that a resend carries a
     * fresh platform message id in every channel we support, so the TTL only
     * matters for the id-less fallback identity.
     */
    private Duration ttl = Duration.ofMinutes(5);

    /**
     * Hard cap on tracked identities. Reached only under sustained traffic
     * within one TTL window; the oldest claims are dropped first.
     */
    private int maxSize = 2000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }
}
