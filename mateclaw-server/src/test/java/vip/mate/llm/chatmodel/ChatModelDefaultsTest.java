package vip.mate.llm.chatmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;

import vip.mate.agent.graph.ModelToolOptions;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.node.ReasoningNode;
import vip.mate.agent.graph.node.SummarizingNode;

import java.util.List;

class ChatModelDefaultsTest {
    @Test
    void legacyOverridePreservesModelAndRequestDefaults() {
        ChatModel model = legacy();
        ChatOptions options = ModelToolOptions.forModel(model, List.of(), null);
        assertEquals("plugin-legacy", options.getModel());
        assertEquals(0.4, options.getTemperature());
        assertEquals(321, options.getMaxTokens());
    }

    @Test
    void currentGetOptionsOverridePreservesTypedDefaults() {
        OpenAiChatOptions defaults =
                OpenAiChatOptions.builder()
                        .model("plugin-current")
                        .temperature(0.6)
                        .reasoningEffort("low")
                        .build();
        ChatModel model =
                new ChatModel() {
                    @Override
                    public ChatOptions getOptions() {
                        return defaults;
                    }

                    @Override
                    public ChatResponse call(Prompt prompt) {
                        return null;
                    }
                };
        assertSame(defaults, ChatModelDefaults.optionsFor(model));
        assertSame(defaults, ChatModelDefaults.optionsFor(new VllmThinkingDecorator(model)));
        var options = (OpenAiChatOptions) ModelToolOptions.forModel(model, List.of(), null);
        assertEquals("plugin-current", options.getModel());
        assertEquals(0.6, options.getTemperature());
        assertEquals("low", options.getReasoningEffort());
    }

    @Test
    void nullLegacyDefaultsFallBackToCurrentOptions() {
        ChatModel model = mock(ChatModel.class);
        OpenAiChatOptions defaults =
                OpenAiChatOptions.builder().model("mock-current").temperature(0.5).build();
        when(model.getOptions()).thenReturn(defaults);
        assertSame(defaults, ChatModelDefaults.optionsFor(model));
        assertEquals("mock-current", ModelToolOptions.forModel(model, List.of(), null).getModel());
        String telemetry =
                ReflectionTestUtils.invokeMethod(
                        NodeStreamingChatHelper.class, "identifyModel", model);
        assertEquals("mock-current", telemetry);
    }

    @Test
    void reasoningSeedsLegacyDefaultsBeforeApplyingOutputBudget() {
        var node = mock(ReasoningNode.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(node, "chatModel", legacy());
        ReflectionTestUtils.setField(node, "maxOutputTokens", 600);
        ChatOptions options =
                ReflectionTestUtils.invokeMethod(node, "buildChatOptions", "off", List.of());
        assertEquals("plugin-legacy", options.getModel());
        assertEquals(0.4, options.getTemperature());
        assertEquals(600, options.getMaxTokens());
    }

    @Test
    void summarySeedsLegacyDefaults() {
        var node = mock(SummarizingNode.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(node, "chatModel", legacy());
        Prompt prompt = ReflectionTestUtils.invokeMethod(node, "buildNoThinkingPrompt", List.of());
        assertEquals("plugin-legacy", prompt.getOptions().getModel());
        assertEquals(0.4, prompt.getOptions().getTemperature());
    }

    @Test
    void telemetryReportsConfiguredLegacyModel() {
        String model =
                ReflectionTestUtils.invokeMethod(
                        NodeStreamingChatHelper.class, "identifyModel", legacy());
        assertEquals("plugin-legacy", model);
    }

    @SuppressWarnings("removal")
    private ChatModel legacy() {
        return new ChatModel() {
            @Override
            public ChatOptions getDefaultOptions() {
                return ChatOptions.builder()
                        .model("plugin-legacy")
                        .temperature(0.4)
                        .maxTokens(321)
                        .build();
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                return null;
            }
        };
    }
}
