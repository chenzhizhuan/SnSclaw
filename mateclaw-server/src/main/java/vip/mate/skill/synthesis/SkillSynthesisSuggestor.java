package vip.mate.skill.synthesis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import vip.mate.memory.event.ConversationCompletedEvent;

import java.util.Map;

/**
 * RFC-023: 后台 Skill 合成建议器
 * <p>
 * 监听 {@link ConversationCompletedEvent}，当对话消息数和工具调用数达到阈值时，
 * 通过 hook 事件通知前端"建议保存为 Skill"。<b>不自动创建 skill</b>——用户确认后
 * 通过 {@code POST /api/v1/skills/synthesize-from-conversation} 触发合成。
 *
 * @author SnSclaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillSynthesisSuggestor {

    private final SkillSynthesisProperties properties;
    private final SkillSynthesisService synthesisService;
    private final ApplicationEventPublisher eventPublisher;

    @Async
    @EventListener
    public void onConversationCompleted(ConversationCompletedEvent event) {
        if (!properties.isSuggestEnabled()) return;

        // 阈值检查 1: 消息数
        if (event.messageCount() < properties.getMinMessageCount()) return;

        // 阈值检查 2: 工具调用数（需查 DB）
        int toolCallCount;
        try {
            toolCallCount = synthesisService.countToolCalls(event.conversationId());
        } catch (Exception e) {
            log.debug("[SkillSuggest] Failed to count tool calls: {}", e.getMessage());
            return;
        }
        if (toolCallCount < properties.getMinToolCallCount()) return;

        log.info("[SkillSuggest] Conversation {} qualifies for skill synthesis suggestion " +
                        "(messages={}, toolCalls={})",
                event.conversationId(), event.messageCount(), toolCallCount);

        // 发布一个 SkillSynthesisSuggestionEvent，供前端 SSE 或 hook 消费
        try {
            eventPublisher.publishEvent(new SkillSynthesisSuggestionEvent(
                    event.agentId(),
                    event.conversationId(),
                    event.messageCount(),
                    toolCallCount
            ));
        } catch (Exception e) {
            log.warn("[SkillSuggest] Failed to publish suggestion event: {}", e.getMessage());
        }
    }

    /**
     * 建议事件——前端监听后弹出 toast 提示用户"是否保存为 Skill"
     */
    public record SkillSynthesisSuggestionEvent(
            Long agentId,
            String conversationId,
            int messageCount,
            int toolCallCount
    ) {}
}
