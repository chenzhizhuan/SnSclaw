package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;

/**
 * Web 渠道适配器
 * <p>
 * Web 渠道是 SnSclaw 的默认渠道，通过 HTTP API 和 SSE 与前端交互。
 * 不同于 IM 渠道，Web 渠道不需要长连接，消息通过 ChatController 直接处理。
 * 此适配器主要提供统一的生命周期管理和消息格式兼容。
 *
 * @author SnSclaw Team
 */
@Slf4j
public class WebChannelAdapter extends AbstractChannelAdapter {

    public static final String CHANNEL_TYPE = "web";

    public WebChannelAdapter(ChannelEntity channelEntity,
                             ChannelMessageRouter messageRouter,
                             ObjectMapper objectMapper) {
        super(channelEntity, messageRouter, objectMapper);
    }

    @Override
    protected void doStart() {
        // Web 渠道无需额外启动，HTTP 端点由 Spring MVC 管理
        log.info("[web] Web channel ready (HTTP/SSE endpoints managed by Spring MVC)");
    }

    @Override
    protected void doStop() {
        // Web 渠道无需显式停止
        log.info("[web] Web channel stopped");
    }

    @Override
    public void sendMessage(String targetId, String content) {
        // Web 渠道的消息发送通过 SSE 或 HTTP 响应完成，
        // 此方法仅用于主动推送场景（如定时任务），可通过 WebSocket 实现
        log.debug("[web] sendMessage to {}: {}chars (push not implemented, use SSE)",
                targetId, content != null ? content.length() : 0);
    }

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }
}
