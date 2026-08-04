package vip.mate.team.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.model.TeamTaskEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Team-scoped SSE event channel, backed by a synthetic conversation id on the
 * existing stream tracker so registration, ring buffering, replay-by-last-id
 * and heartbeats are all inherited instead of re-invented. One channel per
 * team, alive for the application's lifetime; publishing lazily (re)registers,
 * so a recycled channel heals on the next event and subscribers simply
 * reconnect.
 *
 * Task events are additionally mirrored onto the originating lead
 * conversation's stream (when the task has one) for in-chat observability.
 *
 * @author SnSclaw
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamEventChannel {

    static final String CHANNEL_PREFIX = "team-events-";

    private final ChatStreamTracker streamTracker;

    /** Publish a task lifecycle event to the team channel (+ lead stream if any). */
    public void publishTaskEvent(TeamTaskEntity task, String event, Map<String, Object> extra) {
        if (task == null) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>(extra == null ? Map.of() : extra);
            payload.put("taskId", String.valueOf(task.getId()));
            payload.put("taskNumber", task.getTaskNumber());
            payload.put("subject", task.getSubject());
            payload.put("teamId", String.valueOf(task.getTeamId()));
            payload.put("assigneeAgentId", String.valueOf(task.getAssigneeAgentId()));

            String channelId = channelId(task.getTeamId());
            streamTracker.register(channelId);
            streamTracker.broadcastObject(channelId, event, payload);

            if (task.getLeadConversationId() != null) {
                streamTracker.broadcastObject(task.getLeadConversationId(), event, payload);
            }
        } catch (Exception e) {
            // Events are a side channel — never let them affect the task flow.
            log.debug("Team event '{}' broadcast skipped: {}", event, e.getMessage());
        }
    }

    /** Attach a subscriber, replaying buffered events newer than lastEventId. */
    public boolean attach(Long teamId, SseEmitter emitter, long lastEventId) {
        String channelId = channelId(teamId);
        streamTracker.register(channelId);
        return streamTracker.attach(channelId, emitter, lastEventId);
    }

    static String channelId(Long teamId) {
        return CHANNEL_PREFIX + teamId;
    }
}
