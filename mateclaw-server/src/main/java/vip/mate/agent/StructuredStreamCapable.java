package vip.mate.agent;

import reactor.core.publisher.Flux;

/**
 * 支持结构化流的 Agent 接口
 * <p>
 * 实现此接口的 Agent 可以在 SSE 流中同时发送事件（工具调用、阶段变更等）和内容。
 *
 * @author SnSclaw Team
 */
public interface StructuredStreamCapable {

    /**
     * 结构化流式对话
     * <p>
     * 返回的 Flux 中包含两类 StreamDelta：
     * - 事件类型（isEvent() == true）：工具调用开始/完成、阶段变更等
     * - 内容类型（hasPayload() == true）：LLM 生成的文本内容
     *
     * @param userMessage    用户消息
     * @param conversationId 会话ID
     * @return 结构化流
     */
    Flux<AgentService.StreamDelta> chatStructuredStream(String userMessage, String conversationId);

    /**
     * 结构化流式对话（带请求者身份）
     *
     * @param requesterId 请求发起者 ID（用于审批身份校验）
     */
    default Flux<AgentService.StreamDelta> chatStructuredStream(String userMessage, String conversationId,
                                                                  String requesterId) {
        return chatStructuredStream(userMessage, conversationId);
    }
}
