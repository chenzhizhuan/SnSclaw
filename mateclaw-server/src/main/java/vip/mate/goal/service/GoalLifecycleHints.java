package vip.mate.goal.service;

import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalStatus;

/** A minimal authoritative snapshot, never a substitute for the service's transition checks. */
public final class GoalLifecycleHints {
    private GoalLifecycleHints() {}

    public static String current(GoalService service, String conversationId) {
        if (service == null || conversationId == null || conversationId.isBlank()) return "";
        GoalEntity active = service.findActiveByConversation(conversationId);
        return render(active != null ? active : service.findLatestByConversation(conversationId));
    }

    public static String render(GoalEntity goal) {
        if (goal == null || goal.getStatus() == null) return "";
        return "\n\nCurrent persisted goal: id=" + goal.getId() + ", status=" + goal.getStatus().getValue()
                + ". This snapshot overrides lifecycle claims in earlier chat replies. "
                + "Report a state transition only after a successful goal tool response or getGoalStatus verification. "
                + "Producing an answer does not itself resume or complete a goal. "
                + (goal.getStatus() == GoalStatus.PAUSED
                    ? "If the user explicitly asks to resume, call resumeGoal on this conversation. "
                      + "Otherwise keep it paused; do not create a replacement or report it completed. "
                    : "Do not report completed until the completion gate confirms it. ");
    }
}
