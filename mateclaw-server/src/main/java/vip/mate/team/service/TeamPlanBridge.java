package vip.mate.team.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.planning.model.PlanEntity;
import vip.mate.planning.service.PlanningService;
import vip.mate.team.event.TeamTasksDelegatedEvent;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.AgentTeamMemberEntity;
import vip.mate.team.model.TeamRole;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bridges the Plan-Execute graph onto the team task board. When a plan's
 * lead-of-team owner assigns every step to a team member, the steps become
 * board tasks (dependencies mapped to blockedBy), the plan parks in the
 * "delegated" status and the lead's turn ends — execution then runs through
 * the board's dispatch/announce machinery instead of the serial per-step
 * delegation pipeline. Any later inbound message resumes through
 * {@link #checkParkedPlan}: settled boards feed the plan summary, in-flight
 * boards produce a progress answer.
 *
 * Deliberately does NOT depend on the dispatch service (bean cycle through
 * the agent graph builder) — a {@link TeamTasksDelegatedEvent} triggers the
 * immediate sweep instead.
 *
 * @author SnSclaw
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamPlanBridge {

    /** Task subject cap; the full step text rides in the description. */
    static final int SUBJECT_MAX_CHARS = 120;

    private final TeamService teamService;
    private final TeamTaskService taskService;
    private final PlanningService planningService;
    private final AgentMapper agentMapper;
    private final ApplicationEventPublisher eventPublisher;

    // ==================== triage support ====================

    /** The team this agent leads, if any. */
    public Optional<AgentTeamEntity> leadTeam(Long agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        return teamService.getTeamForAgent(agentId)
                .filter(team -> teamService.isLead(team, agentId));
    }

    /** Assignable members (lead excluded), for the planner's roster message. */
    public List<AgentEntity> roster(AgentTeamEntity team) {
        List<AgentEntity> members = new ArrayList<>();
        for (AgentTeamMemberEntity member : teamService.listMembers(team.getId())) {
            if (TeamRole.LEAD.equals(member.getRole())) {
                continue;
            }
            AgentEntity agent = agentMapper.selectById(member.getAgentId());
            if (agent != null) {
                members.add(agent);
            }
        }
        return members;
    }

    /**
     * Map the planner's step_agents names onto team member ids. Returns null
     * unless EVERY step resolves to a member — the hand-off is all-or-nothing
     * (mixed local/board plans are out of scope), and a null keeps the plan
     * on the legacy serial pipeline.
     */
    public List<Long> resolveMembers(AgentTeamEntity team, List<String> steps,
                                     List<String> stepAgents) {
        if (steps == null || steps.isEmpty() || stepAgents == null) {
            return null;
        }
        Map<String, Long> byName = new HashMap<>();
        for (AgentEntity member : roster(team)) {
            if (member.getName() != null) {
                byName.put(member.getName().trim().toLowerCase(), member.getId());
            }
        }
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            String name = i < stepAgents.size() ? stepAgents.get(i) : null;
            Long id = (name == null || name.isBlank()) ? null
                    : byName.get(name.trim().toLowerCase());
            if (id == null) {
                return null;
            }
            ids.add(id);
        }
        return ids;
    }

    // ==================== hand-off ====================

    /**
     * Create one board task per step (dependencies → blockedBy), park the plan
     * as "delegated" and nudge the dispatcher. Returns the announcement text
     * the lead streams to the user before ending its turn.
     *
     * @param stepDeps per-step prerequisite step indices (0-based, each
     *                 referencing an earlier step); the caller guarantees
     *                 validity via its sequential-chain fallback
     */
    public String delegatePlan(AgentTeamEntity team, Long planId, String goal,
                               List<String> steps, List<List<Integer>> stepDeps,
                               List<Long> memberIds, String leadConversationId) {
        List<TeamTaskEntity> created = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.get(i);
            List<Long> blockedBy = new ArrayList<>();
            for (Integer depIndex : stepDeps.get(i)) {
                blockedBy.add(created.get(depIndex).getId());
            }
            TeamTaskEntity task = taskService.createTask(TeamTaskCreateCommand.builder()
                    .teamId(team.getId())
                    .subject(subjectOf(step))
                    .description(step + "\n\n[Plan context]\nOverall request: " + goal)
                    .assigneeAgentId(memberIds.get(i))
                    .createdByAgentId(team.getLeadAgentId())
                    .blockedBy(blockedBy.isEmpty() ? null : blockedBy)
                    .leadConversationId(leadConversationId)
                    .channel("plan")
                    .metadata(new JSONObject()
                            .set("planId", String.valueOf(planId))
                            .set("stepIndex", i)
                            .toString())
                    .build());
            created.add(task);
        }
        planningService.markPlanDelegated(planId);
        eventPublisher.publishEvent(new TeamTasksDelegatedEvent(team.getId()));
        log.info("Plan {} delegated to team {} board as {} task(s)", planId, team.getId(),
                created.size());
        return buildAnnouncement(created, stepDeps);
    }

    // ==================== resume gate ====================

    /** Outcome of the parked-plan check on an inbound message. */
    public sealed interface ParkedPlanState permits None, Settled, InFlight {
    }

    public record None() implements ParkedPlanState {
    }

    /** All board tasks terminal — resume into the plan summary. */
    public record Settled(Long planId, String goal, List<String> steps,
                          List<String> completedResults) implements ParkedPlanState {
    }

    /** Board still working — answer with live progress, stay parked. */
    public record InFlight(String progressText) implements ParkedPlanState {
    }

    /**
     * Inspect the conversation's parked plan, if any. Settled boards sync the
     * sub-plan mirror and return the step results (with deliverable links) in
     * the summary node's expected format; in-flight boards return a rendered
     * progress snapshot.
     */
    public ParkedPlanState checkParkedPlan(String conversationId) {
        PlanEntity plan = planningService.findDelegatedPlan(conversationId);
        if (plan == null) {
            return new None();
        }
        Optional<AgentTeamEntity> teamOpt = leadTeam(parseAgentId(plan.getAgentId()));
        if (teamOpt.isEmpty()) {
            // Team dissolved or lead reassigned while parked — nothing to wait
            // for; fail the plan so the conversation is not wedged forever.
            planningService.markPlanFailed(plan.getId(), "team no longer available");
            return new None();
        }
        List<TeamTaskEntity> tasks = taskService.listTasksByPlan(teamOpt.get().getId(), plan.getId());
        if (tasks.isEmpty()) {
            planningService.markPlanFailed(plan.getId(), "board tasks vanished");
            return new None();
        }
        boolean allTerminal = tasks.stream()
                .allMatch(task -> TeamTaskStatus.isTerminal(task.getStatus()));
        List<String> steps = planningService.getSubPlans(plan.getId()).stream()
                .map(sub -> sub.getDescription())
                .toList();
        if (!allTerminal) {
            return new InFlight(buildProgressText(tasks));
        }
        return new Settled(plan.getId(), plan.getGoal(), steps, settle(plan.getId(), tasks));
    }

    /** Sync the sub-plan mirror from terminal tasks and render step results. */
    private List<String> settle(Long planId, List<TeamTaskEntity> tasks) {
        List<String> results = new ArrayList<>();
        for (TeamTaskEntity task : tasks) {
            int stepIndex = stepIndexOf(task);
            StringBuilder line = new StringBuilder();
            if (TeamTaskStatus.COMPLETED.equals(task.getStatus())) {
                planningService.updateSubPlanResult(planId, stepIndex,
                        task.getResult() == null ? "" : task.getResult());
                line.append(String.format("步骤%d结果：%s", stepIndex + 1,
                        task.getResult() == null ? "(无输出)" : task.getResult()));
            } else {
                String reason = task.getReason() == null ? task.getStatus() : task.getReason();
                planningService.updateSubPlanFailure(planId, stepIndex, reason);
                line.append(String.format("步骤%d未完成（%s）：%s", stepIndex + 1,
                        task.getStatus(), reason));
            }
            for (TeamTaskService.Deliverable file : taskService.listDeliverables(task)) {
                line.append("\n交付物：").append(file.name()).append(" → ").append(file.url());
            }
            results.add(line.toString());
        }
        return results;
    }

    // ==================== rendering ====================

    private String buildAnnouncement(List<TeamTaskEntity> tasks, List<List<Integer>> stepDeps) {
        StringBuilder sb = new StringBuilder("已将计划分派到团队任务板并行执行：\n");
        for (int i = 0; i < tasks.size(); i++) {
            TeamTaskEntity task = tasks.get(i);
            sb.append("- #").append(task.getTaskNumber()).append(' ')
                    .append(task.getSubject())
                    .append("（").append(agentName(task.getAssigneeAgentId())).append("）");
            if (!stepDeps.get(i).isEmpty()) {
                sb.append(" — 前置：");
                for (Integer depIndex : stepDeps.get(i)) {
                    sb.append('#').append(tasks.get(depIndex).getTaskNumber()).append(' ');
                }
            }
            sb.append('\n');
        }
        sb.append("成员完成后我会汇总结果给你。");
        return sb.toString();
    }

    private String buildProgressText(List<TeamTaskEntity> tasks) {
        StringBuilder sb = new StringBuilder("计划仍在团队任务板上执行中：\n");
        for (TeamTaskEntity task : tasks) {
            sb.append("- #").append(task.getTaskNumber()).append(' ')
                    .append(task.getSubject())
                    .append("：").append(task.getStatus());
            if (task.getProgressPercent() != null
                    && TeamTaskStatus.IN_PROGRESS.equals(task.getStatus())) {
                sb.append("（").append(task.getProgressPercent()).append('%');
                if (task.getProgressStep() != null) {
                    sb.append(" — ").append(task.getProgressStep());
                }
                sb.append('）');
            }
            sb.append('\n');
        }
        sb.append("全部完成后我会汇总；如需调整可在团队任务板上操作。");
        return sb.toString();
    }

    // ==================== helpers ====================

    private static String subjectOf(String step) {
        String firstLine = step.strip().lines().findFirst().orElse(step.strip());
        return firstLine.length() <= SUBJECT_MAX_CHARS ? firstLine
                : firstLine.substring(0, SUBJECT_MAX_CHARS);
    }

    private static int stepIndexOf(TeamTaskEntity task) {
        try {
            return JSONUtil.parseObj(task.getMetadata()).getInt("stepIndex", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static Long parseAgentId(String agentId) {
        try {
            return Long.valueOf(agentId);
        } catch (Exception e) {
            return null;
        }
    }

    private String agentName(Long agentId) {
        AgentEntity agent = agentId == null ? null : agentMapper.selectById(agentId);
        return agent != null && agent.getName() != null ? agent.getName()
                : String.valueOf(agentId);
    }
}
