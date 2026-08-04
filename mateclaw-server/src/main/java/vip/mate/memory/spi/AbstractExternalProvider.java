package vip.mate.memory.spi;

import java.util.Collections;
import java.util.List;

/**
 * Abstract base class for external memory providers (vector DB, Honcho, etc.).
 * <p>
 * Provides default no-op implementations for all optional methods.
 * Subclasses typically only need to override:
 * <ul>
 *   <li>{@link #id()} — unique provider identifier</li>
 *   <li>{@link #isAvailable()} — check if configured</li>
 *   <li>{@link #prefetch(Long, String)} — per-turn recall</li>
 *   <li>{@link #syncTurn(Long, String, String, String)} — post-turn persistence</li>
 * </ul>
 * <p>
 * To implement an external provider:
 * 1. Extend this class
 * 2. Annotate with {@code @Component}
 * 3. Override the methods you need
 * 4. The provider will be auto-discovered by MemoryManager via Spring injection
 *
 * @author SnSclaw
 */
public abstract class AbstractExternalProvider implements MemoryProvider {

    @Override
    public int order() {
        return 50; // after built-in providers
    }

    @Override
    public boolean isAvailable() {
        return false; // disabled by default, override to enable
    }

    @Override
    public String systemPromptBlock(Long agentId) {
        return "";
    }

    @Override
    public String prefetch(Long agentId, String userQuery) {
        return "";
    }

    @Override
    public void syncTurn(Long agentId, String conversationId,
                         String userMessage, String assistantReply) {
    }

    @Override
    public List<Object> getToolBeans() {
        return Collections.emptyList();
    }

    @Override
    public void onSessionEnd(Long agentId, String conversationId) {
    }

    @Override
    public String onPreCompress(Long agentId, List<?> messages) {
        return "";
    }
}
