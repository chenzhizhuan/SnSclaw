package vip.mate.agent.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.progress.ProgressLedgerService.AutoRecordEntry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Pins {@link ProgressLedgerService#clearAutoRecorded} — the new-user-turn
 * housekeeping that drops auto-recorded ({@code auto_}-prefixed) entries.
 *
 * <p>Why it matters: an auto-recorded DONE entry that survives into the
 * next user turn is rendered in the snapshot together with the
 * "已完成的步骤不要重复执行" instruction, which stops the agent from
 * re-running a status-query tool when the user repeats a question that
 * needs fresh data — it answers from the frozen 120-char note instead.
 * Regular (LLM-authored) entries and pinned skill constraints must survive
 * the clear so multi-turn task tracking keeps working.
 */
class ProgressLedgerClearAutoRecordedTest {

    /** In-memory double — same pattern as the concurrency test. */
    private static final class InMemoryProgressLedgerService extends ProgressLedgerService {
        private final Map<String, String> store = new ConcurrentHashMap<>();
        final AtomicInteger saveCount = new AtomicInteger();

        InMemoryProgressLedgerService() {
            super(null, new ObjectMapper().registerModule(new JavaTimeModule()));
        }

        @Override
        protected String loadLedgerJson(String conversationId) {
            return store.get(conversationId);
        }

        @Override
        protected void saveLedgerJson(String conversationId, String json) {
            saveCount.incrementAndGet();
            store.put(conversationId, json);
        }
    }

    @Test
    @DisplayName("clearAutoRecorded drops auto_ entries but keeps regular and pinned entries")
    void clearsOnlyAutoEntries() {
        InMemoryProgressLedgerService service = new InMemoryProgressLedgerService();
        String conv = "conv-clear-1";

        service.upsert(conv, "step_report", "Write report", ProgressStatus.PENDING, null);
        service.upsertPinned(conv, "pin_pdf_0", "Always render via template", null);
        service.upsertAutoRecordedBatch(conv, List.of(
                new AutoRecordEntry("mcp_home_check_room_a1b2c3", "check_room", "0人, 电池0%"),
                new AutoRecordEntry("write_file", "write_file", "ok")));
        assertEquals(4, service.load(conv).size());

        service.clearAutoRecorded(conv);

        ProgressLedger ledger = service.load(conv);
        assertEquals(2, ledger.size(), "only regular + pinned should survive");
        assertTrue(ledger.asMap().containsKey("step_report"));
        assertTrue(ledger.pinnedEntries().containsKey("pin_pdf_0"));
        assertFalse(ledger.asMap().keySet().stream()
                        .anyMatch(k -> k.startsWith(ProgressLedger.AUTO_RECORDED_PREFIX)),
                "no auto_ entry may survive a clear");
    }

    @Test
    @DisplayName("clearAutoRecorded is a no-op (no save) when the ledger has no auto_ entries")
    void noOpWithoutAutoEntries() {
        InMemoryProgressLedgerService service = new InMemoryProgressLedgerService();
        String conv = "conv-clear-2";

        service.upsert(conv, "step_x", "Step X", ProgressStatus.DONE, null);
        int savesBefore = service.saveCount.get();

        service.clearAutoRecorded(conv);

        assertEquals(savesBefore, service.saveCount.get(),
                "clearing an auto-free ledger must not write to the DB");
        assertEquals(1, service.load(conv).size());
    }

    @Test
    @DisplayName("clearAutoRecorded tolerates null/blank conversationId and empty ledgers")
    void toleratesMissingInput() {
        InMemoryProgressLedgerService service = new InMemoryProgressLedgerService();
        assertDoesNotThrow(() -> service.clearAutoRecorded(null));
        assertDoesNotThrow(() -> service.clearAutoRecorded(""));
        assertDoesNotThrow(() -> service.clearAutoRecorded("conv-never-seen"));
        assertNull(((InMemoryProgressLedgerService) service).store.get("conv-never-seen"),
                "clearing an unknown conversation must not create a row");
    }

    @Test
    @DisplayName("auto entries re-recorded after a clear behave normally (fresh note, not the old one)")
    void reRecordAfterClearUsesFreshResult() {
        InMemoryProgressLedgerService service = new InMemoryProgressLedgerService();
        String conv = "conv-clear-3";
        String tool = "mcp_home_check_room_a1b2c3";

        service.upsertAutoRecordedBatch(conv, List.of(
                new AutoRecordEntry(tool, "check_room", "0人, 电池0%")));
        service.clearAutoRecorded(conv);
        // Next turn: the same tool runs again and must record the NEW result —
        // without the clear, upsertAutoRecordedBatch skips existing keys and
        // the note stays frozen at the first call's output.
        service.upsertAutoRecordedBatch(conv, List.of(
                new AutoRecordEntry(tool, "check_room", "2人, 电池87%")));

        ProgressEntry entry = service.load(conv).asMap()
                .get(ProgressLedger.AUTO_RECORDED_PREFIX + tool);
        assertEquals("2人, 电池87%", entry.getNote());
    }
}
