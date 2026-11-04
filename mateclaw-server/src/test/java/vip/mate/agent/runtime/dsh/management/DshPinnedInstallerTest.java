package vip.mate.agent.runtime.dsh.management;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class DshPinnedInstallerTest {
    @Test
    void catalogRequiresExactKnownVersionAndCorrectHostPlatform() {
        assertEquals("0.2.0-rc.1", DshPinnedInstaller.release("0.2.0-rc.1").version());
        assertTrue(DshPinnedInstaller.catalog().stream().anyMatch(release -> release.version().equals("0.2.1-rc.2")));
        assertEquals("sha512-tvY+yhfKRifthbqPe/OK0AYxM5ZkvqfYq/jMYgJcEUxiRRL1Vwra3Ti5TB6AXgSWQdiPHYh/Vp6SJa9yHwMNuw==",
                DshPinnedInstaller.release("0.2.1-rc.2").integrity());
        assertTrue(DshPinnedInstaller.release("0.1.7-rc.2").integrity().startsWith("sha512-"));
        assertThrows(IllegalArgumentException.class, () -> DshPinnedInstaller.release("latest"));
        assertThrows(IllegalArgumentException.class, () -> DshPinnedInstaller.release("1.0.0"));
        assertEquals("linux-x64", DshPinnedInstaller.platform("Linux", "amd64"));
        assertEquals("darwin-arm64", DshPinnedInstaller.platform("Mac OS X", "aarch64"));
        assertThrows(IllegalArgumentException.class, () -> DshPinnedInstaller.platform("Windows", "amd64"));
    }
    @Test
    void trustedLocksPinAllRegistryPackagesBeforeInstallScripts() throws Exception {
        var mapper = new ObjectMapper();
        for (var release : DshPinnedInstaller.catalog()) {
            try (var input = getClass().getResourceAsStream("/dsh/locks/" + release.version() + ".json")) {
                assertNotNull(input);
                var packages = mapper.readTree(input).path("packages");
                assertEquals(release.integrity(), packages.path("node_modules/@deepseek-ai/dsh").path("integrity").asText());
                assertTrue(packages.size() > 100);
                var entries = packages.fields();
                while (entries.hasNext()) {
                    var entry = entries.next();
                    if (entry.getKey().isEmpty()) continue; // local root manifest
                    assertTrue(entry.getValue().path("resolved").asText().startsWith("https://registry.npmjs.org/"), entry.getKey());
                    assertTrue(entry.getValue().path("integrity").asText().startsWith("sha512-"), entry.getKey());
                }
            }
        }
    }

}
