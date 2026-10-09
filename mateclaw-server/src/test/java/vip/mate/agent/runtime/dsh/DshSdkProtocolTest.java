package vip.mate.agent.runtime.dsh;

import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.workspace.core.service.MemberFileIsolation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.runtime.dsh.management.*;
import vip.mate.system.service.SystemSettingService;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic subprocess fixtures exercise host failure handling, not real DSH compatibility. */
class DshSdkProtocolTest {
    @TempDir Path directory;

    @Test void sleepingProcessIsNotSuccessfulHandshake() throws Exception {
        Path executable = script("sleep 10");
        var configuration = new DshRuntimeConfiguration(executable.toString(), "", directory.toString(), "", "model", "",
                "sdk", List.of(), directory.resolve("homes").toString());
        DshRuntimeService service = runtime(configuration);
        ReflectionTestUtils.setField(service, "initializeTimeoutMs", 100L);
        assertEquals(false, service.testConnection(configuration).get("success"));
    }

    @Test void legacyCordisIsMigrationRequiredEvenWhenFilesExist() throws Exception {
        Path executable = script("exit 0");
        Path cordis = Files.writeString(directory.resolve("cordis.yml"), "{}");
        var config = mock(DshRuntimeConfigService.class);
        when(config.resolve()).thenReturn(new DshRuntimeConfiguration(executable.toString(), cordis.toString(), directory.toString(), "", "model", ""));
        var service = new DshManagementService(config, mock(DshArtifactInstaller.class), mock(SystemSettingService.class));
        assertEquals("MIGRATION_REQUIRED", service.status().get("state"));
    }

    @Test void healthCheckRejectsMemberIsolationBeforeSpawn() throws Exception {
        Path marker = directory.resolve("started");
        Path executable = script("touch '" + marker + "'");
        var configuration = new DshRuntimeConfiguration(executable.toString(), "", directory.toString(), "", "model", "");
        try {
            MemberFileIsolation.configure(origin -> origin);
            assertEquals(false, runtime(configuration).testTask(configuration).get("success"));
            assertFalse(Files.exists(marker));
        } finally { MemberFileIsolation.configure(null); }
    }

    private DshRuntimeService runtime(DshRuntimeConfiguration configuration) {
        var config = mock(DshRuntimeConfigService.class);
        when(config.resolve()).thenReturn(configuration);
        return new DshRuntimeService(new ObjectMapper(), mock(ModelConfigService.class),
                mock(ModelProviderService.class), config, new ConversationWindowProperties());
    }

    private Path script(String body) throws Exception {
        Path file = Files.writeString(directory.resolve("synthetic dsh"), "#!/bin/sh\n" + body + "\n");
        file.toFile().setExecutable(true);
        return file;
    }
}
