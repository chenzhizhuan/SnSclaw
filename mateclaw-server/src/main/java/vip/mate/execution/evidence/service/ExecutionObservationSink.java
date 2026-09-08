package vip.mate.execution.evidence.service;

import org.springframework.ai.chat.model.ToolContext;
import vip.mate.execution.evidence.model.AttemptState;
import vip.mate.execution.evidence.model.EvidenceObservation;
import vip.mate.execution.evidence.model.EvidenceKind;
import vip.mate.execution.evidence.model.EvidenceResult;
import vip.mate.execution.evidence.model.SourceLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Typed, invocation-local channel for trusted implementations to report observations. */
public final class ExecutionObservationSink {
    private static final String KEY = "mateclaw.executionObservationSink";
    private final boolean metadataOnly;
    private final int maxObservations;
    private final List<EvidenceObservation> observations = new ArrayList<>();
    private AttemptState state = AttemptState.SUCCEEDED;
    private boolean sealed;

    public ExecutionObservationSink(boolean metadataOnly) { this(metadataOnly, 32); }

    public ExecutionObservationSink(boolean metadataOnly, int maxObservations) {
        this.metadataOnly = metadataOnly;
        this.maxObservations = Math.clamp(maxObservations, 1, 99);
    }

    public ToolContext attach(ToolContext context) {
        var values = new HashMap<String, Object>(context.getContext());
        values.put(KEY, this);
        return new ToolContext(values);
    }

    public static ExecutionObservationSink from(ToolContext context) {
        if (context == null) return null;
        Object sink = context.getContext().get(KEY);
        return sink instanceof ExecutionObservationSink typed ? typed : null;
    }

    public boolean metadataOnly() { return metadataOnly; }
    public synchronized AttemptState state() { return state; }
    public synchronized List<EvidenceObservation> observations() { return List.copyOf(observations); }
    public synchronized void seal() { sealed = true; }

    /** Called by the process adapter, never by parsing a tool's returned text. */
    public void command(Integer exitCode, boolean timedOut, boolean cancelled, boolean blocked) {
        command(exitCode, timedOut, cancelled, blocked, null);
    }

    public synchronized void command(Integer exitCode, boolean timedOut, boolean cancelled, boolean blocked, String workingDirectory) {
        if (sealed) return;
        state = cancelled ? AttemptState.CANCELLED : timedOut || exitCode == null ? AttemptState.UNKNOWN
                : blocked ? AttemptState.BLOCKED : exitCode == 0 ? AttemptState.SUCCEEDED : AttemptState.FAILED;
        EvidenceResult result = state == AttemptState.SUCCEEDED ? EvidenceResult.OBSERVED
                : state == AttemptState.UNKNOWN || state == AttemptState.CANCELLED
                        ? EvidenceResult.UNKNOWN : EvidenceResult.FAIL;
        append(new EvidenceObservation("command", EvidenceKind.COMMAND_EXIT, result,
                SourceLevel.PLATFORM_OBSERVED, null, null, null, null, null, workingDirectory,
                null, null, "exit=" + exitCode + "; timedOut=" + timedOut
                        + "; cancelled=" + cancelled + "; blocked=" + blocked, null, null, null));
    }

    /** Called only after file bytes and owner metadata have survived durable read-back. */
    public synchronized void artifact(String id, String digest, long length, String mimeType, Instant expiresAt) {
        append(new EvidenceObservation("artifact:" + id, EvidenceKind.ARTIFACT_SNAPSHOT,
                EvidenceResult.OBSERVED, SourceLevel.PLATFORM_OBSERVED, null, null, null, null, null,
                null, id, digest, "bytes=" + length + "; mime=" + mimeType, null, null, expiresAt));
    }

    private void append(EvidenceObservation observation) {
        if (!sealed && !metadataOnly && observations.size() < maxObservations
                && observations.stream().noneMatch(e -> e.sourceKey().equals(observation.sourceKey()))) {
            observations.add(observation);
        }
    }
}
