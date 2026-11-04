package vip.mate.team.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TeamResultExcerptTest {

    @Test
    void shortResultRemainsVerbatim() {
        String json = "{\"path\":\"report.docx\",\"verified\":true}";
        assertEquals(json, TeamResultExcerpt.render(json, 300, 42L));
        assertEquals("", TeamResultExcerpt.render(null, 300, 42L));
    }

    @Test
    void longResultPreservesOpeningAndFinalVerificationWithinStrictBudget() {
        String full = "Produced report.docx\n" + "body\n".repeat(2000)
                + "\nVerification: 18 tests passed; remaining issue: missing owner approval.";

        String excerpt = TeamResultExcerpt.render(full, 1500, 42L);

        assertTrue(excerpt.length() <= 1500);
        assertTrue(excerpt.startsWith("Produced report.docx"));
        assertTrue(excerpt.endsWith("remaining issue: missing owner approval."));
        assertTrue(excerpt.contains("team_tasks(action=\"output\", taskId=\"42\")"));
        assertTrue(excerpt.contains("Do not infer omitted content"));
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 257, 300, 999, 1500, 8000})
    void excerptsNeverSplitUnicodeSurrogatePairs(int budget) {
        String full = "🙂".repeat(10000);
        String excerpt = TeamResultExcerpt.render(full, budget, Long.MAX_VALUE);

        assertTrue(excerpt.length() <= budget);
        for (int index = 0; index < excerpt.length(); index++) {
            char ch = excerpt.charAt(index);
            if (Character.isHighSurrogate(ch)) {
                assertTrue(index + 1 < excerpt.length());
                assertTrue(Character.isLowSurrogate(excerpt.charAt(++index)));
            } else {
                assertFalse(Character.isLowSurrogate(ch));
            }
        }
    }
}
