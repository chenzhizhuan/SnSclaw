package vip.mate.agent.runtime.dsh.management;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolved DSH settings. The API key is deliberately omitted from public projections. */
public record DshRuntimeConfiguration(
        String executablePath,
        String cordisConfigPath,
        String workingDirectory,
        String baseUrl,
        String modelName,
        String apiKey,
        String profile,
        List<String> patchPaths,
        String homeRoot) {

    public DshRuntimeConfiguration(String executablePath, String cordisConfigPath, String workingDirectory,
                                   String baseUrl, String modelName, String apiKey) {
        this(executablePath, cordisConfigPath, workingDirectory, baseUrl, modelName, apiKey,
                "sdk", List.of(), Path.of(System.getProperty("user.home"), ".mateclaw", "runtimes", "deepseek-harness", "homes").toString());
    }

    public DshRuntimeConfiguration {
        executablePath = executablePath == null ? "" : executablePath;
        cordisConfigPath = cordisConfigPath == null ? "" : cordisConfigPath;
        profile = profile == null || profile.isBlank() ? "sdk" : profile;
        patchPaths = patchPaths == null ? List.of() : List.copyOf(patchPaths);
        homeRoot = homeRoot == null || homeRoot.isBlank()
                ? Path.of(System.getProperty("user.home"), ".mateclaw", "runtimes", "deepseek-harness", "homes").toString() : homeRoot;
    }

    public boolean migrationRequired() {
        return !cordisConfigPath.isBlank() || executablePath.contains("dsh-jsonrpc-agent")
                || executablePath.contains(" --")
                || (executablePath.chars().anyMatch(Character::isWhitespace) && !Files.isRegularFile(Path.of(executablePath))) || executablePath.startsWith("\"") || executablePath.startsWith("'");
    }

    @Override public String toString() { return "DshRuntimeConfiguration[profile=" + profile + ", credentials=redacted]"; }


    public Map<String, Object> publicStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("profile", profile);
        status.put("patchPaths", patchPaths);
        status.put("homeRoot", homeRoot);
        status.put("versionStatus", "UNVERIFIED_VERSION");
        status.put("executablePath", executablePath);
        status.put("cordisConfigPath", cordisConfigPath);
        status.put("workingDirectory", workingDirectory);
        status.put("baseUrl", baseUrl);
        status.put("modelName", modelName);
        status.put("apiKeyConfigured", apiKey != null && !apiKey.isBlank());
        return status;
    }
}
