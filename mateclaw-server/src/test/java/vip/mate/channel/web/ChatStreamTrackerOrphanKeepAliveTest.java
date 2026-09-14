package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "长任务保活"策略（{@code mateclaw.webchat.orphan-reclaim=false}）。
 *
 * <p>背景：上游 #587 的孤儿回收在无人订阅时直接 dispose 运行；对
 * {@code runtime_type=dsh} 就是 {@code destroyForcibly()} 掉 DSH 子进程 ——
 * 浏览器关标签 / 网络中断会让十几分钟的任务半途结束（对话"提前结束"）。
 * 保活策略下运行照常完成、原请求照常把完整回复落库，只由
 * {@code mateclaw.webchat.orphan-run-cap-minutes} 兜底。
 *
 * <p>默认策略（{@code reclaim=true}）的行为由
 * {@link ChatStreamTrackerOrphanPolicyTest} 固定，本类不重复。
 */
class ChatStreamTrackerOrphanKeepAliveTest {

    private static final class RecordingDisposable implements Disposable {
        private final AtomicBoolean disposed = new AtomicBoolean();

        @Override
        public void dispose() {
            disposed.set(true);
        }

        @Override
        public boolean isDisposed() {
            return disposed.get();
        }
    }

    private ChatStreamTracker keepAliveTracker() {
        ChatStreamTracker tracker = new ChatStreamTracker(new ObjectMapper());
        tracker.setIdleTimeoutMinutesForTesting(30);    // keep the idle bucket out of the way
        tracker.setOrphanGraceSecondsForTesting(2);     // tight grace for unit-test speed
        tracker.setOrphanReclaimEnabledForTesting(false);
        tracker.setOrphanRunCapMinutesForTesting(60);   // far away — the run must be kept
        return tracker;
    }

    @Test
    @DisplayName("keep-alive: an orphaned run past the grace keeps running")
    void orphanRunKeptAlive() {
        ChatStreamTracker tracker = keepAliveTracker();
        String cid = "keep-alive";
        ChatStreamTracker.RunHandle handle = tracker.register(cid);
        tracker.incrementFlux(cid);

        RecordingDisposable disposable = new RecordingDisposable();
        tracker.setDisposable(handle, disposable);
        AtomicInteger saves = new AtomicInteger();
        tracker.setEmergencySaveCallback(cid, saves::incrementAndGet);

        // Last subscriber left 3s ago (> 2s grace) — exactly the case #587 reclaims.
        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L);
        tracker.cleanupStaleRuns();

        assertTrue(tracker.hasRunStateForTesting(cid), "kept run must stay in the map");
        assertTrue(tracker.isRunning(cid), "kept run must still be running");
        assertFalse(disposable.isDisposed(), "keep-alive must not cancel the run");
        assertEquals(0, saves.get(), "no emergency save — the run was never evicted");

        // A returning viewer (page refresh → reconnectStream) can still attach and
        // resume the live stream instead of finding a corpse.
        assertTrue(tracker.attach(handle, new SseEmitter()),
                "a kept run must still accept a re-attaching subscriber");
    }

    @Test
    @DisplayName("keep-alive: repeated sweeps do not reclaim the kept run")
    void keptRunSurvivesRepeatedSweeps() {
        ChatStreamTracker tracker = keepAliveTracker();
        String cid = "keep-alive-repeat";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L);
        for (int i = 0; i < 5; i++) {
            tracker.cleanupStaleRuns();
        }

        assertTrue(tracker.hasRunStateForTesting(cid));
        assertTrue(tracker.isRunning(cid));
    }

    @Test
    @DisplayName("keep-alive: the wall-clock run cap still reclaims a wedged run")
    void orphanRunCapReclaims() {
        ChatStreamTracker tracker = keepAliveTracker();
        // cap 0 → any run age exceeds it, and the run is fresh, so only the cap can fire.
        tracker.setOrphanRunCapMinutesForTesting(0);
        String cid = "keep-alive-cap";
        ChatStreamTracker.RunHandle handle = tracker.register(cid);
        tracker.incrementFlux(cid);

        RecordingDisposable disposable = new RecordingDisposable();
        tracker.setDisposable(handle, disposable);
        AtomicInteger saves = new AtomicInteger();
        tracker.setEmergencySaveCallback(cid, saves::incrementAndGet);

        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L);
        tracker.cleanupStaleRuns();

        assertFalse(tracker.hasRunStateForTesting(cid), "the run cap must still reclaim");
        assertTrue(disposable.isDisposed(), "cap reclaim disposes the run");
        assertEquals(1, saves.get(), "cap reclaim flushes the partial answer first");
    }

    @Test
    @DisplayName("keep-alive: a run inside the grace is untouched (same as #587)")
    void runInsideGraceUntouched() {
        ChatStreamTracker tracker = keepAliveTracker();
        String cid = "keep-alive-fresh";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 500L);
        tracker.cleanupStaleRuns();

        assertTrue(tracker.hasRunStateForTesting(cid));
        assertTrue(tracker.isRunning(cid));
    }

    @Test
    @DisplayName("keep-alive: an orphaned run that also goes silent is reclaimed by the idle rule")
    void orphanedAndSilentStillReclaimed() {
        ChatStreamTracker tracker = keepAliveTracker();
        String cid = "keep-alive-orphan-idle";
        ChatStreamTracker.RunHandle handle = tracker.register(cid);
        tracker.incrementFlux(cid);

        RecordingDisposable disposable = new RecordingDisposable();
        tracker.setDisposable(handle, disposable);

        // Nobody watching AND no events at all: wedged, not merely unwatched.
        // The keep-alive branch must not swallow the idle backstop.
        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L);
        tracker.backdateLastEventForTesting(cid, System.currentTimeMillis() - 31 * 60_000L);
        tracker.cleanupStaleRuns();

        assertFalse(tracker.hasRunStateForTesting(cid),
                "an orphaned run that also stopped producing events must still be reclaimed");
        assertTrue(disposable.isDisposed());
    }

    @Test
    @DisplayName("keep-alive: the idle sweep still reclaims a silent run (backstop kept)")
    void idleSweepStillReclaims() {
        ChatStreamTracker tracker = keepAliveTracker();
        String cid = "keep-alive-idle";
        ChatStreamTracker.RunHandle handle = tracker.register(cid);
        tracker.incrementFlux(cid);

        RecordingDisposable disposable = new RecordingDisposable();
        tracker.setDisposable(handle, disposable);

        // No events at all for longer than the idle threshold: the run is wedged,
        // not merely unwatched — the backstop must still fire.
        tracker.backdateLastEventForTesting(cid, System.currentTimeMillis() - 31 * 60_000L);
        tracker.cleanupStaleRuns();

        assertFalse(tracker.hasRunStateForTesting(cid));
        assertTrue(disposable.isDisposed());
    }

    @Test
    @DisplayName("default policy stays upstream: reclaim=true still evicts the orphan")
    void defaultPolicyStillReclaims() {
        ChatStreamTracker tracker = new ChatStreamTracker(new ObjectMapper());
        tracker.setIdleTimeoutMinutesForTesting(30);
        tracker.setOrphanGraceSecondsForTesting(2);
        // orphanReclaimEnabled keeps its default (true).

        String cid = "default-reclaim";
        tracker.register(cid);
        tracker.incrementFlux(cid);
        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L);
        tracker.cleanupStaleRuns();

        assertFalse(tracker.hasRunStateForTesting(cid),
                "the upstream #587 policy must remain the default");
    }
}
