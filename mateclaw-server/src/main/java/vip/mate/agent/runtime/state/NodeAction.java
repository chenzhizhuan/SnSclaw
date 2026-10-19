package vip.mate.agent.runtime.state;

import java.util.Map;

@FunctionalInterface
public interface NodeAction {
    Map<String, Object> apply(RunState state) throws Exception;
}
