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

    /**
     * 判断一个 delta 的文本是否属于"最终回复内容"。
     * <p>
     * {@code segmentOnly} 的 delta 携带的是每轮 ReAct 的旁白（"我来查一下…"），
     * 共享累加器刻意不把它写进 {@code mate_message.content}。适配器如果直接
     * 累加 {@code delta.content()}，就会把每轮旁白拼进外发文本 —— 而旁白通常
     * 是对答案的复述，用户就会把同一段内容读到两三遍。被污染的文本还会回写
     * 持久化并在下一轮作为历史重放，重复量随轮次增长，而不是稳定在 2 倍。
     * <p>
     * 旁白要不要露出，由渠道的 {@code stream_progress} 开关决定：想露出就作为
     * 独立的进度消息下发，而不是混进最终答案。
     *
     * @param delta 流式片段
     * @return true 表示该片段的文本应计入最终回复
     */
    static boolean contributesToFinalContent(StreamDelta delta) {
        return delta != null
                && !delta.isEvent()
                && !delta.segmentOnly()
                && delta.content() != null;
    }
}
