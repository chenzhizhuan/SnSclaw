package vip.mate.team.event;

/**
 * Published after a plan's steps were handed off to a team's task board, so
 * the dispatch layer sweeps immediately instead of waiting for the scheduled
 * pass. An event (rather than a direct call) keeps the hand-off bridge free
 * of the dispatch service — a direct dependency would close a bean cycle
 * through the agent graph builder.
 *
 * @author SnSclaw Team
 */
public record TeamTasksDelegatedEvent(Long teamId) {
}
