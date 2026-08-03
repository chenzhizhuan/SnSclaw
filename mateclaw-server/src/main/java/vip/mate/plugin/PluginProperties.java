package vip.mate.plugin;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Plugin SDK configuration properties.
 *
 * @author SnSclaw Team
 */
@Data
@Component
@ConfigurationProperties(prefix = "mateclaw.plugin")
public class PluginProperties {

    /** Whether the plugin system is enabled */
    private boolean enabled = true;

    /** User-global plugin directory */
    private String userDir = System.getProperty("user.home") + "/.snsclaw/plugins";
}
