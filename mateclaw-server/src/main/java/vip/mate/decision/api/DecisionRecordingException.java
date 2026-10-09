package vip.mate.decision.api;

public class DecisionRecordingException extends RuntimeException {
    public DecisionRecordingException() { super("Decision audit unavailable; preserve current business state"); }

    /** Locate audit failures through transaction, graph, and asynchronous execution wrappers. */
    public static DecisionRecordingException find(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DecisionRecordingException recording) return recording;
        }
        return null;
    }
}
