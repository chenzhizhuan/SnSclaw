package vip.mate.agent.runtime.state;

import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.agent.delegation.SubagentRunContext;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import vip.mate.tool.builtin.DelegationContext;

import java.util.Objects;
import java.util.concurrent.Callable;

/** Restores captured per-run context for synchronous worker callbacks. */
public final class RuntimeExecutionScope {
    private RuntimeExecutionScope() {}

    public static <T> T call(RunState state, Callable<T> callback) throws Exception {
        Objects.requireNonNull(callback);
        try (AutoCloseable scope = open(state)) {
            return callback.call();
        }
    }

    /** Open and close on the same thread, in reverse nesting order. Closing twice is harmless. */
    public static AutoCloseable open(RunState state) {
        Objects.requireNonNull(state);
        Thread owner = Thread.currentThread();
        ChatOrigin previousOrigin = ChatOriginHolder.get();
        String previousThinking = ThinkingLevelHolder.get();
        ChatOrigin origin =
                state.value(MateClawStateKeys.CHAT_ORIGIN, ChatOrigin.class)
                        .orElse(ChatOrigin.EMPTY);
        String thinking =
                state.value(MateClawStateKeys.REQUESTED_THINKING_LEVEL, String.class).orElse(null);
        SubagentRunContext delegation =
                state.value(MateClawStateKeys.SUBAGENT_RUN_CONTEXT, SubagentRunContext.class)
                        .orElse(SubagentRunContext.ROOT);
        DelegationContext.push(delegation);
        ChatOriginHolder.set(origin);
        if (thinking == null) ThinkingLevelHolder.clear();
        else ThinkingLevelHolder.set(thinking);
        return new AutoCloseable() {
            private boolean closed;

            @Override
            public void close() {
                if (Thread.currentThread() != owner)
                    throw new IllegalStateException(
                            "Runtime scope must close on its opening thread");
                if (closed) return;
                closed = true;
                DelegationContext.exit();
                if (ChatOrigin.EMPTY.equals(previousOrigin)) ChatOriginHolder.clear();
                else ChatOriginHolder.set(previousOrigin);
                if (previousThinking == null) ThinkingLevelHolder.clear();
                else ThinkingLevelHolder.set(previousThinking);
            }
        };
    }
}
