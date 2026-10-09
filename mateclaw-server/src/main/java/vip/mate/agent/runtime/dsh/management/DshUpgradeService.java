package vip.mate.agent.runtime.dsh.management;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.agent.runtime.dsh.DshRuntimeService;
import vip.mate.workspace.core.service.MemberFileIsolation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Asynchronous, journaled preparation and activation of isolated runtime generations. */
@Service
public class DshUpgradeService {
    private final DshGenerationStore store;
    private final DshRuntimeConfigService config;
    private final DshRuntimeService runtime;
    private final DshPinnedInstaller installer;
    private final ObjectMapper mapper;
    private final Executor executor;

    @Autowired
    public DshUpgradeService(DshGenerationStore store, DshRuntimeConfigService config,
                             DshRuntimeService runtime, DshPinnedInstaller installer, ObjectMapper mapper) {
        this(store, config, runtime, installer, mapper, task -> Thread.ofVirtual().name("dsh-upgrade").start(task));
    }

    DshUpgradeService(DshGenerationStore store, DshRuntimeConfigService config,
                      DshRuntimeService runtime, DshPinnedInstaller installer, ObjectMapper mapper, Executor executor) {
        this.store = store; this.config = config; this.runtime = runtime; this.installer = installer;
        this.mapper = mapper; this.executor = executor;
    }

    public Map<String, Object> status() {
        return Map.of("configRevision", store.revision(), "activeGeneration", store.activeGeneration(),
                "activeVersion", store.activeValues().getOrDefault("dsh.runtime_version", "unknown"),
                "catalog", DshPinnedInstaller.catalog(), "supportStatus", "CANDIDATE_REQUIRES_VALIDATION");
    }

    public Map<String, String> upgrade(String targetVersion, String expectedRevision, String idempotencyKey) {
        DshPinnedInstaller.release(targetVersion);
        return submit("upgrade", targetVersion, "", expectedRevision, idempotencyKey);
    }

    public Map<String, String> rollback(String originalOperation, String expectedRevision, String idempotencyKey) {
        Map<String, String> original = store.operation(originalOperation);
        if (!"COMPLETED".equals(original.get("state")) || original.getOrDefault("previousGeneration", "").isBlank()) {
            throw new IllegalStateException("dsh.rollback_unavailable");
        }
        if (idempotencyKey == null || !idempotencyKey.matches("[a-zA-Z0-9-]{1,100}")) throw new IllegalArgumentException("dsh.invalid_idempotency_key");
        Map<String, String> prior = null;
        try { prior = operation(digest("rollback:" + idempotencyKey)); }
        catch (IllegalArgumentException missing) {
            if (!"dsh.operation_missing".equals(missing.getMessage())) throw missing;
        }
        if (prior != null) {
            if (!original.get("previousGeneration").equals(prior.get("restoreGeneration"))
                    || !prior.get("expectedRevision").equals(expectedRevision)) throw new IllegalStateException("dsh.idempotency_conflict");
            return prior;
        }
        if (!original.get("candidateGeneration").equals(store.activeGeneration())) {
            throw new IllegalStateException("dsh.rollback_generation_changed");
        }
        String previous = original.get("previousGeneration");
        return submit("rollback", store.generationValues(previous).getOrDefault("dsh.runtime_version", "unknown"),
                previous, expectedRevision, idempotencyKey);
    }

    public Map<String, String> operation(String id) { return store.operation(id); }

    private Map<String, String> submit(String kind, String version, String restore, String revision, String key) {
        if (MemberFileIsolation.isEnabled()) throw new SecurityException("DSH runtime is unavailable in member file isolation mode");
        if (key == null || !key.matches("[a-zA-Z0-9-]{1,100}")) throw new IllegalArgumentException("dsh.invalid_idempotency_key");
        String id = digest(kind + ":" + key);
        Map<String, String> op = DshGenerationStore.owner();
        op.putAll(Map.of("id", id, "kind", kind, "targetVersion", version, "restoreGeneration", restore,
                "expectedRevision", revision == null ? "" : revision, "state", "QUEUED", "createdAt", Instant.now().toString()));
        if (store.createOperation(id, op)) executor.execute(() -> run(new HashMap<>(op)));
        Map<String, String> existing = operation(id);
        if (!version.equals(existing.get("targetVersion")) || !op.get("expectedRevision").equals(existing.get("expectedRevision"))
                || !restore.equals(existing.get("restoreGeneration"))) throw new IllegalStateException("dsh.idempotency_conflict");
        return existing;
    }

    private void run(Map<String, String> op) {
        String id = op.get("id");
        boolean gate = false;
        try {
            String candidate = "run-" + UUID.randomUUID();
            op.put("candidateGeneration", candidate);
            Path directory = store.root().resolve("releases").resolve(candidate);
            transition(op, "PREPARING", "Preparing an isolated candidate");
            DshPinnedInstaller.Installation installation = null;
            if (op.get("kind").equals("upgrade")) installation = installer.install(op.get("targetVersion"), directory.resolve("package"));
            store.beginUpgrade(id, op.get("expectedRevision"));
            gate = true;
            transition(op, "DRAINING", "Waiting for active DSH turns; new DSH turns are temporarily blocked");
            if (!store.awaitDrained(Duration.ofSeconds(60))) throw new IllegalStateException("dsh.drain_timeout");
            Map<String, String> current = snapshot(config.resolve());
            current.putAll(store.activeValues());
            String previous = store.activeGeneration();
            if (previous.isBlank()) {
                previous = "baseline-" + UUID.randomUUID();
                store.saveSnapshot(id, previous, current);
            }
            op.put("previousGeneration", previous);
            Map<String, String> candidateValues = op.get("kind").equals("rollback")
                    ? store.generationValues(op.get("restoreGeneration")) : new HashMap<>(current);
            Path source = Path.of(candidateValues.get("dsh.home_root")).toAbsolutePath().normalize();
            Path home = directory.resolve("homes");
            transition(op, "SNAPSHOTTING", "Copying runtime data; original homes remain unchanged");
            copyHome(source, home);
            candidateValues.put("dsh.home_root", home.toString());
            snapshotPatches(candidateValues, directory.resolve("patches"));
            if (installation != null) {
                candidateValues.put("dsh.executable_path", installation.executablePath());
                candidateValues.put("dsh.runtime_version", installation.version());
                candidateValues.put("dsh.platform", installation.platform());
                candidateValues.put("dsh.integrity", installation.integrity());
                candidateValues.put("dsh.profile", "sdk");
                candidateValues.put("dsh.cordis_config_path", "");
            }
            transition(op, "CHECKING", "Checking candidate SDK and model task");
            DshRuntimeConfiguration resolved = DshRuntimeConfigResolver.resolve(candidateValues, Map.of(), Map.of());
            List<Path> homes = new ArrayList<>();
            try (var paths = Files.walk(home)) {
                paths.filter(path -> path.getFileName().toString().equals("profiles") && Files.isDirectory(path))
                        .map(Path::getParent).forEach(homes::add);
            }
            if (homes.isEmpty()) homes.add(Files.createDirectories(home.resolve("candidate-check")));
            int checked = 0;
            for (Path affectedHome : homes) {
                Map<String, Object> handshake = runtime.testConnectionAtHome(resolved, affectedHome);
                op.put("handshakeStatus", Boolean.TRUE.equals(handshake.get("success")) ? "PASSED" : "FAILED");
                op.put("handshakeCheckedAt", Instant.now().toString());
                store.saveOperation(id, op);
                if (!Boolean.TRUE.equals(handshake.get("success"))) throw new IllegalStateException("dsh.candidate_handshake_failed");
                Map<String, Object> task = runtime.testTaskAtHome(resolved, affectedHome);
                op.put("taskStatus", Boolean.TRUE.equals(task.get("success")) ? "PASSED" : "FAILED");
                op.put("taskCheckedAt", Instant.now().toString());
                op.put("homesChecked", Integer.toString(++checked));
                store.saveOperation(id, op);
                if (!Boolean.TRUE.equals(task.get("success"))) throw new IllegalStateException("dsh.candidate_model_check_failed");
            }
            transition(op, "ACTIVATING", "Candidate checks passed; committing generation");
            store.activate(id, candidate, candidateValues);
            transition(op, "COMPLETED", "Version and home switched together; old generation retained");
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if (op.getOrDefault("candidateGeneration", "!").equals(store.activeGeneration())) {
                transition(op, "COMPLETED", "Activation committed; recovered completion record");
                return;
            }
            String message = error.getMessage();
            transition(op, "FAILED", message != null && message.matches("dsh\\.[a-z_]+")
                    ? message : "Candidate preparation failed; active generation retained");
        } finally {
            if (gate) store.finishUpgrade(id);
        }
    }

    private Map<String, String> snapshot(DshRuntimeConfiguration c) throws IOException {
        Map<String, String> values = new HashMap<>();
        values.put("dsh.executable_path", c.executablePath());
        values.put("dsh.cordis_config_path", c.cordisConfigPath());
        values.put("dsh.working_directory", c.workingDirectory());
        values.put("dsh.base_url", c.baseUrl());
        values.put("dsh.model_name", c.modelName());
        values.put("dsh.api_key", c.apiKey());
        values.put("dsh.profile", c.profile());
        values.put("dsh.patch_paths", mapper.writeValueAsString(c.patchPaths()));
        values.put("dsh.home_root", c.homeRoot());
        values.replaceAll((k, v) -> v == null ? "" : v);
        return values;
    }

    private void snapshotPatches(Map<String, String> values, Path destination) throws IOException {
        List<String> patches = mapper.readValue(values.getOrDefault("dsh.patch_paths", "[]"), new TypeReference<>() {});
        List<String> copied = new ArrayList<>();
        for (int i = 0; i < patches.size(); i++) {
            Path path = Path.of(patches.get(i));
            if (!path.isAbsolute() || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IOException("Unsafe patch");
            Files.createDirectories(destination);
            Path target = destination.resolve(i + ".yml");
            Files.copy(path, target);
            copied.add(target.toString());
        }
        values.put("dsh.patch_paths", mapper.writeValueAsString(copied));
    }

    static void copyHome(Path source, Path destination) throws IOException {
        source = source.toAbsolutePath().normalize();
        destination = destination.toAbsolutePath().normalize();
        if (destination.startsWith(source) || source.startsWith(destination)) throw new IOException("Overlapping home snapshot");
        if (Files.isSymbolicLink(source)) throw new IOException("Home symlinks require explicit migration");
        Files.createDirectories(destination);
        if (!Files.exists(source)) return;
        final Path from = source;
        final Path to = destination;
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir))); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("Home symlinks and special files require explicit migration");
                Files.copy(file, to.resolve(from.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void transition(Map<String, String> op, String state, String message) {
        op.put("state", state); op.put("message", message); op.put("updatedAt", Instant.now().toString());
        store.saveOperation(op.get("id"), op);
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
