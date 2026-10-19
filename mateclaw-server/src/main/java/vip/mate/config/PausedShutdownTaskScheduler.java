package vip.mate.config;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Future;

/** Cancels pending ticks and releases lifecycle-paused workers during graceful shutdown. */
final class PausedShutdownTaskScheduler extends ThreadPoolTaskScheduler {
    @Override
    protected void beforeExecute(Thread thread, Runnable task) {
        super.beforeExecute(thread, task);
        // A worker may have dequeued this future before the lifecycle pause.
        // Releasing that gate during shutdown must not execute the pending tick.
        if (getScheduledExecutor().isShutdown() && task instanceof Future<?> future) {
            future.cancel(false);
        }
    }

    @Override
    public void shutdown() {
        // Stop accepting work and cancel queued future ticks before waking workers.
        initiateShutdown();
        start();
        // Already-running tasks retain the configured graceful completion window.
        super.shutdown();
    }
}
