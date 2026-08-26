package vip.mate.goal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.goal.service.GoalContinuationStore;
import vip.mate.goal.service.GoalService;
import vip.mate.workspace.conversation.ConversationService;

/** Durable execution status, separate from goal acceptance status. */
@RestController
@RequiredArgsConstructor
public class GoalExecutionController {
    private final GoalService goals;
    private final GoalContinuationStore store;
    private final ConversationService conversations;

    @GetMapping("/api/v1/goals/{id}/execution")
    public R<GoalContinuationStore.Continuation> execution(@PathVariable Long id, Authentication auth) {
        var goal=goals.getById(id);
        if (auth==null || !conversations.isConversationOwner(goal.getConversationId(),auth.getName())) {
            throw new MateClawException("err.goal.forbidden",403,"Not the conversation owner");
        }
        return R.ok(store.get(id));
    }
}
