package vip.mate.agent.runtime.dsh;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.runtime.dsh.management.DshRuntimeConfiguration;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DshLaunchSpecTest {
    @TempDir Path directory;
    @Test void preservesExecutableSpacesPatchOrderAndIsolatesAgentHomes() throws Exception {
        Path executable = Files.writeString(directory.resolve("dsh binary"), "#!/bin/sh\nexit 0\n");
        executable.toFile().setExecutable(true);
        Path first = Files.writeString(directory.resolve("first patch.yml"), "{}");
        Path second = Files.writeString(directory.resolve("second.yml"), "{}");
        var config = new DshRuntimeConfiguration(executable.toString(), "", directory.toString(), "", "", "",
                "sdk", List.of(first.toString(), second.toString()), directory.resolve("homes").toString());
        var launch = DshLaunchSpec.create(config, 1L, 2L, directory);
        assertEquals(List.of(executable.toString(), "--profile", "sdk", "--patch", first.toString(), "--patch", second.toString()), launch.command());
        assertNotEquals(launch.home(), DshLaunchSpec.create(config, 1L, 3L, directory).home());
        assertEquals(launch.home().toString(), launch.processBuilder(Map.of()).environment().get("DSH_HOME"));
    }
    @Test void rejectsLegacyCommandAndCordis() {
        var config = new DshRuntimeConfiguration("/bin/sh --stdio", "", directory.toString(), "", "", "");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DshLaunchSpec.create(config, 1, 2, directory)).getMessage().startsWith("MIGRATION_REQUIRED"));
    }
    @Test void normalizesOnlyOfficialApiRoute() {
        assertEquals("https://api.deepseek.com/anthropic", DshRuntimeService.normalizeBaseUrl("https://api.deepseek.com"));
        assertEquals("https://api.deepseek.com/anthropic", DshRuntimeService.normalizeBaseUrl("https://api.deepseek.com/v1/"));
        assertEquals("https://custom.example/v1", DshRuntimeService.normalizeBaseUrl("https://custom.example/v1"));
    }
    @Test void workspaceSymlinkIsRejectedBeforeCreatingAgentHomeOutsideRoot() throws Exception {
        Path root = Files.createDirectory(directory.resolve("homes"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        var config = new DshRuntimeConfiguration("/bin/sh", "", directory.toString(), "", "", "", "sdk", List.of(), root.toString());
        var launch = DshLaunchSpec.create(config, 1, 2, directory);
        Files.createSymbolicLink(launch.home().getParent(), outside);
        assertThrows(IOException.class, () -> launch.processBuilder(Map.of()));
        assertFalse(Files.exists(outside.resolve(launch.home().getFileName())), "validation must precede all writes through the link");
    }

    @Test void homeRootSymlinkIsRejectedWithoutWritingToItsTarget() throws Exception {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path root = Files.createSymbolicLink(directory.resolve("homes"), outside);
        var config = new DshRuntimeConfiguration("/bin/sh", "", directory.toString(), "", "", "", "sdk", List.of(), root.toString());
        var launch = DshLaunchSpec.create(config, 1, 2, directory);
        assertThrows(IOException.class, () -> launch.processBuilder(Map.of()));
        try (var entries = Files.list(outside)) { assertEquals(0, entries.count()); }
    }

}
