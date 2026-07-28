package vip.mate.wiki.event;

/**
 * Published after a fact-layer page is updated during ingest (already
 * committed). Consumed asynchronously to mark the experience pages that depend
 * on it as stale, without blocking ingest.
 *
 * @author SnSclaw Team
 */
public record WikiFactPageUpdatedEvent(Long kbId, Long factPageId, String reason) {
}
