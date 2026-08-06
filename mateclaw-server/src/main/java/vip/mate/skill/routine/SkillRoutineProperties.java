package vip.mate.skill.routine;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for routine mining — the cross-session pass that detects
 * requests the user makes habitually and promotes them into skills.
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.skill.routine")
public class SkillRoutineProperties {

    /** Master switch. When {@code false} neither mining nor promotion runs. */
    private boolean enabled = true;

    /** How far back the mining pass looks, in days. */
    private int lookbackDays = 30;

    /**
     * Shingle-similarity threshold above which two conversation openers are
     * considered the same request. Tuned toward precision: a false merge
     * produces a skill describing a routine the user does not actually have,
     * which is worse than missing one and catching it on the next sweep.
     */
    private double similarityThreshold = 0.62;

    /**
     * Conversations a cluster needs before promotion. Two is coincidence.
     */
    private int minOccurrences = 3;

    /**
     * Distinct calendar days a cluster must span before promotion. Guards
     * against a single afternoon of retries reading as a daily habit.
     */
    private int minDistinctDays = 3;

    /** Shortest opener worth clustering; below this the text carries no intent. */
    private int minOpenerChars = 8;

    /** Longest opener prefix fed to the shingler. */
    private int maxOpenerChars = 400;

    /** Conversation ids retained per candidate as promotion evidence. */
    private int maxSamplesPerCandidate = 8;

    /** Candidates promoted in a single sweep, bounding LLM cost per run. */
    private int maxPromotionsPerRun = 2;

    /** Conversations scanned per sweep, bounding memory and query cost. */
    private int maxConversationsPerRun = 1000;

    /** Messages of each sample conversation shown to the synthesizer. */
    private int transcriptMessagesPerSample = 12;

    /** Per-message truncation when building the synthesis transcript. */
    private int transcriptTruncateChars = 800;

    /** Synthesis model ID ({@code null} = follow the system default model). */
    private String modelId;
}
