package vip.mate.agent.runtime.dsh.management;


import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vip.mate.system.service.SystemSettingService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the current DSH configuration without requiring a backend restart. */
@Service
public class DshRuntimeConfigService {
    private static final String[] MANAGED_KEYS = {
            "dsh.executable_path", "dsh.cordis_config_path", "dsh.working_directory",
            "dsh.base_url", "dsh.model_name", SystemSettingService.DSH_API_KEY_KEY,
            "dsh.profile", "dsh.patch_paths", "dsh.home_root"
    };

    private final SystemSettingService settings;
    private final Map<String, String> properties;
    @Autowired(required = false)
    private DshGenerationStore generations;
    @Value("${mateclaw.agent.runtime.dsh.profile:sdk}")
    private String profile = "sdk";
    @Value("${mateclaw.agent.runtime.dsh.patch-paths:[]}")
    private String patchPaths = "[]";
    @Value("${mateclaw.agent.runtime.dsh.home-root:}")
    private String homeRoot = "";

    public DshGenerationStore generations() { return generations; }
    public String revision() { return generations == null ? "0" : generations.revision(); }


    public DshRuntimeConfigService(
            SystemSettingService settings,
            @Value("${mateclaw.agent.runtime.dsh.command:}") String command,
            @Value("${mateclaw.agent.runtime.dsh.cordis-config:}") String cordisConfig,
            @Value("${mateclaw.agent.runtime.dsh.working-directory:}") String workingDirectory,
            @Value("${mateclaw.agent.runtime.dsh.base-url:}") String baseUrl,
            @Value("${mateclaw.agent.runtime.dsh.model-name:}") String modelName,
            @Value("${mateclaw.agent.runtime.dsh.api-key:}") String apiKey) {
        this.settings = settings;
        this.properties = Map.of(
                "mateclaw.agent.runtime.dsh.command", command,
                "mateclaw.agent.runtime.dsh.cordis-config", cordisConfig,
                "mateclaw.agent.runtime.dsh.working-directory", workingDirectory,
                "mateclaw.agent.runtime.dsh.base-url", baseUrl,
                "mateclaw.agent.runtime.dsh.model-name", modelName,
                "mateclaw.agent.runtime.dsh.api-key", apiKey);
    }

    public DshRuntimeConfiguration resolve() {
        return resolve(Map.of());
    }

    private DshRuntimeConfiguration resolve(Map<String, String> submitted) {
        Map<String, String> managed = new LinkedHashMap<>();
        for (String key : MANAGED_KEYS) {
            String defaultValue = key.equals("dsh.working_directory") ? "" : null;
            managed.put(key, settings.getString(key, defaultValue));
        }
        Map<String, String> active = generations == null ? Map.of() : generations.activeValues();
        managed.putAll(active);
        managed.putAll(submitted);
        if (managed.get("dsh.home_root") == null || managed.get("dsh.home_root").isBlank()) {
            if (!homeRoot.isBlank()) managed.put("dsh.home_root", homeRoot);
            else if (generations != null) managed.put("dsh.home_root", generations.root().resolve("homes").toString());
        }
        Map<String, String> runtimeProperties = new LinkedHashMap<>(properties);
        runtimeProperties.put("mateclaw.agent.runtime.dsh.profile", profile);
        runtimeProperties.put("mateclaw.agent.runtime.dsh.patch-paths", patchPaths);
        DshRuntimeConfiguration resolved = DshRuntimeConfigResolver.resolve(managed,
                active.isEmpty() ? runtimeProperties : Map.of(), active.isEmpty() ? System.getenv() : Map.of());
        String workingDirectory = resolved.workingDirectory();
        if (workingDirectory == null || workingDirectory.isBlank()) workingDirectory = System.getProperty("user.dir");
        return new DshRuntimeConfiguration(resolved.executablePath(), resolved.cordisConfigPath(), workingDirectory,
                resolved.baseUrl(), resolved.modelName(), resolved.apiKey(), resolved.profile(), resolved.patchPaths(), resolved.homeRoot());
    }

    public Map<String, String> managedValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : MANAGED_KEYS) {
            String value = settings.getString(key, "");
            if (SystemSettingService.DSH_API_KEY_KEY.equals(key)) {
                values.put(key, settings.maskSecret(value));
            } else {
                values.put(key, value == null ? "" : value);
            }
        }
        if (generations != null) {
            generations.activeValues().forEach((key, value) -> {
                if (Arrays.asList(MANAGED_KEYS).contains(key)) values.put(key,
                        SystemSettingService.DSH_API_KEY_KEY.equals(key) ? settings.maskSecret(value) : value);
            });
        }
        return values;
    }

    public void save(Map<String, String> values) {
        if (values == null) return;
        Map<String, String> submitted = new LinkedHashMap<>();
        for (String key : MANAGED_KEYS) {
            String value = values.get(key);
            if (value == null) continue;
            if (SystemSettingService.DSH_API_KEY_KEY.equals(key) && (value.isBlank() || value.startsWith("****"))) continue;
            submitted.put(key, value);
        }
        String expectedRevision = generations == null ? null : generations.revision();
        // Apply repairs before parsing; malformed stored fields must remain editable.
        DshRuntimeConfiguration current = resolve(submitted);
        if (!"sdk".equals(current.profile())) throw new IllegalArgumentException("DSH_PROFILE_INVALID: profile must be sdk");
        if (generations != null) {
            Map<String, String> snapshot = new LinkedHashMap<>();
            snapshot.put("dsh.executable_path", current.executablePath());
            snapshot.put("dsh.cordis_config_path", current.cordisConfigPath());
            snapshot.put("dsh.working_directory", current.workingDirectory());
            snapshot.put("dsh.base_url", current.baseUrl() == null ? "" : current.baseUrl());
            snapshot.put("dsh.model_name", current.modelName() == null ? "" : current.modelName());
            snapshot.put("dsh.api_key", current.apiKey() == null ? "" : current.apiKey());
            snapshot.put("dsh.profile", current.profile());
            snapshot.put("dsh.home_root", current.homeRoot());
            try { snapshot.put("dsh.patch_paths", new ObjectMapper().writeValueAsString(current.patchPaths())); }
            catch (Exception error) { throw new IllegalArgumentException("Invalid DSH patches", error); }
            generations.updateActiveValues(snapshot, expectedRevision);
            return;
        }
        save(values, "dsh.executable_path");
        save(values, "dsh.cordis_config_path");
        save(values, "dsh.working_directory");
        save(values, "dsh.base_url");
        save(values, "dsh.model_name");
        save(values, "dsh.profile");
        save(values, "dsh.patch_paths");
        save(values, "dsh.home_root");
        String apiKey = values.get(SystemSettingService.DSH_API_KEY_KEY);
        if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("****")) {
            settings.saveString(SystemSettingService.DSH_API_KEY_KEY, apiKey.trim(), "DeepSeek API key for DSH");
        }
    }

    private void save(Map<String, String> values, String key) {
        if (values.containsKey(key)) {
            settings.saveString(key, values.get(key), "Managed DeepSeek Harness runtime setting");
        }
    }
}
