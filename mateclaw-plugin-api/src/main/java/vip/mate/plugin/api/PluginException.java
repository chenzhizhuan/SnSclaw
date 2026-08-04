package vip.mate.plugin.api;

/**
 * Runtime exception for plugin-related errors.
 *
 * @author SnSclaw
 */
public class PluginException extends RuntimeException {

    public PluginException(String message) {
        super(message);
    }

    public PluginException(String message, Throwable cause) {
        super(message, cause);
    }
}
