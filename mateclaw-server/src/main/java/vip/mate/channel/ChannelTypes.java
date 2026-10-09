package vip.mate.channel;

import java.util.Set;
import vip.mate.exception.MateClawException;

/** Channel types with a runtime adapter. Validate before persisting configuration. */
public final class ChannelTypes {
    public static final Set<String> SUPPORTED = Set.of(
            "web", "dingtalk", "feishu", "telegram", "discord", "wecom", "qq", "weixin", "slack", "webchat");

    private ChannelTypes() {}

    public static void requireSupported(String type) {
        if (type == null || !SUPPORTED.contains(type)) {
            throw new MateClawException("err.channel.type_unsupported", 400,
                    "Unsupported channel type: " + type);
        }
    }
}
