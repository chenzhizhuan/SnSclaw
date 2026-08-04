package vip.mate.plugin.api.channel;

/**
 * Simplified channel adapter interface for plugins.
 * <p>
 * Plugins implement this interface to register new messaging channels.
 * The platform wraps it in an internal ChannelAdapter via a bridge.
 *
 * @author SnSclaw
 */
public interface PluginChannelAdapter {

    /**
     * Start the channel (establish connections, register webhooks, etc.).
     */
    void start();

    /**
     * Stop the channel (disconnect, clean up resources).
     */
    void stop();

    /**
     * Whether the channel is currently running.
     */
    boolean isRunning();

    /**
     * The channel type identifier, e.g. "line", "whatsapp".
     */
    String getChannelType();

    /**
     * Human-readable display name.
     */
    default String getDisplayName() {
        return getChannelType();
    }

    /**
     * Send a text message to a target.
     *
     * @param targetId target identifier (user/group/channel ID)
     * @param content  message content (Markdown format)
     */
    void sendMessage(String targetId, String content);

    /**
     * Handle an incoming message from the channel.
     * <p>
     * The platform will call this when a webhook or push message arrives.
     * Plugins should process the message and use their internal routing logic.
     *
     * @param senderId  sender identifier
     * @param content   message text
     * @param rawData   raw message data (JSON string) for advanced processing
     */
    default void onMessage(String senderId, String content, String rawData) {
        // Default no-op; plugins override if they need to handle incoming messages
    }

    /**
     * Whether this channel supports proactive sending (without webhook context).
     */
    default boolean supportsProactiveSend() {
        return false;
    }

    /**
     * Proactively send a message (without webhook callback context).
     *
     * @param targetId target identifier
     * @param content  message content
     */
    default void proactiveSend(String targetId, String content) {
        throw new UnsupportedOperationException(getChannelType() + " does not support proactive send");
    }
}
