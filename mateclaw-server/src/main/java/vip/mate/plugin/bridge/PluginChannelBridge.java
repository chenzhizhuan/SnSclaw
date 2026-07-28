package vip.mate.plugin.bridge;

import vip.mate.channel.ChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.plugin.api.channel.PluginChannelAdapter;

/**
 * Bridge that wraps a plugin's {@link PluginChannelAdapter} into the platform's
 * internal {@link ChannelAdapter} interface.
 *
 * @author SnSclaw Team
 */
public class PluginChannelBridge implements ChannelAdapter {

    private final PluginChannelAdapter delegate;

    public PluginChannelBridge(PluginChannelAdapter delegate) {
        this.delegate = delegate;
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public boolean isRunning() {
        return delegate.isRunning();
    }

    @Override
    public void onMessage(ChannelMessage message) {
        // Bridge: convert internal ChannelMessage to simplified plugin format
        String rawData = message.getRawPayload() != null ? message.getRawPayload().toString() : null;
        delegate.onMessage(
                message.getSenderId(),
                message.getContent(),
                rawData
        );
    }

    @Override
    public void sendMessage(String targetId, String content) {
        delegate.sendMessage(targetId, content);
    }

    @Override
    public boolean supportsProactiveSend() {
        return delegate.supportsProactiveSend();
    }

    @Override
    public void proactiveSend(String targetId, String content) {
        delegate.proactiveSend(targetId, content);
    }

    @Override
    public String getChannelType() {
        return delegate.getChannelType();
    }

    @Override
    public String getDisplayName() {
        return delegate.getDisplayName();
    }
}
