package vip.mate.decision.api;

public record DecisionScope(Long workspaceId, Long subjectId, Long runId, String conversationId, String attemptId) {
    public DecisionScope(Long workspaceId, Long subjectId, Long runId) { this(workspaceId, subjectId, runId, null, null); }
    public DecisionScope {
        if (conversationId != null && (conversationId.isBlank() || conversationId.length() > 128)
                || attemptId != null && (attemptId.isBlank() || attemptId.length() > 128))
            throw new IllegalArgumentException("Invalid scope identifier size");
    }
}
