package vip.mate.llm.chatmodel;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import vip.mate.llm.model.ModelProviderEntity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Per-request template switch for vLLM/Qwen, including arbitrary served model aliases. */
final class VllmThinkingDecorator implements ChatModel {
    private final ChatModel delegate;

    VllmThinkingDecorator(ChatModel delegate) {
        this.delegate = delegate;
    }

    static boolean supports(ModelProviderEntity provider, OpenAiChatOptions defaults) {
        String id = provider.getProviderId();
        if (id != null && id.toLowerCase(Locale.ROOT).contains("vllm")) return true;
        // Custom providers can opt in by explicitly configuring the template switch.
        return defaults.getExtraBody() != null
                && defaults.getExtraBody().get("chat_template_kwargs") instanceof Map<?, ?> template
                && template.containsKey("enable_thinking");
    }

    @Override public ChatResponse call(Prompt prompt) { return delegate.call(transform(prompt)); }
    @Override public Flux<ChatResponse> stream(Prompt prompt) { return delegate.stream(transform(prompt)); }
    @Override public ChatOptions getOptions() { return delegate.getOptions(); }

    private Prompt transform(Prompt prompt) {
        // Capture on the caller's thread, before Reactor subscription or worker handoff.
        String level = ThinkingLevelHolder.get();
        if (level == null || level.isBlank()) return prompt;
        ChatOptions runtime = prompt.getOptions();
        OpenAiChatOptions.Builder builder = OpenAiWirePolicyChatModel.mergeOptions(delegate.getOptions(), runtime).mutate();
        OpenAiChatOptions patched = builder.build();
        Map<String, Object> extra = new LinkedHashMap<>();
        Map<String, Object> template = new LinkedHashMap<>();
        if (delegate.getOptions() instanceof OpenAiChatOptions defaults) {
            mergeExtra(extra, template, defaults.getExtraBody());
        }
        mergeExtra(extra, template, patched.getExtraBody());
        template.put("enable_thinking", !"off".equalsIgnoreCase(level));
        extra.put("chat_template_kwargs", template);
        builder.extraBody(extra);
        // vLLM template switching does not use OpenAI's effort levels.
        builder.reasoningEffort(null);
        return new Prompt(prompt.getInstructions(), builder.build());
    }

    private static void mergeExtra(Map<String, Object> extra, Map<String, Object> template, Map<String, Object> source) {
        if (source == null) return;
        extra.putAll(source);
        if (source.get("chat_template_kwargs") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> { if (key instanceof String name) template.put(name, value); });
        }
    }
}
