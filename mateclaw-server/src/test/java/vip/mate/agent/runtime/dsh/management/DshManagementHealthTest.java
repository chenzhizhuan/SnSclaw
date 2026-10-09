package vip.mate.agent.runtime.dsh.management;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.runtime.dsh.DshRuntimeService;
import vip.mate.system.service.SystemSettingService;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DshManagementHealthTest {
    private final DshRuntimeConfigService config = mock(DshRuntimeConfigService.class);
    private final DshRuntimeService runtime = mock(DshRuntimeService.class);
    private final DshManagementService management = new DshManagementService(config,
            mock(DshArtifactInstaller.class), mock(SystemSettingService.class));

    @BeforeEach
    void setup() {
        when(config.revision()).thenReturn("revision-1");
        when(config.resolve()).thenReturn(new DshRuntimeConfiguration("/bin/sh", "",
                Path.of(System.getProperty("java.io.tmpdir")).toString(), "", "model", "secret"));
        ReflectionTestUtils.setField(management, "runtime", runtime);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exceptionDuringRecheckInvalidatesPreviousSuccess(boolean task) {
        if (task) when(runtime.testTask(any())).thenReturn(Map.of("success", true))
                .thenThrow(new IllegalStateException("private credential"));
        else when(runtime.testConnection(any())).thenReturn(Map.of("success", true))
                .thenThrow(new IllegalStateException("private credential"));
        assertEquals(true, (task ? management.testTask() : management.testConnection()).get("success"));
        Map<String, Object> failed = task ? management.testTask() : management.testConnection();
        assertEquals(false, failed.get("success"));
        Map<?, ?> displayed = (Map<?, ?>) management.status().get(task ? "taskCheck" : "handshake");
        assertEquals(false, displayed.get("success"));
        assertFalse(displayed.toString().contains("private credential"));
    }

    @Test
    void busyAdmissionClearsOldHandshakeWithoutStartingAnotherProcess() {
        when(runtime.testConnection(any())).thenReturn(Map.of("success", true));
        management.testConnection();
        DshGenerationStore store = mock(DshGenerationStore.class);
        when(config.generations()).thenReturn(store);
        when(store.acquireLease("health", "health")).thenThrow(new IllegalStateException("dsh.home_busy"));
        assertEquals(false, management.testConnection().get("success"));
        Map<?, ?> displayed = (Map<?, ?>) management.status().get("handshake");
        assertNotEquals(true, displayed.get("success"));
        verify(runtime, times(1)).testConnection(any());
    }

    @Test
    void invalidStoredConfigStillExposesEditableMaskedValues() {
        when(config.resolve()).thenThrow(new IllegalArgumentException("private malformed input"));
        when(config.managedValues()).thenReturn(Map.of("dsh.patch_paths", "[", "dsh.api_key", "****"));
        Map<String, Object> status = assertDoesNotThrow(management::status);
        assertEquals("CONFIG_INVALID", status.get("state"));
        assertEquals(Map.of("dsh.patch_paths", "[", "dsh.api_key", "****"), status.get("managed"));
        assertFalse(status.toString().contains("private malformed input"));
        assertEquals(false, management.verify().get("verified"));
    }
}
