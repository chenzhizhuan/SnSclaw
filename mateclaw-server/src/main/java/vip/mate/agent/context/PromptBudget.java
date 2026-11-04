package vip.mate.agent.context;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/** Estimates the effective request without changing the provider's prompt or options. */
public final class PromptBudget {
    private PromptBudget() {}

    public static long estimateTokens(Prompt prompt, ChatOptions defaults, int fallbackOutputTokens) {
        ChatOptions runtime = prompt.getOptions();
        ChatOptions effective = effectiveOptions(runtime, defaults);
        // Native combineWith treats empty lists as absent. Provider adapters explicitly replace
        // callbacks when the runtime list is non-null, including a deliberately empty list.
        List<ToolCallback> callbacks = runtime instanceof ToolCallingChatOptions tools
                && tools.getToolCallbacks() != null ? tools.getToolCallbacks()
                : effective instanceof ToolCallingChatOptions tools ? tools.getToolCallbacks() : List.of();
        return (long) TokenEstimator.estimateTokens(prompt.getInstructions())
                + TokenEstimator.estimateToolsTokens(callbacks)
                + effectiveOutputReserve(effective, runtime, fallbackOutputTokens);
    }

    public static int outputReserveTokens(ChatOptions runtime, ChatOptions defaults, int fallback) {
        return effectiveOutputReserve(effectiveOptions(runtime, defaults), runtime, fallback);
    }

    private static int effectiveOutputReserve(ChatOptions effective, ChatOptions runtime, int fallback) {
        // A generic defaults builder cannot represent provider-specific runtime fields. Preserve
        // the runtime's declared maximum as a conservative floor, as the existing guard did.
        return Math.max(outputReserveTokens(effective, fallback), outputReserveTokens(runtime, 0));
    }

    private static ChatOptions effectiveOptions(ChatOptions runtime, ChatOptions defaults) {
        if (defaults == null) return runtime;
        if (runtime == null) return defaults;
        return defaults.mutate().combineWith(runtime.mutate()).build();
    }

    private static int outputReserveTokens(ChatOptions options, int fallback) {
        Integer maximum = options == null ? null : options.getMaxTokens();
        if (options instanceof OpenAiChatOptions openAi && openAi.getMaxCompletionTokens() != null) {
            maximum = maximum == null ? openAi.getMaxCompletionTokens()
                    : Math.max(maximum, openAi.getMaxCompletionTokens());
        }
        return maximum == null ? Math.max(0, fallback) : Math.max(0, maximum);
    }
}
