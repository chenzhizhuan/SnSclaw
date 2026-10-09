package vip.mate.agent.runtime.dsh.management;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import vip.mate.agent.runtime.dsh.DshRuntimeService;
import org.springframework.stereotype.Service;
import vip.mate.system.service.SystemSettingService;
import vip.mate.agent.runtime.dsh.DshLaunchSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DshManagementService {
    private static final String ENABLED_KEY = "dsh.enabled";

    private final DshRuntimeConfigService configService;
    private final DshArtifactInstaller installer;
    private final SystemSettingService settings;
    private volatile Map<String, Object> handshake = Map.of();
    private volatile Map<String, Object> taskCheck = Map.of();
    @Autowired
    private DshRuntimeService runtime;

    public DshManagementService(DshRuntimeConfigService configService,
                                DshArtifactInstaller installer,
                                SystemSettingService settings) {
        this.configService = configService;
        this.installer = installer;
        this.settings = settings;
    }

    public Map<String, Object> status() {
        DshRuntimeConfiguration configuration;
        try { configuration = configService.resolve(); }
        catch (IllegalArgumentException invalid) { return invalidConfigurationStatus(); }
        boolean executableAvailable = isExecutable(configuration.executablePath());
        // An empty managed key is valid: DshRuntimeService can reuse the
        // existing DeepSeek provider key. The page may still store a managed
        // key when the operator wants DSH to be independent from model rows.
        boolean sdkConfigAvailable;
        try { DshLaunchSpec.create(configuration, "health", "health", Path.of(configuration.workingDirectory())); sdkConfigAvailable = true; }
        catch (Exception error) { sdkConfigAvailable = false; }
        boolean enabled = settings.getBool(ENABLED_KEY, false);
        DshManagementState state;
        if (configuration.migrationRequired()) state = DshManagementState.MIGRATION_REQUIRED;
        else if (!executableAvailable) state = DshManagementState.NOT_INSTALLED;
        else if (!sdkConfigAvailable) state = DshManagementState.CONFIG_INVALID;
        else if (enabled) state = DshManagementState.ENABLED;
        else state = DshManagementState.READY;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state.name());
        result.put("profile", configuration.profile());
        String revision = Objects.toString(configService.revision(), "0");
        result.put("handshake", revision.equals(handshake.get("configRevision")) ? handshake : Map.of());
        result.put("taskCheck", revision.equals(taskCheck.get("configRevision")) ? taskCheck : Map.of());
        result.put("configRevision", configService.revision());
        result.put("versionStatus", "UNVERIFIED_VERSION");
        result.put("fileCheck", Map.of("success", sdkConfigAvailable));
        result.put("installed", executableAvailable);
        result.put("enabled", enabled);
        result.put("config", configuration.publicStatus());
        result.put("managed", configService.managedValues());
        result.put("artifactManifestConfigured", installer.manifestConfigured());
        result.put("privateArtifactManifestConfigured", installer.privateManifestConfigured());
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    private Map<String, Object> invalidConfigurationStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", DshManagementState.CONFIG_INVALID.name());
        result.put("installed", false);
        result.put("enabled", settings.getBool(ENABLED_KEY, false));
        result.put("config", Map.of());
        // Keep masked raw fields editable even when the resolved configuration cannot be parsed.
        result.put("managed", configService.managedValues());
        result.put("configRevision", configService.revision());
        result.put("fileCheck", Map.of("success", false));
        result.put("handshake", Map.of());
        result.put("taskCheck", Map.of());
        result.put("versionStatus", "UNVERIFIED_VERSION");
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    public Map<String, Object> saveConfig(Map<String, String> values) {
        configService.save(values);
        return status();
    }

    public Map<String, Object> verify() {
        Map<String, Object> result = status();
        boolean ok = "ENABLED".equals(result.get("state")) || "READY".equals(result.get("state"));
        result.put("verified", ok);
        result.put("verificationMessage", ok ? "DSH executable and configuration are available" : "DSH executable or configuration is unavailable");
        return result;
    }

    public Map<String, Object> testConnection() { return check(false); }
    public Map<String, Object> testTask() { return check(true); }

    private Map<String, Object> check(boolean task) {
        DshGenerationStore.Lease lease = null;
        String revision = "";
        if (task) taskCheck = Map.of(); else handshake = Map.of();
        try {
            if (configService.generations() != null) lease = configService.generations().acquireLease("health", "health");
            revision = Objects.toString(configService.revision(), "0");
            DshRuntimeConfiguration configuration = configService.resolve();
            if (runtime == null) throw new IllegalStateException("DSH health service unavailable");
            Map<String, Object> result = new LinkedHashMap<>(task ? runtime.testTask(configuration) : runtime.testConnection(configuration));
            result.put("configRevision", revision);
            if (task) taskCheck = Map.copyOf(result); else handshake = Map.copyOf(result);
            return result;
        } catch (Exception error) {
            Map<String, Object> failed = Map.of("success", false, "message", "DSH health check unavailable",
                    "configRevision", revision, "checkedAt", Instant.now().toString());
            if (task) taskCheck = failed; else handshake = failed;
            return failed;
        } finally { if (lease != null) lease.close(); }
    }

    public Map<String, Object> enable() {
        Map<String, Object> current = verify();
        if (!Boolean.TRUE.equals(current.get("verified"))) throw new IllegalStateException("DSH must pass verification before enabling");
        Map<String, Object> checked = testConnection();
        if (!Boolean.TRUE.equals(checked.get("success"))) throw new IllegalStateException("DSH must pass the SDK handshake before enabling");
        settings.saveBool(ENABLED_KEY, true, "Enable managed DeepSeek Harness runtime");
        return status();
    }

    public Map<String, Object> disable() {
        settings.saveBool(ENABLED_KEY, false, "Enable managed DeepSeek Harness runtime");
        return status();
    }

    private boolean isExecutable(String path) {
        return path != null && !path.isBlank() && Files.isExecutable(Path.of(path));
    }
}
