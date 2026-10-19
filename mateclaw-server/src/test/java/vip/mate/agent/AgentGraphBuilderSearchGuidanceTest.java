package vip.mate.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.model.AgentEntity;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentGraphBuilderSearchGuidanceTest {
    private AgentGraphBuilder builder;

    @BeforeEach
    void setUp() {
        builder = mock(AgentGraphBuilder.class, CALLS_REAL_METHODS);
        for (var field : AgentGraphBuilder.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                ReflectionTestUtils.setField(builder, field.getName(), mock(field.getType()));
            }
        }
    }

    @Test void mcpOnlyDoesNotRecommendUnselectedWebTools() throws Exception {
        String prompt = prompt(false, Set.of("mcp_catalog_search"));
        assertFalse(prompt.contains("## Web Search Capability"));
        assertTrue(prompt.contains("exact-name"));
        assertTrue(prompt.contains("serverId prefixes"));
    }

    @Test void noToolsHasNoWebGuidance() throws Exception {
        assertFalse(prompt(false, Set.of()).contains("## Web Search Capability"));
    }

    @Test void searchOnlyUsesExactRuntimeName() throws Exception {
        String prompt = prompt(false, Set.of("web_search"));
        assertTrue(prompt.contains("call the `web_search` tool"));
        assertFalse(prompt.contains("`browser_use`"));
        assertFalse(prompt.contains("call the `search` tool"));
    }

    @Test void browserOnlyRecommendsOnlyBrowserInteraction() throws Exception {
        String prompt = prompt(false, Set.of("browser_use"));
        assertTrue(prompt.contains("Call `browser_use` ONLY"));
        assertFalse(prompt.contains("`web_search`"));
        assertFalse(prompt.contains("`search` for the same query"));
    }

    @Test void bothToolsReceivePriorityGuidance() throws Exception {
        String prompt = prompt(false, Set.of("web_search", "browser_use"));
        assertTrue(prompt.contains("call the `web_search` tool"));
        assertTrue(prompt.contains("Call `browser_use` ONLY"));
        assertTrue(prompt.contains("both `browser_use` and `web_search`"));
    }

    @Test void nativeSearchDoesNotClaimEveryReplyContainsSearchResults() throws Exception {
        String prompt = prompt(true, Set.of());
        assertTrue(prompt.contains("model provider"));
        assertFalse(prompt.contains("automatically incorporate live web search results"));
        assertFalse(prompt.contains("already includes real-time search data"));
        assertFalse(prompt.contains("`web_search`"));
        assertFalse(prompt.contains("`browser_use`"));
        assertFalse(prompt.contains("`search` tool"));
    }

    @Test void nativeSearchUsesReturnedResultsBeforeSupplementaryScopedSearch() throws Exception {
        String prompt = prompt(true, Set.of("web_search"));
        assertTrue(prompt.contains("call the `web_search` tool"));
        assertTrue(prompt.contains("When provider results are available and sufficient, use them directly"));
        assertFalse(prompt.contains("`browser_use`"));
        assertFalse(prompt.contains("already includes real-time search data"));
    }

    private String prompt(boolean nativeSearch, Set<String> names) throws Exception {
        AgentEntity entity = new AgentEntity();
        entity.setId(664L);
        entity.setSystemPrompt("Scoped agent identity");
        Method method = AgentGraphBuilder.class.getDeclaredMethod("buildEnhancedPrompt",
                AgentEntity.class, boolean.class, int.class, Set.class);
        method.setAccessible(true);
        return (String) method.invoke(builder, entity, nativeSearch, 1024, names);
    }
}
