package vip.mate.goal.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

class CrashRecoveryProcessTest {
    @TempDir Path folder;

    @Test
    void processHaltAfterExternalEffectBlocksRecoveryAndAnotherInvocation() throws Exception {
        String crash = run("crash", 73);
        assertTrue(crash.contains("independent committed tool_started/uncertain"));
        assertEquals("one effect\n", Files.readString(folder.resolve("effect")));
        String recovered = run("recover", 0);
        assertTrue(recovered.contains("BLOCKED, due=0, stale checkpoint denied, effects=1"));
        assertEquals("one effect\n", Files.readString(folder.resolve("effect")));
    }

    @Test
    void processHaltAtSafeProviderCheckpointRemainsRetryable() throws Exception {
        assertTrue(run("safe", 73).contains("provider_started committed; no tool entry"));
        assertTrue(run("recover-safe", 0).contains("RETRY_SAFE, due=1, effects=0"));
        assertFalse(Files.exists(folder.resolve("effect")));
    }

    private String run(String mode, int expectedExit) throws Exception {
        Path output = folder.resolve(mode + ".log");
        String classpath =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                classpath,
                                CrashRecoveryWorker.class.getName(),
                                mode,
                                folder.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Worker timeout: " + mode);
            String text = Files.readString(output, StandardCharsets.UTF_8);
            assertEquals(expectedExit, process.exitValue(), text);
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
