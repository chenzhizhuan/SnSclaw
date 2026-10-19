package vip.mate.llm.dashscope;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable chat options with native provider extensions. */
public final class NativeDashScopeChatOptions extends DefaultToolCallingChatOptions {
    private final Map<String, Object> nativeParameters;

    private NativeDashScopeChatOptions(Builder builder, ToolCallingChatOptions base) {
        super(
                base.getToolCallbacks() == null ? null : List.copyOf(base.getToolCallbacks()),
                base.getToolContext() == null ? Map.of() : Map.copyOf(base.getToolContext()),
                base.getModel(),
                base.getFrequencyPenalty(),
                base.getMaxTokens(),
                base.getPresencePenalty(),
                base.getStopSequences() == null ? null : List.copyOf(base.getStopSequences()),
                base.getTemperature(),
                base.getTopK(),
                base.getTopP());
        nativeParameters = immutableMap(builder.nativeParameters);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, immutableValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) return value;
        throw new IllegalArgumentException(
                "Native option values must be JSON scalars, objects, or arrays");
    }

    public Map<String, Object> getNativeParameters() {
        return nativeParameters;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Builder mutate() {
        Builder builder = new Builder();
        builder.model(getModel())
                .frequencyPenalty(getFrequencyPenalty())
                .maxTokens(getMaxTokens())
                .presencePenalty(getPresencePenalty())
                .stopSequences(getStopSequences())
                .temperature(getTemperature())
                .topK(getTopK())
                .topP(getTopP())
                .toolCallbacks(getToolCallbacks())
                .toolContext(getToolContext());
        builder.nativeParameters.putAll(nativeParameters);
        return builder;
    }

    public static final class Builder extends DefaultToolCallingChatOptions.Builder<Builder> {
        private Map<String, Object> nativeParameters = new LinkedHashMap<>();

        public Builder nativeParameter(String name, Object value) {
            if (value == null) nativeParameters.remove(name);
            else nativeParameters.put(name, value);
            return this;
        }

        public Builder enableSearch(Boolean value) {
            return nativeParameter("enable_search", value);
        }

        public Builder searchOptions(Map<String, Object> value) {
            return nativeParameter("search_options", value);
        }

        public Builder enableThinking(Boolean value) {
            return nativeParameter("enable_thinking", value);
        }

        public Builder thinkingBudget(Integer value) {
            return nativeParameter("thinking_budget", value);
        }

        @Override
        public Builder clone() {
            Builder copy = (Builder) super.clone();
            copy.nativeParameters = new LinkedHashMap<>(nativeParameters);
            return copy;
        }

        @Override
        public Builder combineWith(ChatOptions.Builder<?> other) {
            super.combineWith(other);
            if (other.build() instanceof NativeDashScopeChatOptions nativeOptions) {
                nativeParameters.putAll(nativeOptions.nativeParameters);
            }
            return this;
        }

        @Override
        public NativeDashScopeChatOptions build() {
            return new NativeDashScopeChatOptions(this, super.build());
        }
    }
}
