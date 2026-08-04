package vip.mate.plugin.mem0;

import org.slf4j.Logger;
import vip.mate.plugin.api.memory.PluginMemoryProvider;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Memory provider that bridges SnSclaw's per-turn lifecycle to a self-hosted
 * Mem0 service.
 * <p>
 * Behavior matrix:
 * <ul>
 *   <li>{@code systemPromptBlock} — no-op (returns ""), aligns with SessionSearchProvider</li>
 *   <li>{@code prefetch(agentId, query, ownerKey)} — when {@code searchEnabled}
 *       and {@code ownerKey} is non-blank, calls {@code POST /memories/search/}
 *       and returns a {@code [Mem0 Recall]} block. Returns "" on any failure
 *       or when disabled.</li>
 *   <li>{@code syncTurn(agentId, conversationId, messages, ownerKey)} — when
 *       {@code syncEnabled} and {@code ownerKey} is non-blank, asynchronously
 *       pushes the turn to {@code POST /memories/} under {@code user_id =
 *       ownerKey}, the same identifier prefetch recalls by. Failures are
 *       logged and swallowed; never blocks the response path. The four-arg
 *       variant (no ownerKey) skips — writing under any other identifier
 *       would produce memories that owner-scoped recall can never surface.</li>
 *   <li>{@code getToolBeans} — empty (no agent-facing tools in v1)</li>
 * </ul>
 *
 * <p>Per-owner isolation: {@code ownerKey} (e.g. {@code "user:42"}) is passed
 * verbatim as Mem0's {@code user_id}; {@code agentId} as Mem0's {@code agent_id}.
 * When {@code ownerKey} is null/blank, both recall and sync are skipped — Mem0
 * requires {@code user_id}.
 *
 * <p>Asynchronous sync: a single-thread daemon executor is used
 * so that bursts of turns don't pile up on the platform's request thread.
 *
 * @author SnSclaw
 */
class Mem0Provider implements PluginMemoryProvider {

    static final String ID = "mem0";

    private final Mem0Config config;
    private final Mem0Client client;
    private final Logger log;
    private final Executor async;

    Mem0Provider(Mem0Config config, Mem0Client client, Logger log) {
        this.config = config;
        this.client = client;
        this.log = log;
        // Single-thread executor is enough — syncTurn calls are sequential per
        // agent and not latency-sensitive; the platform's request thread must
        // not be blocked. A bounded single-thread queue keeps memory footprint
        // predictable even under burst load.
        this.async = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mem0-sync");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        // Same as the SPI default; declared explicitly for clarity.
        return 200;
    }

    @Override
    public boolean isAvailable() {
        // Provider is "available" if at least one of recall/sync can fire.
        return config.isUsable() && (config.searchEnabled() || config.syncEnabled());
    }

    @Override
    public String systemPromptBlock(Long agentId) {
        return "";
    }

    @Override
    public String prefetch(Long agentId, String userQuery) {
        // Two-arg variant: no owner key → cannot isolate per-user → skip.
        // Mem0 requires user_id; without it the call would either fail or
        // return global memories breaking per-owner isolation.
        return "";
    }

    @Override
    public String prefetch(Long agentId, String userQuery, String ownerKey) {
        if (!config.searchEnabled()) {
            return "";
        }
        if (ownerKey == null || ownerKey.isBlank()) {
            return "";
        }
        if (userQuery == null || userQuery.isBlank()) {
            return "";
        }
        try {
            List<String> memories = client.searchMemories(
                    ownerKey, agentId == null ? null : agentId.toString(), userQuery);
            if (memories.isEmpty()) {
                return "";
            }
            return formatRecallBlock(memories);
        } catch (Exception e) {
            // Fault isolation: log and return empty so the platform falls back
            // to the other (local) providers without affecting the response.
            log.warn("[Mem0] prefetch failed for agent={} owner={}: {}",
                    agentId, ownerKey, e.getMessage());
            return "";
        }
    }

    @Override
    public void syncTurn(Long agentId, String conversationId,
                         String userMessage, String assistantReply) {
        // Four-arg variant: no owner key → skip. Mem0 keys memories by user_id;
        // writing under any fallback identifier (e.g. agentId) would store
        // memories that owner-scoped prefetch can never recall.
    }

    @Override
    public void syncTurn(Long agentId, String conversationId,
                         String userMessage, String assistantReply, String ownerKey) {
        if (!config.syncEnabled()) {
            return;
        }
        if (ownerKey == null || ownerKey.isBlank()) {
            // Same guard as prefetch: Mem0 requires user_id; without the owner
            // key the write would break per-owner isolation.
            return;
        }
        if ((userMessage == null || userMessage.isBlank())
                && (assistantReply == null || assistantReply.isBlank())) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                client.addMemories(ownerKey, agentId == null ? null : agentId.toString(),
                        conversationId, userMessage, assistantReply);
            } catch (Exception e) {
                log.debug("[Mem0] syncTurn failed for agent={} owner={}: {}",
                        agentId, ownerKey, e.getMessage());
            }
        }, async);
    }

    @Override
    public void onSessionEnd(Long agentId, String conversationId) {
        // No Mem0-specific session cleanup needed in v1.
    }

    /**
     * Format the recalled memories into a labeled block.
     * <p>
     * The {@code [Mem0 Recall]} label is intentional: it lets the LLM
     * distinguish this block from the local providers' output and avoid
     * treating it as authoritative PROFILE.md content.
     */
    private String formatRecallBlock(List<String> memories) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Mem0 Recall — semantic matches from external service, treat as hints]\n");
        for (int i = 0; i < memories.size(); i++) {
            sb.append(i + 1).append(". ").append(memories.get(i)).append('\n');
        }
        return sb.toString();
    }
}
