package vip.mate.agent.graph.executor;

import vip.mate.agent.context.ChatOrigin;

/** Durable authority check performed immediately before entering a tool callback. */
@FunctionalInterface
public interface ToolExecutionCheckpoint {
    void beforeInvocation(ChatOrigin origin, String invocationKey, String providerCallId);

    default void markUnknownOutcome(ChatOrigin origin, Throwable cause) {}
}
