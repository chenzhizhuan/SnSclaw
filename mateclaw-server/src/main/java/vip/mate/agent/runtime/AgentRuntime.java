package vip.mate.agent.runtime;

import reactor.core.publisher.Flux;

import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.runtime.state.RunStep;

import java.util.Map;
import java.util.Optional;

/** Application execution boundary with detached state for every subscription. */
@FunctionalInterface
public interface AgentRuntime {
    Flux<RunStep> stream(Map<String, Object> inputs);

    default Optional<RunState> invoke(Map<String, Object> inputs) {
        RunStep last = stream(inputs).blockLast();
        return Optional.ofNullable(last).map(RunStep::state);
    }
}
