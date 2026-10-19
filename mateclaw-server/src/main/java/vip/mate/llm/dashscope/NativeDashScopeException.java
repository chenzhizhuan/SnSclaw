package vip.mate.llm.dashscope;

/** Preserves native provider error codes for model failover classification. */
public final class NativeDashScopeException extends RuntimeException {
    private final int status;
    private final String code;
    private final String requestId;

    public NativeDashScopeException(int status, String code, String message, String requestId) {
        super(
                "DashScope HTTP "
                        + status
                        + " ["
                        + code
                        + "] "
                        + message
                        + (requestId == null ? "" : " (requestId: " + requestId + ")"));
        this.status = status;
        this.code = code;
        this.requestId = requestId;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getRequestId() {
        return requestId;
    }
}
