package vip.mate.llm.dashscope;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class NativeDashScopeChatOptionsTest {
    @Test
    void callerChangesCannotMutatePublishedNativeOptions() {
        List<String> sources = new ArrayList<>(List.of("original"));
        Map<String, Object> search = new LinkedHashMap<>(Map.of("sources", sources));
        NativeDashScopeChatOptions options =
                NativeDashScopeChatOptions.builder()
                        .model("qwen-plus")
                        .searchOptions(search)
                        .build();
        sources.add("modified");
        search.put("enable_source", false);
        Map<?, ?> saved = (Map<?, ?>) options.getNativeParameters().get("search_options");
        assertEquals(List.of("original"), saved.get("sources"));
        assertFalse(saved.containsKey("enable_source"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> options.getNativeParameters().put("enable_search", false));
    }

    @Test
    void nativeOptionsCombineAndCloneRemainIndependent() {
        NativeDashScopeChatOptions original =
                NativeDashScopeChatOptions.builder()
                        .model("qwen-plus")
                        .maxTokens(100)
                        .enableSearch(true)
                        .enableThinking(true)
                        .build();
        NativeDashScopeChatOptions.Builder mutable = original.mutate();
        NativeDashScopeChatOptions copy =
                mutable.clone()
                        .enableSearch(false)
                        .combineWith(ChatOptions.builder().maxTokens(50))
                        .build();
        assertEquals(50, copy.getMaxTokens());
        assertEquals("qwen-plus", copy.getModel());
        assertEquals(false, copy.getNativeParameters().get("enable_search"));
        assertEquals(true, original.getNativeParameters().get("enable_search"));
        assertEquals(true, mutable.build().getNativeParameters().get("enable_search"));
        assertEquals(true, copy.getNativeParameters().get("enable_thinking"));
    }
}
