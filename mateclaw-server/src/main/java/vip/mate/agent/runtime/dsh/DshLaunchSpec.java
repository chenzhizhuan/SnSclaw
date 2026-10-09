package vip.mate.agent.runtime.dsh;


import java.io.IOException;
import vip.mate.agent.runtime.dsh.management.DshRuntimeConfiguration;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable SDK argv. Paths are never interpreted as a shell command. */
public record DshLaunchSpec(List<String> command, Path home, Path cwd) {
    public DshLaunchSpec { command = List.copyOf(command); }

    public static DshLaunchSpec create(DshRuntimeConfiguration config, Object workspaceId, Object agentId, Path cwd) {
        if (config.migrationRequired()) throw new IllegalArgumentException("MIGRATION_REQUIRED: replace legacy DSH command/Cordis configuration with an SDK executable path");
        if (!"sdk".equals(config.profile())) throw new IllegalArgumentException("DSH_PROFILE_INVALID: profile must be sdk");
        Path executable = Path.of(config.executablePath());
        if (!executable.isAbsolute() || !Files.isRegularFile(executable) || !Files.isExecutable(executable))
            throw new IllegalArgumentException("DSH_EXECUTABLE_UNAVAILABLE: configure an absolute executable path");
        if (cwd == null || !Files.isDirectory(cwd)) throw new IllegalArgumentException("DSH_CWD_UNAVAILABLE");
        List<String> argv = new ArrayList<>(List.of(executable.normalize().toString(), "--profile", "sdk"));
        for (String patch : config.patchPaths()) {
            Path path = Path.of(patch);
            if (!path.isAbsolute() || !Files.isRegularFile(path)) throw new IllegalArgumentException("DSH_PATCH_UNAVAILABLE");
            argv.add("--patch"); argv.add(path.normalize().toString());
        }
        Path root = Path.of(config.homeRoot()).toAbsolutePath().normalize();
        Path home = root.resolve(opaque(workspaceId)).resolve(opaque(agentId));
        return new DshLaunchSpec(argv, home, cwd.toAbsolutePath().normalize());
    }

    private static String opaque(Object id) {
        return UUID.nameUUIDFromBytes(String.valueOf(id).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public ProcessBuilder processBuilder(Map<String, String> environment) throws IOException {
        Path root = home.getParent().getParent();
        // Preflight every managed component before any directory creation can follow a link.
        requireDirectoryOrMissing(root);
        requireDirectoryOrMissing(home.getParent());
        requireDirectoryOrMissing(home);
        Files.createDirectories(root);
        createHomeDirectory(home.getParent());
        createHomeDirectory(home);
        if (!home.toRealPath().startsWith(root.toRealPath())) throw new IOException("DSH_HOME_ESCAPE");
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.environment().remove("DSH_CORDIS_CONFIG");
        builder.environment().put("DSH_HOME", home.toString());
        builder.environment().put("DSH_CWD", cwd.toString());
        return builder;
    }
    private static void requireDirectoryOrMissing(Path path) throws IOException {
        if (Files.isSymbolicLink(path)
                || (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("DSH_HOME_ESCAPE: home components must be real directories");
    }

    private static void createHomeDirectory(Path path) throws IOException {
        requireDirectoryOrMissing(path);
        try { Files.createDirectory(path); }
        catch (FileAlreadyExistsException exists) { requireDirectoryOrMissing(path); }
    }

}
