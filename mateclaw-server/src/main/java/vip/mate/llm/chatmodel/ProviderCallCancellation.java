package vip.mate.llm.chatmodel;

import okhttp3.Call;

import java.util.HashSet;
import java.util.Set;

/** Cancels only calls owned by one provider subscription before SDK reader cleanup. */
final class ProviderCallCancellation implements AutoCloseable {
    private final Set<Call> calls = new HashSet<>();
    private boolean cancelled;
    private boolean finished;

    synchronized void register(Call call) {
        if (cancelled || finished) call.cancel();
        else calls.add(call);
    }

    synchronized void cancel() {
        cancelled = true;
        // SDK reader close can wait for a blocked read; cancel its socket first.
        calls.forEach(Call::cancel);
    }

    @Override
    public synchronized void close() {
        finished = true;
        calls.clear();
    }
}
