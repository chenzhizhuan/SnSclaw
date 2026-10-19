package vip.mate.agent.graph;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import vip.mate.llm.dashscope.NativeDashScopeChatOptions;

import java.util.List;
import java.util.Map;

class ModelToolOptionsTest {
    private static final ToolCallback OLD = callback("old");
    private static final ToolCallback ACTIVE = callback("active");

    private static ToolCallback callback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("Description")
                        .inputSchema("{\"type\":\"object\",\"required\":[\"value\"]}")
                        .build();
            }

            @Override
            public String call(String input) {
                throw new AssertionError("Options must never execute a tool");
            }
        };
    }

    private static ChatModel model(ChatOptions defaults) {
        return new ChatModel() {
            @Override
            public ChatOptions getOptions() {
                return defaults;
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                throw new AssertionError("Options must never call a model");
            }
        };
    }

    @AfterEach
    void cleanup() {
        ThinkingLevelHolder.clear();
    }

    @Test
    void openAiUsesConfiguredDefaultsAndReplacesCallbacks() {
        OpenAiChatOptions defaults =
                OpenAiChatOptions.builder()
                        .model("configured-alias")
                        .temperature(.4)
                        .maxTokens(123)
                        .reasoningEffort("high")
                        .toolCallbacks(OLD)
                        .extraBody(Map.of("vendor", true))
                        .customHeaders(Map.of("X-Test", "value"))
                        .build();
        OpenAiChatOptions actual =
                assertInstanceOf(
                        OpenAiChatOptions.class,
                        ModelToolOptions.forModel(model(defaults), List.of(ACTIVE), "medium"));
        assertEquals("configured-alias", actual.getModel());
        assertEquals(.4, actual.getTemperature());
        assertEquals(123, actual.getMaxTokens());
        assertEquals("medium", actual.getReasoningEffort());
        assertEquals(defaults.getExtraBody(), actual.getExtraBody());
        assertEquals(defaults.getCustomHeaders(), actual.getCustomHeaders());
        assertEquals(List.of(ACTIVE), actual.getToolCallbacks());
        assertSame(ACTIVE, actual.getToolCallbacks().getFirst());
        assertEquals(
                "{\"type\":\"object\",\"required\":[\"value\"]}",
                actual.getToolCallbacks().getFirst().getToolDefinition().inputSchema());
        assertEquals("high", defaults.getReasoningEffort());
        assertEquals(List.of(OLD), defaults.getToolCallbacks());
    }

    @Test
    void offClearsOpenAiEffortAndExplicitEmptyCallbacksClearDefaults() {
        OpenAiChatOptions defaults =
                OpenAiChatOptions.builder()
                        .model("alias")
                        .reasoningEffort("high")
                        .toolCallbacks(OLD)
                        .build();
        ThinkingLevelHolder.set("off");
        OpenAiChatOptions actual =
                assertInstanceOf(
                        OpenAiChatOptions.class,
                        ModelToolOptions.forModel(model(defaults), List.of(), "medium"));
        assertNull(actual.getReasoningEffort());
        assertEquals(List.of(), actual.getToolCallbacks());
        ThinkingLevelHolder.clear();
        assertNull(
                ((OpenAiChatOptions) ModelToolOptions.forModel(model(defaults), List.of(), "off"))
                        .getReasoningEffort());
        assertEquals(
                "high",
                ((OpenAiChatOptions) ModelToolOptions.forModel(model(defaults), List.of(), null))
                        .getReasoningEffort());
    }

    @Test
    void requestThinkingLevelOverridesConstructorOff() {
        OpenAiChatOptions defaults =
                OpenAiChatOptions.builder().model("alias").reasoningEffort("medium").build();
        ThinkingLevelHolder.set("high");
        OpenAiChatOptions actual =
                assertInstanceOf(
                        OpenAiChatOptions.class,
                        ModelToolOptions.forModel(model(defaults), List.of(), "off"));
        assertEquals("high", actual.getReasoningEffort());
        assertEquals("medium", defaults.getReasoningEffort());
    }

    @Test
    void requestThinkingLevelsMapMinimalToLowAndMaxToHigh() {
        OpenAiChatOptions defaults =
                OpenAiChatOptions.builder().model("alias").reasoningEffort("medium").build();
        ThinkingLevelHolder.set("minimal");
        assertEquals(
                "low",
                ((OpenAiChatOptions)
                                ModelToolOptions.forModel(model(defaults), List.of(), "medium"))
                        .getReasoningEffort());
        ThinkingLevelHolder.set("max");
        assertEquals(
                "high",
                ((OpenAiChatOptions)
                                ModelToolOptions.forModel(model(defaults), List.of(), "medium"))
                        .getReasoningEffort());
    }

    @Test
    void anthropicKeepsNativeThinkingAndModelDefaults() {
        AnthropicChatOptions defaults =
                AnthropicChatOptions.builder()
                        .model("claude-configured")
                        .maxTokens(10000)
                        .thinkingEnabled(4096)
                        .toolCallbacks(OLD)
                        .httpHeaders(Map.of("X-Test", "value"))
                        .build();
        AnthropicChatOptions actual =
                assertInstanceOf(
                        AnthropicChatOptions.class,
                        ModelToolOptions.forModel(model(defaults), List.of(ACTIVE), "high"));
        assertEquals(defaults.getModel(), actual.getModel());
        assertEquals(defaults.getThinking(), actual.getThinking());
        assertEquals(defaults.getMaxTokens(), actual.getMaxTokens());
        assertEquals(defaults.getHttpHeaders(), actual.getHttpHeaders());
        assertEquals(List.of(ACTIVE), actual.getToolCallbacks());
        assertEquals(List.of(OLD), defaults.getToolCallbacks());
    }

    @Test
    void nativeDashScopeKeepsProviderExtensionsAndToolContext() {
        NativeDashScopeChatOptions defaults =
                NativeDashScopeChatOptions.builder()
                        .model("qwen-native")
                        .maxTokens(456)
                        .temperature(.3)
                        .enableThinking(true)
                        .thinkingBudget(2048)
                        .nativeParameter("vendor", Map.of("option", true))
                        .toolCallbacks(OLD)
                        .toolContext(Map.of("context", "value"))
                        .build();
        NativeDashScopeChatOptions actual =
                assertInstanceOf(
                        NativeDashScopeChatOptions.class,
                        ModelToolOptions.forModel(model(defaults), List.of(ACTIVE), "high"));
        assertEquals(defaults.getNativeParameters(), actual.getNativeParameters());
        assertEquals(defaults.getModel(), actual.getModel());
        assertEquals(defaults.getToolContext(), actual.getToolContext());
        assertEquals(456, actual.getMaxTokens());
        assertEquals(List.of(ACTIVE), actual.getToolCallbacks());
        assertEquals(List.of(OLD), defaults.getToolCallbacks());
    }

    @Test
    void customToolOptionsKeepTheirSubtypeAndExtension() {
        CustomOptions defaults =
                new CustomBuilder()
                        .model("custom-configured")
                        .temperature(.6)
                        .toolCallbacks(OLD)
                        .extension("native")
                        .build();
        CustomOptions actual =
                assertInstanceOf(
                        CustomOptions.class,
                        ModelToolOptions.forModel(model(defaults), List.of(ACTIVE), "high"));
        assertEquals("native", actual.extension);
        assertEquals("custom-configured", actual.getModel());
        assertEquals(.6, actual.getTemperature());
        assertEquals(List.of(ACTIVE), actual.getToolCallbacks());
    }

    @Test
    void genericDefaultsStayGenericAndStillAdvertiseCallbacks() {
        ChatOptions defaults =
                ChatOptions.builder().model("generic-configured").maxTokens(555).topK(12).build();
        ToolCallingChatOptions actual =
                assertInstanceOf(
                        ToolCallingChatOptions.class,
                        ModelToolOptions.forModel(model(defaults), List.of(ACTIVE), "high"));
        assertFalse(actual instanceof OpenAiChatOptions);
        assertEquals("generic-configured", actual.getModel());
        assertEquals(555, actual.getMaxTokens());
        assertEquals(12, actual.getTopK());
        assertEquals(List.of(ACTIVE), actual.getToolCallbacks());
    }

    private static final class CustomOptions extends DefaultToolCallingChatOptions {
        private final String extension;

        CustomOptions(ToolCallingChatOptions base, String extension) {
            super(
                    base.getToolCallbacks(),
                    base.getToolContext(),
                    base.getModel(),
                    base.getFrequencyPenalty(),
                    base.getMaxTokens(),
                    base.getPresencePenalty(),
                    base.getStopSequences(),
                    base.getTemperature(),
                    base.getTopK(),
                    base.getTopP());
            this.extension = extension;
        }

        @Override
        public CustomBuilder mutate() {
            CustomBuilder builder = new CustomBuilder();
            builder.combineWith(super.mutate());
            return builder.extension(extension);
        }
    }

    private static final class CustomBuilder
            extends DefaultToolCallingChatOptions.Builder<CustomBuilder> {
        private String extension;

        CustomBuilder extension(String value) {
            extension = value;
            return this;
        }

        @Override
        public CustomOptions build() {
            return new CustomOptions(super.build(), extension);
        }
    }
}
