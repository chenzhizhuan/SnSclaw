package vip.mate.plugin.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Plugin manifest model — deserialized from {@code mateclaw-plugin.json} in the plugin JAR root.
 *
 * @author SnSclaw
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginManifest {

    private String name;
    private String version;
    private String type;
    private String displayName;
    private String description;
    private String entrypoint;

    @JsonProperty("minPlatformVersion")
    private String minPlatformVersion;

    private String author;

    private Map<String, ConfigField> config;

    // ==================== Getters & Setters ====================

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getEntrypoint() { return entrypoint; }
    public void setEntrypoint(String entrypoint) { this.entrypoint = entrypoint; }

    public String getMinPlatformVersion() { return minPlatformVersion; }
    public void setMinPlatformVersion(String minPlatformVersion) { this.minPlatformVersion = minPlatformVersion; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public Map<String, ConfigField> getConfig() { return config; }
    public void setConfig(Map<String, ConfigField> config) { this.config = config; }

    /**
     * Validate that required fields are present.
     *
     * @throws PluginException if validation fails
     */
    public void validate() {
        if (name == null || name.isBlank()) {
            throw new PluginException("Plugin manifest missing required field: name");
        }
        if (version == null || version.isBlank()) {
            throw new PluginException("Plugin manifest missing required field: version");
        }
        if (entrypoint == null || entrypoint.isBlank()) {
            throw new PluginException("Plugin manifest missing required field: entrypoint");
        }
        if (type == null || type.isBlank()) {
            throw new PluginException("Plugin manifest missing required field: type");
        }
        // Validate type is a known enum value
        try {
            PluginType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new PluginException("Unknown plugin type '" + type +
                    "'. Allowed: " + java.util.Arrays.toString(PluginType.values()));
        }
    }

    /**
     * Check if this plugin is compatible with the given platform version.
     *
     * @param platformVersion current platform version (e.g. "1.1.0")
     * @return true if compatible (minPlatformVersion &lt;= platformVersion)
     */
    public boolean isCompatibleWith(String platformVersion) {
        if (minPlatformVersion == null || minPlatformVersion.isBlank()) {
            return true; // No constraint
        }
        return compareVersions(platformVersion, minPlatformVersion) >= 0;
    }

    /**
     * Simple semver comparison: returns negative if v1 &lt; v2, 0 if equal, positive if v1 &gt; v2.
     */
    private static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int p1 = i < parts1.length ? parseIntSafe(parts1[i]) : 0;
            int p2 = i < parts2.length ? parseIntSafe(parts2[i]) : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            // Strip non-numeric suffixes like "-SNAPSHOT"
            return Integer.parseInt(s.replaceAll("[^0-9].*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Get the plugin type as enum.
     */
    public PluginType getPluginType() {
        if (type == null) return null;
        try {
            return PluginType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new PluginException("Unknown plugin type: " + type);
        }
    }

    // ==================== Config Field ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfigField {
        private String type;
        private boolean required;
        private boolean secret;
        private String description;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }

        public boolean isSecret() { return secret; }
        public void setSecret(boolean secret) { this.secret = secret; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
