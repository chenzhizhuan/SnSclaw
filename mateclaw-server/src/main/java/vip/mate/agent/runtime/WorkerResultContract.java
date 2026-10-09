package vip.mate.agent.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/** Structured business outcome, independent of whether the model transport succeeded. */
public final class WorkerResultContract {
    private WorkerResultContract() {}
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final String INSTRUCTIONS = """
            Return ONLY a JSON object, without markdown:
            {"status":"COMPLETED|BLOCKED|FAILED","result":"deliverable summary","evidence":"concrete evidence or missing requirement"}.
            Choose COMPLETED only when the requested deliverable is actually satisfied.
            If inputs are missing, return BLOCKED even if team_tasks is unavailable.
            Tool unavailability does not turn a blocked task into a completed task.
            """;
    public record Result(boolean completed, String text, String reason, String evidence) {}

    public static Result parse(String reply, int maxLength) {
        if (reply == null || reply.length() > maxLength) return invalid();
        try {
            var value = JSON.readTree(reply);
            if (value == null || !value.isObject() || value.size() != 3
                    || !value.path("status").isTextual() || !value.path("result").isTextual()
                    || !value.path("evidence").isTextual()) return invalid();
            String status = value.path("status").asText();
            if (!Set.of("COMPLETED", "BLOCKED", "FAILED").contains(status)) return invalid();
            String result = value.path("result").asText().trim();
            String evidence = value.path("evidence").asText().trim();
            if (evidence.isEmpty() || ("COMPLETED".equals(status) && result.isEmpty())) return invalid();
            return new Result("COMPLETED".equals(status), result, status, evidence);
        } catch (java.io.IOException error) {
            return invalid();
        }
    }

    private static Result invalid() { return new Result(false, "", "INVALID_RESULT_CONTRACT", "Structured worker result required"); }
}
