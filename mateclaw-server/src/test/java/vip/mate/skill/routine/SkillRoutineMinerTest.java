package vip.mate.skill.routine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.common.text.Shingles;
import vip.mate.skill.routine.repository.SkillRoutineCandidateMapper;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for the deterministic half of routine mining — opener normalization
 * and clustering. These are the parts that decide whether two runs of the same
 * habitual request are recognised as the same routine.
 */
class SkillRoutineMinerTest {

    private SkillRoutineProperties properties;
    private SkillRoutineMiner miner;

    @BeforeEach
    void setUp() {
        properties = new SkillRoutineProperties();
        miner = new SkillRoutineMiner(
                mock(ConversationMapper.class),
                mock(MessageMapper.class),
                mock(SkillRoutineCandidateMapper.class),
                properties,
                new ObjectMapper());
    }

    private SkillRoutineMiner.Opener opener(String text, int dayOffset) {
        String normalized = miner.normalize(text);
        return new SkillRoutineMiner.Opener(
                "conv-" + text.hashCode() + "-" + dayOffset,
                1L, 1L, text, normalized, Shingles.of(normalized),
                LocalDateTime.of(2026, 8, 1, 9, 0).plusDays(dayOffset));
    }

    @Test
    @DisplayName("normalize strips the values that vary between runs of one routine")
    void normalizeStripsVaryingValues() {
        String a = miner.normalize("Generate the 2026-08-04 ops report");
        String b = miner.normalize("Generate the 2026-08-05 ops report");
        assertEquals(a, b, "dates must not distinguish two runs of the same routine");
    }

    @Test
    @DisplayName("normalize drops URLs and filesystem paths")
    void normalizeDropsUrlsAndPaths() {
        String n = miner.normalize("Summarize https://example.com/x and /var/log/app.log please");
        assertTrue(n.contains("summarize"), "intent words survive: " + n);
        assertTrue(!n.contains("example") && !n.contains("var"),
                "URL and path tokens must be stripped: " + n);
    }

    @Test
    @DisplayName("Chinese openers cluster without a word segmenter")
    void clustersChineseOpeners() {
        List<SkillRoutineMiner.Opener> openers = new ArrayList<>(List.of(
                opener("帮我生成今天的运维日报", 0),
                opener("帮我生成今天的运维日报，谢谢", 1),
                opener("生成今天的运维日报", 2)));

        List<SkillRoutineMiner.Cluster> clusters = miner.cluster(openers);

        assertEquals(1, clusters.size(), "three phrasings of one request must form one cluster");
        assertEquals(3, clusters.get(0).members().size());
        assertEquals(3, clusters.get(0).distinctDays());
    }

    @Test
    @DisplayName("unrelated requests stay in separate clusters")
    void keepsUnrelatedRequestsApart() {
        List<SkillRoutineMiner.Opener> openers = new ArrayList<>(List.of(
                opener("帮我生成今天的运维日报", 0),
                opener("把这段代码重构成异步实现", 1),
                opener("查一下上个季度的营收数字", 2)));

        List<SkillRoutineMiner.Cluster> clusters = miner.cluster(openers);

        assertEquals(3, clusters.size(), "distinct intents must not be merged");
    }

    @Test
    @DisplayName("English openers cluster on shared word tokens")
    void clustersEnglishOpeners() {
        List<SkillRoutineMiner.Opener> openers = new ArrayList<>(List.of(
                opener("generate the weekly oncall digest for the team", 0),
                opener("generate the weekly oncall digest for the team now", 3)));

        List<SkillRoutineMiner.Cluster> clusters = miner.cluster(openers);

        assertEquals(1, clusters.size());
        assertEquals(2, clusters.get(0).distinctDays());
    }

    @Test
    @DisplayName("distinctDays counts calendar days, not occurrences")
    void distinctDaysIgnoresSameDayRetries() {
        // Five conversations in one afternoon is one person retrying, not a habit.
        List<SkillRoutineMiner.Opener> sameDay = new ArrayList<>(List.of(
                opener("帮我生成今天的运维日报", 0),
                opener("帮我生成今天的运维日报", 0),
                opener("帮我生成今天的运维日报", 0)));

        List<SkillRoutineMiner.Cluster> clusters = miner.cluster(sameDay);

        assertEquals(1, clusters.size());
        assertEquals(3, clusters.get(0).members().size());
        assertEquals(1, clusters.get(0).distinctDays(),
                "same-day retries must not satisfy the habit gate");
    }

    @Test
    @DisplayName("a raised similarity threshold splits loosely-related openers")
    void thresholdControlsMergeAggressiveness() {
        properties.setSimilarityThreshold(0.95);
        List<SkillRoutineMiner.Opener> openers = new ArrayList<>(List.of(
                opener("generate the weekly oncall digest for the team", 0),
                opener("generate the weekly oncall digest for the team now", 1)));

        assertEquals(2, miner.cluster(openers).size());
    }
}
