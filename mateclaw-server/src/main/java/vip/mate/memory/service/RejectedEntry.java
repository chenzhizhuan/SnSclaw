package vip.mate.memory.service;

/**
 * A candidate that was scored but not adopted into MEMORY.md during a dream.
 *
 * @author SnSclaw
 */
public record RejectedEntry(
        Long recallId,
        String filename,
        String snippetPreview,
        double score,
        int reviewCount
) {}
