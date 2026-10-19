package vip.mate.agent.graph;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import vip.mate.llm.chatmodel.ChatModelDefaults;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;

import java.util.List;
import java.util.Locale;

/** Builds immutable tool-request options from the bound model's configured defaults. */
public final class ModelToolOptions {
    private ModelToolOptions() {}

    public static ChatOptions forModel(
            ChatModel model, List<ToolCallback> callbacks, String reasoningEffort) {
        ChatOptions defaults = ChatModelDefaults.optionsFor(model);
        if (defaults instanceof OpenAiChatOptions openAi) {
            OpenAiChatOptions.Builder builder = openAi.mutate().toolCallbacks(callbacks);
            String requestLevel = ThinkingLevelHolder.get();
            String effective = requestLevel != null ? requestLevel : reasoningEffort;
            if ("off".equalsIgnoreCase(effective)) {
                builder.reasoningEffort(null);
            } else if (StringUtils.hasText(effective)) {
                builder.reasoningEffort(
                        switch (effective.toLowerCase(Locale.ROOT)) {
                            case "minimal", "low" -> "low";
                            case "max" -> "high";
                            default -> effective;
                        });
            }
            return builder.build();
        }
        if (defaults != null
                && defaults.mutate() instanceof ToolCallingChatOptions.Builder<?> builder) {
            return builder.toolCallbacks(callbacks).build();
        }
        ToolCallingChatOptions.Builder<?> builder = ToolCallingChatOptions.builder();
        if (defaults != null) builder.combineWith(defaults.mutate());
        return builder.toolCallbacks(callbacks).build();
    }
}
