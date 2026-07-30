package vip.mate.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TTL- and capacity-bounded claim register for inbound channel messages.
 *
 * <p>One shared implementation for every channel. Before this existed, four
 * adapters carried four hand-rolled variants (a 500-entry LRU, an unbounded
 * set halved on overflow, an access-ordered map) and four adapters carried
 * none at all — DingTalk among them, which is why a redelivered DingTalk
 * message produced a second full answer.
 *
 * <p>A message is identified by {@code channelId + identity}, where identity is
 * the platform message id (see
 * {@link ChannelMessageRouter#inboundIdentity(ChannelMessage)}). Scoping by
 * channel keeps two channels of the same type from colliding on a platform id
 * that is only unique per app.
 *
 * <p>Three operations, matching the three things a caller needs:
 * <ul>
 *   <li>{@link #claim} — take ownership of a message. The first caller gets
 *       {@code true} and proceeds; a redelivery inside the TTL gets
 *       {@code false} and must drop the message.</li>
 *   <li>{@link #contains} — peek without claiming, so an adapter can drop a
 *       known redelivery <em>before</em> expensive inbound work (media
 *       download, payload decryption) and still leave the authoritative claim
 *       to the router.</li>
 *   <li>{@link #release} — give a claim back when the message was never handed
 *       off for processing (e.g. the channel queue was full), so the
 *       platform's own retry can still get through.</li>
 * </ul>
 *
 * <p>Fail-open by design: a blank identity means "this platform gave us
 * nothing stable to dedup on", and the message is always let through. Dropping
 * a real message is worse than answering a redelivery twice.
 *
 * <p>入站消息去重登记表（TTL + 容量双约束），全渠道共用一份实现。
 */
@Slf4j
@Component
@EnableConfigurationProperties(ChannelDedupProperties.class)
public class InboundMessageDeduplicator {

    private final ChannelDedupProperties props;

    /**
     * Claimed identity -> claim timestamp (epoch millis). Insertion-ordered so
     * the eldest entries sit at the head and overflow trimming is a head scan.
     * Guarded by its own monitor — claims are short, contended only by the
     * channel intake threads.
     */
    private final LinkedHashMap<String, Long> claims = new LinkedHashMap<>();

    public InboundMessageDeduplicator(ChannelDedupProperties props) {
        this.props = props;
    }

    /**
     * Take ownership of an inbound message.
     *
     * @return {@code true} when the caller owns this message and should process
     *         it; {@code false} when it is a redelivery already claimed inside
     *         the TTL window and must be dropped
     */
    public boolean claim(Long channelId, String identity) {
        String key = key(channelId, identity);
        if (key == null || !props.isEnabled()) {
            return true;
        }
        long now = System.currentTimeMillis();
        long ttlMs = ttlMillis();
        synchronized (claims) {
            Long claimedAt = claims.get(key);
            if (claimedAt != null && now - claimedAt < ttlMs) {
                return false;
            }
            // Either new, or an expired claim being retaken. Remove first so
            // the re-insert moves the entry to the tail — insertion order is
            // what the overflow trim relies on to find the eldest claims.
            claims.remove(key);
            claims.put(key, now);
            if (claims.size() > props.getMaxSize()) {
                trim(now, ttlMs);
            }
            return true;
        }
    }

    /**
     * Peek at a claim without taking one. Lets an adapter short-circuit a
     * redelivery before doing expensive inbound work while leaving the single
     * authoritative claim to the router.
     */
    public boolean contains(Long channelId, String identity) {
        String key = key(channelId, identity);
        if (key == null || !props.isEnabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long ttlMs = ttlMillis();
        synchronized (claims) {
            Long claimedAt = claims.get(key);
            if (claimedAt == null) {
                return false;
            }
            if (now - claimedAt < ttlMs) {
                return true;
            }
            claims.remove(key);
            return false;
        }
    }

    /**
     * Hand a claim back. Call this only when the message was never handed off
     * for processing — a turn that ran and failed keeps its claim, because the
     * user already received the error and a platform retry would just send a
     * second one.
     */
    public void release(Long channelId, String identity) {
        String key = key(channelId, identity);
        if (key == null) {
            return;
        }
        synchronized (claims) {
            claims.remove(key);
        }
    }

    /** Drop every claim. Called when a channel restarts. */
    public void clear() {
        synchronized (claims) {
            claims.clear();
        }
    }

    /** Live claim count. Package-private for tests. */
    int size() {
        synchronized (claims) {
            return claims.size();
        }
    }

    /**
     * Evict expired claims first; if the map is still over capacity (every
     * entry fresh under sustained traffic), drop the eldest until it fits.
     * Caller holds the monitor.
     */
    private void trim(long now, long ttlMs) {
        claims.entrySet().removeIf(e -> now - e.getValue() >= ttlMs);
        int overflow = claims.size() - props.getMaxSize();
        if (overflow <= 0) {
            return;
        }
        Iterator<Map.Entry<String, Long>> it = claims.entrySet().iterator();
        for (int i = 0; i < overflow && it.hasNext(); i++) {
            it.next();
            it.remove();
        }
        log.debug("[dedup] Trimmed {} eldest claims (cap={})", overflow, props.getMaxSize());
    }

    private long ttlMillis() {
        Duration ttl = props.getTtl();
        return ttl != null ? Math.max(1L, ttl.toMillis()) : Duration.ofMinutes(5).toMillis();
    }

    /**
     * Compose the tracking key, or {@code null} when there is nothing stable to
     * track. Scoped by channel id so two channels of the same type can't
     * collide on a per-app platform id.
     */
    private static String key(Long channelId, String identity) {
        if (identity == null || identity.isBlank()) {
            return null;
        }
        return (channelId == null ? "-" : channelId.toString()) + ":" + identity;
    }
}
