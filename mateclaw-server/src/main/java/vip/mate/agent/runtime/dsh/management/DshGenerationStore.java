package vip.mate.agent.runtime.dsh.management;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.system.service.SettingCrypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Durable generation selection and process-shared admission for local runtimes. */
@Component
public class DshGenerationStore {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final TypeReference<Map<String, String>> VALUES = new TypeReference<>() {};
    private final Path root;
    private final ObjectMapper mapper;
    private final SettingCrypto crypto;

    public DshGenerationStore(
            @Value("${mateclaw.agent.runtime.dsh.install-root:${user.home}/.mateclaw/runtimes/deepseek-harness}") String root,
            ObjectMapper mapper, SettingCrypto crypto) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.crypto = crypto;
    }

    public Path root() { return root; }

    public String revision() { return locked(() -> pointer().getOrDefault("revision", "0")); }
    public String activeGeneration() { return locked(() -> pointer().getOrDefault("generation", "")); }
    public Map<String, String> activeValues() {
        return locked(() -> readGeneration(pointer().getOrDefault("generation", "")));
    }
    public Map<String, String> generationValues(String generation) {
        return locked(() -> readGeneration(generation));
    }

    /** Keeps configuration changes in the same atomic domain as activation. */
    public void updateActiveValues(Map<String, String> values) {
        updateActiveValues(values, null);
    }

    /** A resolved configuration snapshot may only replace the revision it read. */
    public void updateActiveValues(Map<String, String> values, String expectedRevision) {
        locked(() -> {
            requireWritable();
            Map<String, String> active = pointer();
            if (expectedRevision != null && !active.getOrDefault("revision", "0").equals(expectedRevision))
                throw new IllegalStateException("dsh.stale_configuration");
            Map<String, String> merged = readGeneration(active.getOrDefault("generation", ""));
            merged.putAll(values);
            writeGeneration("config-" + UUID.randomUUID(), merged);
            return null;
        });
    }

    public Lease acquireLease(String workspaceId, String agentId) {
        return locked(() -> {
            requireWritable();
            pruneLeases();
            String key = workspaceId + ":" + agentId;
            try (var paths = Files.list(directory("leases"))) {
                for (Path path : paths.toList()) {
                    if (key.equals(read(path).get("home"))) throw new IllegalStateException("dsh.home_busy");
                }
            }
            Path file = directory("leases").resolve(UUID.randomUUID() + ".json");
            Map<String, String> lease = owner();
            lease.put("home", key);
            write(file, lease);
            return new Lease(file);
        });
    }

    public void beginUpgrade(String operationId, String expectedRevision) {
        safeId(operationId);
        locked(() -> {
            requireWritable();
            if (!pointer().getOrDefault("revision", "0").equals(expectedRevision)) {
                throw new IllegalStateException("dsh.stale_configuration");
            }
            Map<String, String> gate = owner();
            gate.put("operation", operationId);
            write(root.resolve("gate.json"), gate);
            return null;
        });
    }

    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (locked(() -> { pruneLeases(); try (var files = Files.list(directory("leases"))) { return files.findAny().isEmpty(); } })) return true;
            if (System.nanoTime() >= deadline) return false;
            Thread.sleep(Math.min(50, Math.max(1, timeout.toMillis())));
        } while (true);
    }

    public void activate(String operationId, String generation, Map<String, String> values) {
        safeId(generation);
        locked(() -> {
            requireGate(operationId);
            requireDrained();
            writeGeneration(generation, values);
            return null;
        });
    }

    /** Saves the pre-upgrade configuration without changing the committed active pointer. */
    public void saveSnapshot(String operationId, String generation, Map<String, String> values) {
        safeId(generation);
        locked(() -> {
            requireGate(operationId);
            requireDrained();
            writeGenerationSnapshot(generation, values);
            return null;
        });
    }

    public void restore(String operationId, String generation) {
        safeId(generation);
        locked(() -> {
            requireGate(operationId);
            requireDrained();
            if (!Files.isRegularFile(directory("generations").resolve(generation + ".json"))) throw new IllegalArgumentException("dsh.generation_missing");
            write(root.resolve("active.json"), Map.of("generation", generation, "revision", UUID.randomUUID().toString()));
            return null;
        });
    }

    public void finishUpgrade(String operationId) {
        locked(() -> {
            Map<String, String> gate = read(root.resolve("gate.json"));
            if (operationId.equals(gate.get("operation"))) {
                Map<String, String> op = read(directory("operations").resolve(operationId + ".json"));
                if (op.isEmpty() || isTerminal(op.get("state"))) Files.deleteIfExists(root.resolve("gate.json"));
            }
            return null;
        });
    }

    public boolean createOperation(String id, Map<String, String> operation) {
        safeId(id);
        return locked(() -> {
            Path file = directory("operations").resolve(id + ".json");
            if (Files.exists(file)) return false;
            write(file, operation);
            return true;
        });
    }

    public void saveOperation(String id, Map<String, String> operation) {
        safeId(id);
        locked(() -> { write(directory("operations").resolve(id + ".json"), operation); return null; });
    }

    /** Reconciles interrupted operations from the durable activation pointer. */
    public Map<String, String> operation(String id) {
        safeId(id);
        return locked(() -> {
            Map<String, String> op = read(directory("operations").resolve(id + ".json"));
            if (op.isEmpty()) throw new IllegalArgumentException("dsh.operation_missing");
            boolean committedPointer = op.getOrDefault("candidateGeneration", "!").equals(pointer().get("generation"));
            if ((!isOwnerAlive(op) || committedPointer) && !isTerminal(op.get("state"))) {
                boolean committed = op.getOrDefault("candidateGeneration", "!").equals(pointer().get("generation"));
                op.put("state", committed ? "COMPLETED" : "INTERRUPTED");
                op.put("message", committed ? "Activation committed before restart" : "Upgrade interrupted; active generation retained");
                write(directory("operations").resolve(id + ".json"), op);
            }
            return op;
        });
    }

    public static Map<String, String> owner() {
        Map<String, String> values = new HashMap<>();
        values.put("pid", Long.toString(ProcessHandle.current().pid()));
        values.put("started", ProcessHandle.current().info().startInstant().map(Object::toString).orElse(""));
        return values;
    }

    private static boolean isTerminal(String state) {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "INTERRUPTED".equals(state);
    }

    private void writeGeneration(String id, Map<String, String> values) throws IOException {
        writeGenerationSnapshot(id, values);
        write(root.resolve("active.json"), Map.of("generation", id, "revision", UUID.randomUUID().toString()));
    }

    private void writeGenerationSnapshot(String id, Map<String, String> values) throws IOException {
        safeId(id);
        Map<String, String> stored = new HashMap<>(values);
        stored.replaceAll((key, value) -> value == null ? "" : value);
        stored.computeIfPresent("dsh.api_key", (key, value) -> crypto.encrypt(value));
        write(directory("generations").resolve(id + ".json"), stored);
    }

    private Map<String, String> readGeneration(String id) throws IOException {
        if (id.isBlank()) return new HashMap<>();
        safeId(id);
        Path path = directory("generations").resolve(id + ".json");
        if (!Files.isRegularFile(path)) throw new IllegalStateException("dsh.active_generation_missing");
        Map<String, String> values = read(path);
        values.computeIfPresent("dsh.api_key", (key, value) -> crypto.decrypt(value));
        return values;
    }

    private void requireGate(String id) throws IOException {
        if (!id.equals(read(root.resolve("gate.json")).get("operation"))) throw new IllegalStateException("dsh.upgrade_ownership_lost");
    }

    private void requireDrained() throws IOException {
        pruneLeases();
        try (var files = Files.list(directory("leases"))) {
            if (files.findAny().isPresent()) throw new IllegalStateException("dsh.active_turns");
        }
    }

    private void requireWritable() throws IOException {
        Map<String, String> gate = read(root.resolve("gate.json"));
        if (gate.isEmpty()) return;
        String operation = gate.get("operation");
        Map<String, String> journalState = operation == null ? Map.of() : read(directory("operations").resolve(operation + ".json"));
        boolean committed = journalState.getOrDefault("candidateGeneration", "!").equals(pointer().get("generation"));
        if (isOwnerAlive(gate) && !committed && !isTerminal(journalState.get("state"))) {
            throw new IllegalStateException("dsh.upgrade_in_progress");
        }
        if (operation != null) {
            Path journal = directory("operations").resolve(operation + ".json");
            Map<String, String> op = read(journal);
            if (!op.isEmpty() && !isTerminal(op.get("state"))) {
                boolean wasCommitted = op.getOrDefault("candidateGeneration", "!").equals(pointer().get("generation"));
                op.put("state", wasCommitted ? "COMPLETED" : "INTERRUPTED");
                op.put("message", wasCommitted ? "Recovered committed activation" : "Interrupted before activation");
                write(journal, op);
            }
        }
        Files.deleteIfExists(root.resolve("gate.json"));
    }

    private void pruneLeases() throws IOException {
        try (var files = Files.list(directory("leases"))) {
            for (Path file : files.toList()) {
                if (!isOwnerAlive(read(file))) {
                    // A dead JVM is not proof its tool descendants stopped writing the home.
                    // Preserve admission until an operator verifies and removes the orphan lease.
                    throw new IllegalStateException("dsh.orphaned_runtime_requires_recovery");
                }
            }
        }
    }

    private static boolean isOwnerAlive(Map<String, String> value) {
        try {
            return ProcessHandle.of(Long.parseLong(value.getOrDefault("pid", "0")))
                    .filter(ProcessHandle::isAlive)
                    .flatMap(handle -> handle.info().startInstant())
                    .map(start -> start.toString().equals(value.get("started"))).orElse(false);
        } catch (NumberFormatException error) { return false; }
    }

    private Map<String, String> pointer() throws IOException { return read(root.resolve("active.json")); }

    private Path directory(String name) throws IOException {
        Path path = name.isEmpty() ? root : root.resolve(name);
        if (Files.isSymbolicLink(path)) throw new IOException("DSH managed directory must not be a symlink");
        Files.createDirectories(path);
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { /* Non-POSIX hosts rely on the managed root ACL. */ }
        return path;
    }

    private Map<String, String> read(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new HashMap<>();
        if (Files.isSymbolicLink(path) || Files.size(path) > 1024 * 1024) throw new IOException("Invalid DSH metadata file");
        return new HashMap<>(mapper.readValue(Files.readAllBytes(path), VALUES));
    }

    private void write(Path path, Map<String, String> value) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), ".write-", ".json");
        try {
            try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { /* Inherit the managed root ACL. */ }
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(mapper.writeValueAsBytes(value));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            catch (UnsupportedOperationException ignored) { /* Directory fsync is unavailable on some hosts. */ }
        } finally { Files.deleteIfExists(temp); }
    }

    private <T> T locked(IoSupplier<T> action) {
        ReentrantLock local = LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
        local.lock();
        try {
            directory("");
            Path lockPath = root.resolve("control.lock");
            if (Files.isSymbolicLink(lockPath)) throw new IOException("Invalid DSH lock");
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) { return action.get(); }
        } catch (IOException error) { throw new IllegalStateException("dsh.storage_unavailable", error); }
        finally { local.unlock(); }
    }

    private static void safeId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9-]{0,99}")) throw new IllegalArgumentException("dsh.invalid_identifier");
    }

    @FunctionalInterface private interface IoSupplier<T> { T get() throws IOException; }

    public final class Lease implements AutoCloseable {
        private final Path path;
        private Lease(Path path) { this.path = path; }
        @Override public void close() { locked(() -> { Files.deleteIfExists(path); return null; }); }
    }
}
