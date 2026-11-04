package vip.mate.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromptBudgetTest {
    @Test void explicitEmptyCallbacksAndLowerOutputOverrideDefaultsWithoutMutatingOptions() {
        var tool=mock(ToolCallback.class);
        var defaults=OpenAiChatOptions.builder().maxTokens(4096).toolCallbacks(List.of(tool)).build();
        var runtime=OpenAiChatOptions.builder().maxTokens(64).toolCallbacks(List.of()).build();
        var prompt=new Prompt("summary",runtime);

        assertEquals(TokenEstimator.estimateTokens(prompt.getInstructions())+64,
                PromptBudget.estimateTokens(prompt,defaults,1024));
        assertEquals(4096,defaults.getMaxTokens());
        assertEquals(List.of(tool),defaults.getToolCallbacks());
        assertEquals(64,runtime.getMaxTokens());
        assertTrue(runtime.getToolCallbacks().isEmpty());
        assertSame(runtime,prompt.getOptions());
        verifyNoInteractions(tool);
    }

    @Test void inheritedCompletionFieldAndRuntimeOverrideFollowNativeOptionMerge() {
        var defaults=OpenAiChatOptions.builder().maxCompletionTokens(30000).build();
        var partial=OpenAiChatOptions.builder().maxTokens(512).build();
        assertEquals(30000,PromptBudget.outputReserveTokens(partial,defaults,1024));
        var explicit=partial.mutate().maxCompletionTokens(128).build();
        assertNull(explicit.getMaxTokens(),"Native builder clears maxTokens when setting maxCompletionTokens");
        assertEquals(128,PromptBudget.outputReserveTokens(explicit,defaults,1024));
        assertEquals(30000,defaults.getMaxCompletionTokens());
        assertNull(partial.getMaxCompletionTokens());
    }

    @Test void providerSpecificRuntimeOutputFieldSurvivesGenericDefaultBuilder() {
        var defaults=ChatOptions.builder().maxTokens(512).build();
        var runtime=OpenAiChatOptions.builder().maxCompletionTokens(30000).build();
        assertEquals(30000,PromptBudget.outputReserveTokens(runtime,defaults,1024));
        assertTrue(PromptBudget.estimateTokens(new Prompt("small",runtime),defaults,1024)>30000);
    }

    @Test void nullAndGenericRuntimeOptionsInheritDefaultToolSchemas() {
        var tool=mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("tool").description("界".repeat(1000)).inputSchema("{\"type\":\"object\"}").build());
        var defaults=OpenAiChatOptions.builder().maxTokens(64).toolCallbacks(List.of(tool)).build();
        assertTrue(PromptBudget.estimateTokens(new Prompt("small"),defaults,64)>1000);
        assertTrue(PromptBudget.estimateTokens(new Prompt("small",ChatOptions.builder().maxTokens(64).build()),defaults,64)>1000);
    }
}
