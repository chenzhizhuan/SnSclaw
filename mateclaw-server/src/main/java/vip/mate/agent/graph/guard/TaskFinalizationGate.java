package vip.mate.agent.graph.guard;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.agent.graph.state.ActionExecutionLedger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Pure receipt-consistency policy. Model transport and run-scoped attempt accounting belong to callers. */
public final class TaskFinalizationGate {
    public static final int MAX_CORRECTIVE_ATTEMPTS = 2;
    public static final int MAX_VERDICT_CHARS = 16_384;
    public static final int MAX_REASON_CHARS = 1_024;
    private static final int MAX_RECOVERY_CLAIMS = 64;
    private static final Set<String> FIELDS = Set.of("decision", "taskComplete", "remainingWork",
            "failedCallCount", "recoveryClaims", "reason");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public enum Outcome { ACCEPT, REVISE, CONTINUE, INCOMPLETE }
    /** Endorsed recovery claims only. A rejected draft claim belongs in a REVISE reason,
     * not here: every listed pair must satisfy canonical same-operation recovery evidence. */
    public record RecoveryClaim(String failedCallId, String successfulCallId) {}
    public record Verdict(Outcome decision, boolean taskComplete, boolean remainingWork,
                          int failedCallCount, List<RecoveryClaim> recoveryClaims, String reason) {
        public Verdict {
            recoveryClaims = recoveryClaims == null ? null : List.copyOf(recoveryClaims);
        }
    }
    public record Result(Outcome outcome, String reason) {}

    /** Exact JSON object only; no coercion, unknown fields, duplicate keys, or trailing input. */
    public Optional<Verdict> parse(String json) {
        if (json == null || json.length() > MAX_VERDICT_CHARS) return Optional.empty();
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!exactFields(node, FIELDS) || !node.get("decision").isTextual()
                    || !node.get("taskComplete").isBoolean() || !node.get("remainingWork").isBoolean()
                    || !node.get("failedCallCount").isIntegralNumber()
                    || !node.get("failedCallCount").canConvertToInt()
                    || node.get("failedCallCount").intValue() < 0
                    || !node.get("reason").isTextual() || node.get("reason").textValue().length() > MAX_REASON_CHARS
                    || !node.get("recoveryClaims").isArray() || node.get("recoveryClaims").size() > MAX_RECOVERY_CLAIMS) {
                return Optional.empty();
            }
            Outcome decision = Outcome.valueOf(node.get("decision").textValue());
            if (decision == Outcome.INCOMPLETE) return Optional.empty();
            List<RecoveryClaim> claims = new ArrayList<>();
            for (JsonNode claim : node.get("recoveryClaims")) {
                if (!exactFields(claim, Set.of("failedCallId", "successfulCallId"))
                        || !validId(claim.get("failedCallId")) || !validId(claim.get("successfulCallId"))) return Optional.empty();
                claims.add(new RecoveryClaim(claim.get("failedCallId").textValue(), claim.get("successfulCallId").textValue()));
            }
            return Optional.of(new Verdict(decision, node.get("taskComplete").booleanValue(),
                    node.get("remainingWork").booleanValue(), node.get("failedCallCount").intValue(), claims,
                    node.get("reason").textValue()));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    /** Safe structural diagnostics only; parse() remains the sole acceptance boundary. */
    public enum ParseFailureCode {
        NONE, NOT_EVALUATED, EMPTY_INPUT, VERDICT_SIZE_LIMIT, FENCED_JSON, INVALID_JSON,
        DUPLICATE_FIELDS, TRAILING_CONTENT, NON_OBJECT, FIELD_SET, FIELD_TYPES, COUNT_RANGE,
        INVALID_DECISION, REASON_LIMIT, CLAIMS_LIMIT, CLAIM_FIELD_SET, CLAIM_ID
    }
    public ParseFailureCode parseFailureCode(String json) {
        if(json==null || json.isBlank()) return ParseFailureCode.EMPTY_INPUT;
        if(json.length()>MAX_VERDICT_CHARS) return ParseFailureCode.VERDICT_SIZE_LIMIT;
        if(json.stripLeading().startsWith("```")) return ParseFailureCode.FENCED_JSON;
        try {
            JsonNode node=MAPPER.readTree(json);
            if(node==null || !node.isObject()) return ParseFailureCode.NON_OBJECT;
            if(!exactFields(node,FIELDS)) return ParseFailureCode.FIELD_SET;
            if(!node.get("decision").isTextual() || !node.get("taskComplete").isBoolean()
                    || !node.get("remainingWork").isBoolean() || !node.get("failedCallCount").isIntegralNumber()
                    || !node.get("reason").isTextual() || !node.get("recoveryClaims").isArray())
                return ParseFailureCode.FIELD_TYPES;
            if(!node.get("failedCallCount").canConvertToInt() || node.get("failedCallCount").intValue()<0)
                return ParseFailureCode.COUNT_RANGE;
            if(node.get("reason").textValue().length()>MAX_REASON_CHARS) return ParseFailureCode.REASON_LIMIT;
            if(node.get("recoveryClaims").size()>MAX_RECOVERY_CLAIMS) return ParseFailureCode.CLAIMS_LIMIT;
            try {
                if(Outcome.valueOf(node.get("decision").textValue())==Outcome.INCOMPLETE)
                    return ParseFailureCode.INVALID_DECISION;
            } catch(IllegalArgumentException invalid) {return ParseFailureCode.INVALID_DECISION;}
            for(JsonNode claim:node.get("recoveryClaims")) {
                if(!exactFields(claim,Set.of("failedCallId","successfulCallId"))) return ParseFailureCode.CLAIM_FIELD_SET;
                if(!validId(claim.get("failedCallId")) || !validId(claim.get("successfulCallId"))) return ParseFailureCode.CLAIM_ID;
            }
            return ParseFailureCode.NONE;
        } catch(com.fasterxml.jackson.core.JsonParseException invalid) {
            return String.valueOf(invalid.getOriginalMessage()).contains("Duplicate field")
                    ? ParseFailureCode.DUPLICATE_FIELDS : ParseFailureCode.INVALID_JSON;
        } catch(com.fasterxml.jackson.databind.exc.MismatchedInputException invalid) {
            return String.valueOf(invalid.getOriginalMessage()).contains("Trailing token")
                    ? ParseFailureCode.TRAILING_CONTENT : ParseFailureCode.INVALID_JSON;
        } catch(Exception invalid) {return ParseFailureCode.INVALID_JSON;}
    }

    private static boolean validId(JsonNode node) {
        return node.isTextual() && !node.textValue().isBlank() && node.textValue().length() <= 1_024;
    }

    private static boolean exactFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) return false;
        var names = node.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }

    /** Pending model progress can inform a verdict; it never independently authorizes acceptance. */
    public Result evaluate(Verdict verdict, ActionExecutionLedger ledger, int attempts,
                           boolean terminalRuntime, boolean canContinue) {
        if (terminalRuntime) return incomplete("Runtime is terminal");
        if (attempts < 0 || ledger == null || verdict == null) return incomplete("Verification unavailable");
        if (verdict.decision() == null || verdict.decision() == Outcome.INCOMPLETE
                || verdict.reason() == null || verdict.reason().length() > MAX_REASON_CHARS
                || verdict.recoveryClaims() == null || verdict.recoveryClaims().size() > MAX_RECOVERY_CLAIMS
                || verdict.failedCallCount() != ledger.failedCallCount()) return incomplete("Invalid verification facts");
        if (verdict.taskComplete() && verdict.remainingWork()) return incomplete("Contradictory task outcome");
        if (verdict.decision() == Outcome.ACCEPT && (!verdict.taskComplete() || verdict.remainingWork()))
            return incomplete("Task is unfinished");
        if (verdict.decision() == Outcome.CONTINUE && (verdict.taskComplete() || !verdict.remainingWork()))
            return incomplete("Continuation requires unfinished work");
        boolean unsupportedRecovery = false;
        for (RecoveryClaim claim : verdict.recoveryClaims()) {
            if (claim == null || claim.failedCallId() == null || claim.successfulCallId() == null)
                return incomplete("Unsupported original-operation recovery claim");
            var failed = ledger.receipts().get(claim.failedCallId());
            var success = ledger.receipts().get(claim.successfulCallId());
            if (failed == null || success == null || failed.status() != ActionExecutionLedger.Status.FAILED
                    || success.status() != ActionExecutionLedger.Status.SUCCEEDED)
                return incomplete("Unsupported original-operation recovery claim");
            unsupportedRecovery |= !ledger.supportedRecovery(claim.failedCallId(), claim.successfulCallId());
        }
        if (unsupportedRecovery) {
            if (attempts >= MAX_CORRECTIVE_ATTEMPTS || !canContinue)
                return incomplete("Correction allowance exhausted");
            return new Result(Outcome.REVISE,
                    "The known receipt pair does not establish original-request recovery. Remove unsupported recovery claims; "
                    + "changed parameters are a different request, and eventual task completion after a failure is distinct "
                    + "from recovery of the failed original operation. State the original failure separately.");
        }
        if (verdict.decision() == Outcome.ACCEPT) return new Result(Outcome.ACCEPT, verdict.reason());
        if (attempts >= MAX_CORRECTIVE_ATTEMPTS || !canContinue) return incomplete("Correction allowance exhausted");
        // Work-state facts take precedence over a model's prose-only repair label.
        // This still consumes the same bounded correction allowance and never accepts a draft.
        Outcome next = verdict.decision() == Outcome.REVISE && !verdict.taskComplete() && verdict.remainingWork()
                ? Outcome.CONTINUE : verdict.decision();
        return new Result(next, verdict.reason());
    }

    private static Result incomplete(String reason) { return new Result(Outcome.INCOMPLETE, reason); }
}
