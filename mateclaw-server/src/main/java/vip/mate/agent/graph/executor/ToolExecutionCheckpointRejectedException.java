package vip.mate.agent.graph.executor;

/** A rejected execution fence must abort the run, never become a normal tool result. */
public final class ToolExecutionCheckpointRejectedException extends RuntimeException {
    public ToolExecutionCheckpointRejectedException(String message) {
        super(message);
    }

    public ToolExecutionCheckpointRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
