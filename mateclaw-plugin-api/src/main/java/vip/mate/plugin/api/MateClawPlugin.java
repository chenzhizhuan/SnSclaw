package vip.mate.plugin.api;

/**
 * Plugin SPI contract — all SnSclaw plugins implement this interface.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #onLoad(PluginContext)} — called when the plugin is loaded, platform context injected</li>
 *   <li>{@link #onEnable()} — called when the plugin is enabled, register features to the platform</li>
 *   <li>{@link #onDisable()} — called when the plugin is disabled, clean up resources</li>
 * </ol>
 *
 * @author SnSclaw
 */
public interface MateClawPlugin {

    /**
     * Called when the plugin is loaded. Use the provided context to register
     * tools, providers, channels, or memory providers.
     *
     * @param context platform context providing registration APIs
     */
    void onLoad(PluginContext context);

    /**
     * Called when the plugin is enabled. Perform any startup logic here.
     */
    void onEnable();

    /**
     * Called when the plugin is disabled. Clean up resources, close connections, etc.
     */
    void onDisable();
}
