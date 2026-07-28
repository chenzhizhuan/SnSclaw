package vip.mate.memory.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.memory.spi.MemoryProvider;

import java.util.List;

/**
 * Session search provider — enables the agent to search conversation history.
 * <p>
 * Provides no system prompt block (search is on-demand via tool).
 * Tool (SessionSearchTool) is auto-discovered by ToolRegistry.
 *
 * @author SnSclaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSearchProvider implements MemoryProvider {

    @Override
    public String id() {
        return "session_search";
    }

    @Override
    public int order() {
        return 20; // after structured (10)
    }

    @Override
    public String systemPromptBlock(Long agentId) {
        return ""; // search is on-demand via tool, no static prompt block
    }

    @Override
    public List<Object> getToolBeans() {
        return List.of(); // auto-discovered by ToolRegistry
    }
}
