package vip.mate.agent.runtime.state;

@FunctionalInterface
public interface EdgeAction {
    String apply(RunState state) throws Exception;
}
