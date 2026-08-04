package vip.mate.memory.lifecycle;

/**
 * Published after syncAll completes for a turn.
 *
 * @param context        the turn context
 * @param assistantReply the LLM response text
 * @author SnSclaw
 */
public record TurnCompletedEvent(TurnContext context, String assistantReply) {}
