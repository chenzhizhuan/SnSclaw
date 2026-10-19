package vip.mate.llm.dashscope;

import java.util.Map;

/** Native counters expose stable accessors to cache and reasoning token accounting. */
public record NativeDashScopeUsage(
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        Long cacheReadInputTokens,
        Long cacheCreationInputTokens,
        CompletionTokenDetails completionTokenDetails,
        Map<String, Object> details) {
    public record CompletionTokenDetails(Integer reasoningTokens) {}
}
