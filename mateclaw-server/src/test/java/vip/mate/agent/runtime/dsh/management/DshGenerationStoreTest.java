package vip.mate.agent.runtime.dsh.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.system.service.SettingCrypto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DshGenerationStoreTest {
    @TempDir Path root;

    private DshGenerationStore store() {
        return new DshGenerationStore(root.toString(), new ObjectMapper(), new SettingCrypto("test-encryption-key"));
    }

    @Test
    void activationAndRollbackKeepVersionDataPairsAndEncryptCredentials() throws Exception {
        DshGenerationStore store = store();
        store.beginUpgrade("op1", store.revision());
        store.activate("op1", "g1", Map.of("dsh.runtime_version", "0.1.7-rc.2", "dsh.home_root", root.resolve("g1/home").toString(), "dsh.api_key", "secret-value"));
        store.finishUpgrade("op1");
        String oldRevision = store.revision();
        store.beginUpgrade("op2", oldRevision);
        store.activate("op2", "g2", Map.of("dsh.runtime_version", "0.2.0-rc.1", "dsh.home_root", root.resolve("g2/home").toString(), "dsh.api_key", "new-secret"));
        store.finishUpgrade("op2");
        assertEquals("new-secret", store.activeValues().get("dsh.api_key"));
        assertFalse(Files.readString(root.resolve("generations/g2.json")).contains("new-secret"));
        store.beginUpgrade("rollback", store.revision());
        store.restore("rollback", "g1");
        store.finishUpgrade("rollback");
        assertEquals("secret-value", store().activeValues().get("dsh.api_key"));
        assertEquals("0.1.7-rc.2", store().activeValues().get("dsh.runtime_version"));
        assertTrue(Files.exists(root.resolve("generations/g2.json")));
    }

    @Test
    void drainDoesNotKillActiveWorkAndAdmissionResumesAfterAbort() throws Exception {
        DshGenerationStore store = store();
        try (var lease = store.acquireLease("1", "2")) {
            assertThrows(IllegalStateException.class, () -> store.acquireLease("1", "2"));
            store.beginUpgrade("upgrade", store.revision());
            assertThrows(IllegalStateException.class, () -> store.acquireLease("3", "4"));
            assertFalse(store.awaitDrained(Duration.ofMillis(10)));
            store.finishUpgrade("upgrade");
            try (var second = store.acquireLease("3", "4")) { assertNotNull(second); }
        }
        assertTrue(store.awaitDrained(Duration.ofMillis(10)));
    }

    @Test
    void staleRevisionAndUnsafeGenerationCannotActivate() throws Exception {
        DshGenerationStore store = store();
        assertThrows(IllegalStateException.class, () -> store.beginUpgrade("op", "outdated"));
        store.beginUpgrade("op", store.revision());
        assertThrows(IllegalArgumentException.class, () -> store.activate("op", "../escape", Map.of()));
        assertTrue(store.activeValues().isEmpty());
        store.finishUpgrade("op");
    }

    @Test
    void activationCannotBypassDrainOrMutateDuringUpgrade() throws Exception {
        DshGenerationStore store = store();
        try (var lease = store.acquireLease("1", "2")) {
            store.beginUpgrade("op", store.revision());
            assertThrows(IllegalStateException.class, () -> store.activate("op", "g", Map.of()));
            assertThrows(IllegalStateException.class, () -> store.updateActiveValues(Map.of("dsh.model_name", "changed")));
            store.finishUpgrade("op");
        }
    }
    @Test
    void deadJvmLeaseDoesNotAdmitPotentialOrphanWriters() throws Exception {
        DshGenerationStore store = store();
        store.revision();
        Path leases = Files.createDirectories(root.resolve("leases"));
        new ObjectMapper().writeValue(leases.resolve("orphan.json").toFile(), Map.of("pid", "0", "started", "", "home", "1:2"));
        assertThrows(IllegalStateException.class, () -> store.acquireLease("1", "2"));
        assertTrue(Files.exists(leases.resolve("orphan.json")));
    }

    @Test
    void committedCrashIsReconciledBeforeSubsequentConfigWrite() throws Exception {
        DshGenerationStore store = store();
        store.beginUpgrade("op", store.revision());
        store.saveOperation("op", Map.of("pid", "0", "started", "", "state", "ACTIVATING", "candidateGeneration", "g"));
        store.activate("op", "g", Map.of("dsh.model_name", "old"));
        new ObjectMapper().writeValue(root.resolve("gate.json").toFile(), Map.of("pid", "0", "started", "", "operation", "op"));
        store.updateActiveValues(Map.of("dsh.model_name", "new"));
        assertEquals("COMPLETED", store.operation("op").get("state"));
        assertEquals("new", store.activeValues().get("dsh.model_name"));
    }

}
