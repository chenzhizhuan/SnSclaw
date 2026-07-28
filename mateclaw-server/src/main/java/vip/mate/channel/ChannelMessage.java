package vip.mate.channel;

import lombok.Builder;
import lombok.Data;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 渠道消息模型
 * <p>
 * 统一封装来自不同渠道的消息，采用渠道地址 + 原生 payload 设计。
 * 所有渠道的入站消息先转为此格式，再由 ChannelMessageRouter 路由到 Agent。
 * <p>
 * 实际消息内容以 contentParts 为准；content 字段保留纯文本摘要用于向后兼容。
 *
 * @author SnSclaw Team
 */
@Data
@Builder
public class ChannelMessage {

    /** 消息ID（渠道原始ID） */
    private String messageId;

    /** 渠道类型 */
    private String channelType;

    /** 发送者ID */
    private String senderId;

    /** 发送者名称 */
    private String senderName;

    /** 会话/群组ID（私聊时为 null） */
    private String chatId;

    /** 纯文本摘要（向后兼容） */
    private String content;

    /** 消息类型：text / image / file */
    private String contentType;

    /**
     * 用户输入方式：text / voice / image / mixed
     * <p>
     * 各渠道 Adapter 在解析入站消息时设置。
     * Router 据此决定是否触发 TTS 语音回复、是否注入语音场景提示词。
     */
    @Builder.Default
    private String inputMode = "text";

    /**
     * 结构化消息内容（多模态）。
     * 各渠道 Adapter 在解析原生消息时构建此列表，
     * Router 据此传给 AgentService，使 Agent 能看到完整的多模态输入。
     */
    @Builder.Default
    private List<MessageContentPart> contentParts = List.of();

    /** 消息时间 */
    private LocalDateTime timestamp;

    /**
     * 回复 Token
     * <p>
     * 不同渠道含义不同：
     * - 钉钉：sessionWebhook URL
     * - 飞书：chat_id
     * - Telegram：chat_id
     * - Discord：channel_id
     * <p>
     * 用于 sendMessage 回复时确定目标
     */
    private String replyToken;

    /** 原始 payload（用于调试） */
    private Object rawPayload;
}
