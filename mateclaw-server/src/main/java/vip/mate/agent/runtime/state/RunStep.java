package vip.mate.agent.runtime.state;

import java.util.Objects;

/** Internal retained snapshot, distinct from product SSE and stored conversation formats. */
public record RunStep(int snapshotVersion, String node, RunState state) {
    public static final int CURRENT_SNAPSHOT_VERSION = 1;

    public RunStep(String node, RunState state) {
        this(CURRENT_SNAPSHOT_VERSION, node, state);
    }

    public RunStep {
        if (snapshotVersion != CURRENT_SNAPSHOT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported runtime snapshot version: " + snapshotVersion);
        }
        Objects.requireNonNull(node);
        state = Objects.requireNonNull(state).snapshot();
    }

    @Override
    public RunState state() {
        return state.snapshot();
    }
}
