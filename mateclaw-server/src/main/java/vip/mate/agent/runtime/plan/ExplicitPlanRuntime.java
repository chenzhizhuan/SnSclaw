package vip.mate.agent.runtime.plan;

import static vip.mate.agent.graph.plan.state.PlanStateKeys.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.GOAL_EVALUATION_NODE;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import vip.mate.agent.runtime.AgentRuntime;
import vip.mate.agent.runtime.state.EdgeAction;
import vip.mate.agent.runtime.state.NodeAction;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.runtime.state.RunStep;
import vip.mate.agent.runtime.state.RuntimeExecutionScope;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Explicit plan phases. Individual step tool loops belong to the native agent runtime. */
public final class ExplicitPlanRuntime implements AgentRuntime {
    public static final String END = "end";

    public record Policies(
            NodeAction generation,
            EdgeAction afterGeneration,
            NodeAction step,
            EdgeAction afterStep,
            NodeAction summary,
            NodeAction directAnswer,
            EdgeAction afterAnswer,
            NodeAction goalEvaluation,
            EdgeAction afterGoal) {
        public Policies {
            Objects.requireNonNull(generation);
            Objects.requireNonNull(afterGeneration);
            Objects.requireNonNull(step);
            Objects.requireNonNull(afterStep);
            Objects.requireNonNull(summary);
            Objects.requireNonNull(directAnswer);
            Objects.requireNonNull(afterAnswer);
            Objects.requireNonNull(goalEvaluation);
            Objects.requireNonNull(afterGoal);
        }
    }

    private final Policies policies;
    private final int phaseCeiling;

    public ExplicitPlanRuntime(Policies policies, int phaseCeiling) {
        this.policies = Objects.requireNonNull(policies);
        if (phaseCeiling < 1)
            throw new IllegalArgumentException("A positive plan phase ceiling is required");
        this.phaseCeiling = phaseCeiling;
    }

    @Override
    public Flux<RunStep> stream(Map<String, Object> inputs) {
        return Flux.defer(
                        () ->
                                Flux.<RunStep, Session>generate(
                                        () -> new Session(new RunState(inputs)),
                                        (session, sink) -> {
                                            if (END.equals(session.next)) {
                                                sink.complete();
                                                return session;
                                            }
                                            try {
                                                if (Thread.currentThread().isInterrupted())
                                                    throw new CancellationException(
                                                            "Plan execution cancelled");
                                                if (session.phases >= phaseCeiling)
                                                    throw new IllegalStateException(
                                                            "Plan phase ceiling reached");
                                                String phase = session.next;
                                                session.phases++;
                                                NodeAction node =
                                                        switch (phase) {
                                                            case PLAN_GENERATION_NODE ->
                                                                    policies.generation();
                                                            case STEP_EXECUTION_NODE ->
                                                                    policies.step();
                                                            case PLAN_SUMMARY_NODE ->
                                                                    policies.summary();
                                                            case DIRECT_ANSWER_NODE ->
                                                                    policies.directAnswer();
                                                            case GOAL_EVALUATION_NODE ->
                                                                    policies.goalEvaluation();
                                                            default ->
                                                                    throw new IllegalStateException(
                                                                            "Unsupported plan"
                                                                                    + " phase: "
                                                                                    + phase);
                                                        };
                                                Map<String, Object> patch =
                                                        RuntimeExecutionScope.call(
                                                                session.state,
                                                                () -> node.apply(session.state));
                                                if (Thread.currentThread().isInterrupted())
                                                    throw new CancellationException(
                                                            "Plan execution cancelled");
                                                session.state.applyPatch(patch);
                                                EdgeAction edge =
                                                        switch (phase) {
                                                            case PLAN_GENERATION_NODE ->
                                                                    policies.afterGeneration();
                                                            case STEP_EXECUTION_NODE ->
                                                                    policies.afterStep();
                                                            case PLAN_SUMMARY_NODE,
                                                                            DIRECT_ANSWER_NODE ->
                                                                    policies.afterAnswer();
                                                            case GOAL_EVALUATION_NODE ->
                                                                    policies.afterGoal();
                                                            default ->
                                                                    throw new IllegalStateException(
                                                                            "Unsupported plan"
                                                                                    + " phase: "
                                                                                    + phase);
                                                        };
                                                session.next =
                                                        RuntimeExecutionScope.call(
                                                                session.state,
                                                                () -> edge.apply(session.state));
                                                sink.next(new RunStep(phase, session.state));
                                            } catch (Exception failure) {
                                                sink.error(failure);
                                            }
                                            return session;
                                        }))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static final class Session {
        private final RunState state;
        private String next = PLAN_GENERATION_NODE;
        private int phases;

        private Session(RunState state) {
            this.state = state;
        }
    }
}
