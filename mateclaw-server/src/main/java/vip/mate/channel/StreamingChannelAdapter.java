package vip.mate.channel;

import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService.StreamDelta;

/**
 * 支持流式处理的渠道适配器接口
 * <p>
 * 实现此接口的渠道能够以自身方式渲染流式事件（如钉钉 AI Card、飞书卡片更新等），
 * 而非等待完整回复后一次性发送。
 * <p>
 * 设计参考 SnSclaw 的事件流与渲染分离模式：
 * - ChannelMessageRouter 负责"事件产生"（调用 Agent 获取 StreamDelta 流）
 * - StreamingChannelAdapter 负责"UI 渲染"（决定如何呈现流式事件）
 *
 * @author SnSclaw Team
 */
public interface StreamingChannelAdapter extends ChannelAdapter {

    /**
     * 处理流式事件并渲染到渠道
     * <p>
     * Router 将 Agent 产生的 StreamDelta 流传入，由渠道实现决定渲染策略：
     * - 钉钉：创建 AI Card → 流式更新卡片 → 完成/失败
     * - 飞书：可更新消息卡片
     * - 其他：可累积后分段发送
     * <p>
     * 实现约定：
     * - 方法内部消费整个 Flux（阻塞当前线程直到流结束）
     * - 返回最终完整回复内容（用于保存到 DB）
     * - 异常应向上抛出，由 Router 统一处理
     *
     * @param stream         Agent 产生的结构化流式事件
     * @param message        原始入站消息（含 replyToken、rawPayload 等上下文）
     * @param conversationId 会话 ID
     * @return 最终完整回复内容
     */
    String processStream(Flux<StreamDelta> stream, ChannelMessage message, String conversationId);
}
