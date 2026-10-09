package vip.mate.agent.runtime.dsh.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.system.service.SettingCrypto;
import vip.mate.agent.runtime.dsh.DshRuntimeService;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.service.SystemSettingService;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DshRuntimeConfigSaveTest {
    @TempDir Path root;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void upgradeDuringConfigResolutionCannotRestoreStalePaths(boolean upgradeBeforeRead) {
        AtomicBoolean interleave = new AtomicBoolean();
        DshGenerationStore store = new DshGenerationStore(root.toString(), new ObjectMapper(), new SettingCrypto("test-key")) {
            @Override public Map<String, String> activeValues() {
                if (!interleave.compareAndSet(true, false)) return super.activeValues();
                Map<String, String> before = super.activeValues();
                beginUpgrade("upgrade", revision());
                activate("upgrade", "new", Map.of("dsh.executable_path", "/new/dsh", "dsh.home_root", root.resolve("new").toString(), "dsh.runtime_version", "0.2.0-rc.1"));
                finishUpgrade("upgrade");
                return upgradeBeforeRead ? super.activeValues() : before;
            }
        };
        store.updateActiveValues(Map.of("dsh.executable_path", "/old/dsh", "dsh.home_root", root.resolve("old").toString(), "dsh.runtime_version", "0.1.7-rc.2"));
        DshRuntimeConfigService config = config(store);
        interleave.set(true);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> config.save(Map.of("dsh.model_name", "edited")));
        assertEquals("dsh.stale_configuration", error.getMessage());
        assertEquals("/new/dsh", store.activeValues().get("dsh.executable_path"));
        assertEquals(root.resolve("new").toString(), store.activeValues().get("dsh.home_root"));
        assertEquals("0.2.0-rc.1", store.activeValues().get("dsh.runtime_version"));
        assertFalse(store.activeValues().containsKey("dsh.model_name"));
    }

    @Test void malformedPatchJsonNeverBecomesActive() {
        DshGenerationStore store = store();
        store.updateActiveValues(Map.of("dsh.patch_paths", "[]"));
        String revision = store.revision();
        assertThrows(IllegalArgumentException.class, () -> config(store).save(Map.of("dsh.patch_paths", "[")));
        assertEquals(revision, store.revision());
        assertEquals("[]", store.activeValues().get("dsh.patch_paths"));
    }

    @Test void validSubmissionCanRepairAlreadyInvalidStoredPatchJson() {
        DshGenerationStore store = store();
        store.updateActiveValues(Map.of("dsh.patch_paths", "["));
        DshRuntimeConfigService config = config(store);
        config.save(Map.of("dsh.patch_paths", "[]"));
        assertTrue(config.resolve().patchPaths().isEmpty());
        assertEquals("[]", store.activeValues().get("dsh.patch_paths"));
    }

    @Test void corruptRuntimeSettingsDoNotPreventAdminServiceStartup() {
        DshGenerationStore store = store();
        store.updateActiveValues(Map.of("dsh.patch_paths", "["));
        assertDoesNotThrow(() -> new DshRuntimeService(new ObjectMapper(), mock(ModelConfigService.class),
                mock(ModelProviderService.class), config(store), new ConversationWindowProperties()));
    }

    private DshGenerationStore store() {
        return new DshGenerationStore(root.toString(), new ObjectMapper(), new SettingCrypto("test-key"));
    }
    private DshRuntimeConfigService config(DshGenerationStore store) {
        DshRuntimeConfigService service = new DshRuntimeConfigService(mock(SystemSettingService.class), "", "", root.toString(), "", "", "");
        ReflectionTestUtils.setField(service, "generations", store);
        return service;
    }
}
