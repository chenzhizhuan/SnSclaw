package vip.mate.channel;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Inbound dedup contract: a redelivered message is dropped, a genuinely new
 * one is not, and nothing is dropped when the platform gave us no stable
 * identity to key on.
 */
class InboundMessageDeduplicatorTest {

    private static InboundMessageDeduplicator dedup(ChannelDedupProperties props) {
        return new InboundMessageDeduplicator(props);
    }

    private static ChannelDedupProperties props() {
        return new ChannelDedupProperties();
    }

    @Test
    void firstClaimWinsAndRedeliveryIsDropped() {
        InboundMessageDeduplicator d = dedup(props());

        assertTrue(d.claim(1L, "msg-1"), "first delivery must be processed");
        assertFalse(d.claim(1L, "msg-1"), "redelivery must be dropped");
        assertFalse(d.claim(1L, "msg-1"), "and stay dropped");
        assertTrue(d.claim(1L, "msg-2"), "a different message is unaffected");
    }

    @Test
    void claimsAreScopedPerChannel() {
        InboundMessageDeduplicator d = dedup(props());

        assertTrue(d.claim(1L, "msg-1"));
        // Same platform message id on a different channel row: platform ids are
        // only unique per app, so these must not collide.
        assertTrue(d.claim(2L, "msg-1"));
    }

    @Test
    void blankIdentityFailsOpen() {
        InboundMessageDeduplicator d = dedup(props());

        // No stable identity => cannot tell a redelivery from a new message.
        // Dropping a real message is worse than answering a redelivery twice.
        assertTrue(d.claim(1L, null));
        assertTrue(d.claim(1L, null));
        assertTrue(d.claim(1L, "  "));
        assertTrue(d.claim(1L, "  "));
        assertEquals(0, d.size());
    }

    @Test
    void expiredClaimIsRetakeable() throws Exception {
        ChannelDedupProperties p = props();
        p.setTtl(Duration.ofMillis(30));
        InboundMessageDeduplicator d = dedup(p);

        assertTrue(d.claim(1L, "msg-1"));
        assertFalse(d.claim(1L, "msg-1"));
        Thread.sleep(60);
        assertTrue(d.claim(1L, "msg-1"), "past the TTL the identity is free again");
    }

    @Test
    void releaseLetsThePlatformRetryThrough() {
        InboundMessageDeduplicator d = dedup(props());

        assertTrue(d.claim(1L, "msg-1"));
        assertFalse(d.claim(1L, "msg-1"));
        // Never handed off for processing (queue full) — hand the claim back.
        d.release(1L, "msg-1");
        assertTrue(d.claim(1L, "msg-1"), "retry must be able to get through");
    }

    @Test
    void containsPeeksWithoutClaiming() {
        InboundMessageDeduplicator d = dedup(props());

        assertFalse(d.contains(1L, "msg-1"), "peek must not claim");
        assertFalse(d.contains(1L, "msg-1"));
        assertEquals(0, d.size(), "peeking must leave the register untouched");

        assertTrue(d.claim(1L, "msg-1"));
        assertTrue(d.contains(1L, "msg-1"));
    }

    @Test
    void capacityIsEnforcedAndOldestClaimsGoFirst() {
        ChannelDedupProperties p = props();
        p.setMaxSize(10);
        InboundMessageDeduplicator d = dedup(p);

        for (int i = 0; i < 50; i++) {
            assertTrue(d.claim(1L, "msg-" + i));
        }
        assertTrue(d.size() <= 10, "register must stay bounded, was " + d.size());
        assertTrue(d.contains(1L, "msg-49"), "the newest claim must survive the trim");
        assertFalse(d.contains(1L, "msg-0"), "the eldest claim is the one dropped");
    }

    @Test
    void disabledSwitchLetsEverythingThrough() {
        ChannelDedupProperties p = props();
        p.setEnabled(false);
        InboundMessageDeduplicator d = dedup(p);

        assertTrue(d.claim(1L, "msg-1"));
        assertTrue(d.claim(1L, "msg-1"));
        assertFalse(d.contains(1L, "msg-1"));
    }

    @Test
    void clearDropsEveryClaim() {
        InboundMessageDeduplicator d = dedup(props());

        assertTrue(d.claim(1L, "msg-1"));
        d.clear();
        assertEquals(0, d.size());
        assertTrue(d.claim(1L, "msg-1"));
    }

    // ---- identity derivation (ChannelMessageRouter.inboundIdentity) ----

    @Test
    void identityPrefersThePlatformMessageId() {
        ChannelMessage m = ChannelMessage.builder()
                .messageId("dt-123")
                .channelType("dingtalk")
                .senderId("alice")
                .timestamp(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();

        assertEquals("dt-123", ChannelMessageRouter.inboundIdentity(m));
    }

    @Test
    void identityFallsBackToSenderAndTimestamp() {
        LocalDateTime ts = LocalDateTime.of(2026, 1, 1, 0, 0);
        ChannelMessage m = ChannelMessage.builder()
                .channelType("telegram")
                .senderId("alice")
                .timestamp(ts)
                .build();

        // Stable across redeliveries of the same payload.
        assertEquals("alice@" + ts, ChannelMessageRouter.inboundIdentity(m));
    }

    @Test
    void identityIsNullWhenNothingStableExists() {
        ChannelMessage m = ChannelMessage.builder()
                .channelType("telegram")
                .senderId("alice")
                .build();

        assertNull(ChannelMessageRouter.inboundIdentity(m),
                "no id and no timestamp => must fail open, not invent a key");
        assertNull(ChannelMessageRouter.inboundIdentity(null));
    }
}
