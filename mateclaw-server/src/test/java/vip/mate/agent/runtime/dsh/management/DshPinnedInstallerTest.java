package vip.mate.agent.runtime.dsh.management;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class DshPinnedInstallerTest {
    @Test
    void catalogRequiresExactKnownVersionAndCorrectHostPlatform() {
        assertEquals("0.2.0-rc.1", DshPinnedInstaller.release("0.2.0-rc.1").version());
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
                for (var entry : packages) {
                    if (entry.has("resolved")) {
                        assertTrue(entry.path("resolved").asText().startsWith("https://registry.npmjs.org/"));
                        assertTrue(entry.path("integrity").asText().startsWith("sha512-"));
                    }
                }
            }
        }
    }

}
