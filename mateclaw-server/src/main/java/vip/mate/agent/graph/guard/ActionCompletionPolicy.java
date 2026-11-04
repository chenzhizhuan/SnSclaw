package vip.mate.agent.graph.guard;

import vip.mate.agent.graph.state.ActionExecutionLedger;

import java.util.Optional;
import java.util.regex.Pattern;

/** Pure completion decision for action-required ReAct turns. */
public final class ActionCompletionPolicy {

    public static final int MAX_RETRIES = 1;

    public enum Decision { ALLOW, WAIT_FOR_USER, RETRY, UNVERIFIED, FAILED }

    private static final Pattern USER_QUESTION = Pattern.compile(
            "^(?:需要我|要我|是否需要我|你(?:希望|想|需要)|您(?:希望|想|需要)|请问|"
                    + "(?:能否|可以)(?:请)?(?:你|您)|"
                    + "would you like\\b|do you want\\b|should I\\b|may I\\b|can I\\b|"
                    + "(?:could|can|would) you\\b|"
                    + "(?:which|what)\\b.*\\b(?:would you|do you|should I)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_INPUT_REQUEST = Pattern.compile(
            "^(?:请(?:确认|提供|选择|补充|告知)|please\\s+(?:provide|confirm|choose|select|specify)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_APPENDIX = Pattern.compile(
            "^(?:#{1,6}\\h*)?(?:\\*\\*|__)?"
                    + "(?:(?:(?:已加载)?技能的?|查询|参考|信息)?来源(?:信息)?|sources?|references?)"
                    + "(?:\\*\\*|__)?\\h*[:：]?\\h*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private ActionCompletionPolicy() {
    }

    public static Decision evaluate(boolean actionRequired, int retryCount,
                                    ActionExecutionLedger ledger) {
        return evaluate(actionRequired, retryCount, ledger, null);
    }

    public static Decision evaluate(boolean actionRequired, int retryCount,
                                    ActionExecutionLedger ledger, String content) {
        // Asking for input is a turn boundary, not an unsupported completion claim.
        // Check before receipts: a failed call may also need clarification to proceed.
        if (userInputRequest(content).isPresent()) return Decision.WAIT_FOR_USER;
        if (!actionRequired) return Decision.ALLOW;
        ActionExecutionLedger evidence = ledger != null ? ledger : ActionExecutionLedger.empty();
        if (evidence.hasSuccessfulSubstantiveCall()) return Decision.ALLOW;
        if (evidence.hasSubstantiveAttempt()) return Decision.FAILED;
        return retryCount < MAX_RETRIES ? Decision.RETRY : Decision.UNVERIFIED;
    }

    /** Terminal input request, preserving its formatting but excluding preceding claims. */
    public static Optional<String> userInputRequest(String content) {
        if (content == null || content.isBlank()) return Optional.empty();
        var terminalRequest = terminalUserInputRequest(content.strip());
        if (terminalRequest.isPresent()) return terminalRequest;
        String original = content.strip();
        // Citation appendices are presentation, not a decision to proceed past
        // the preceding question. Keep ordinary trailing prose in the decision.
        var sources = SOURCE_APPENDIX.matcher(original);
        if (sources.find()) {
            original = original.substring(0, sources.start()).stripTrailing()
                    .replaceFirst("\\s*\\n[-*_]{3,}\\s*$", "").stripTrailing();
        }
        if (original.isBlank()) return Optional.empty();
        return terminalUserInputRequest(original);
    }

    private static Optional<String> terminalUserInputRequest(String original) {
        String text = original.replaceFirst("(?:\\*\\*|__|\\*)$", "").stripTrailing();
        if (text.isBlank()) return Optional.empty();
        int end = text.length() - 1;
        char terminal = text.charAt(end);
        boolean question = terminal == '?' || terminal == '？';
        // Only inspect the final sentence, so quoted examples, rhetorical questions,
        // and an earlier question followed by "I'll proceed" cannot bypass the gate.
        int start = end;
        if (question || terminal == '.' || terminal == '。' || terminal == '!'
                || terminal == '！') start--;
        while (start >= 0 && "\n。！？.!?".indexOf(text.charAt(start)) < 0) start--;
        String sentence = text.substring(start + 1).stripLeading()
                .replaceFirst("^(?:[-+*]|\\d+\\.)\\s+", "")
                .replaceFirst("^(?:\\*\\*|__|\\*)", "");
        boolean requested = USER_INPUT_REQUEST.matcher(sentence).find()
                || question && USER_QUESTION.matcher(sentence).find();
        return requested ? Optional.of(original.substring(start + 1).stripLeading()) : Optional.empty();
    }
}
