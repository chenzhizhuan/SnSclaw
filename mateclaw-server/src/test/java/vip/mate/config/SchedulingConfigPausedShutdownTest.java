package vip.mate.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class SchedulingConfigPausedShutdownTest {
    @Test
    void pausedWorkersAndPendingTicksDoNotBlockAlreadyRunningWork() throws Exception {
        var scheduler = (ThreadPoolTaskScheduler) new SchedulingConfig().taskScheduler();
        scheduler.initialize();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new AtomicBoolean();
        var interrupted = new AtomicBoolean();
        var readyRan = new AtomicBoolean();
        var futureRan = new AtomicBoolean();
        try (var closer = Executors.newSingleThreadExecutor()) {
            try {
                scheduler.schedule(
                        () -> {
                            started.countDown();
                            try {
                                release.await();
                                completed.set(true);
                            } catch (InterruptedException exception) {
                                interrupted.set(true);
                                Thread.currentThread().interrupt();
                            }
                        },
                        Instant.now());
                assertTrue(started.await(2, TimeUnit.SECONDS));
                scheduler.stop();
                var ready = scheduler.schedule(() -> readyRan.set(true), Instant.now());
                var future =
                        scheduler.schedule(
                                () -> futureRan.set(true), Instant.now().plusSeconds(3600));
                awaitActiveWorkers(scheduler, 2);
                var shutdown = closer.submit(scheduler::destroy);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!scheduler.getScheduledExecutor().isShutdown()
                        && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertTrue(scheduler.getScheduledExecutor().isShutdown());
                assertFalse(
                        shutdown.isDone(),
                        "active work must retain its graceful completion window");
                assertFalse(interrupted.get());
                release.countDown();
                shutdown.get(2, TimeUnit.SECONDS);
                assertTrue(completed.get());
                assertFalse(interrupted.get());
                assertTrue(ready.isCancelled());
                assertFalse(readyRan.get());
                assertTrue(future.isCancelled());
                assertFalse(futureRan.get());
                assertTrue(scheduler.getScheduledExecutor().isTerminated());
            } finally {
                release.countDown();
                scheduler.getScheduledExecutor().shutdownNow();
            }
        }
    }

    @Test
    void pausedApplicationContextClosesWithoutRunningPendingTicks() throws Exception {
        var context = new AnnotationConfigApplicationContext(SchedulingConfig.class);
        var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
        var ran = new AtomicBoolean();
        try (var closer = Executors.newSingleThreadExecutor()) {
            try {
                context.pause();
                var pending = scheduler.schedule(() -> ran.set(true), Instant.now());
                awaitActiveWorkers(scheduler, 1);
                closer.submit(context::close).get(2, TimeUnit.SECONDS);
                assertTrue(pending.isCancelled());
                assertFalse(ran.get());
                assertTrue(scheduler.getScheduledExecutor().isTerminated());
            } finally {
                scheduler.getScheduledExecutor().shutdownNow();
                context.close();
            }
        }
    }

    private static void awaitActiveWorkers(ThreadPoolTaskScheduler scheduler, int expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            // Keep the successful observation: a second sample can race a worker transition.
            if (scheduler.getScheduledThreadPoolExecutor().getActiveCount() >= expected) return;
            Thread.sleep(5);
        }
        fail("pending tick must reach the lifecycle pause gate");
    }
}
