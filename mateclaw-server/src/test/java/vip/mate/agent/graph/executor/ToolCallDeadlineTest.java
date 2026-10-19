package vip.mate.agent.graph.executor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import vip.mate.tool.ToolInputValidationException;

import java.lang.reflect.Field;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class ToolCallDeadlineTest {
    @Test
    void delayedWatchdogClassifiesLateValidationFailureAsTimeout() throws Exception {
        Field field = ToolCallDeadline.class.getDeclaredField("TIMER");
        field.setAccessible(true);
        var timer = (ScheduledThreadPoolExecutor) field.get(null);
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        timer.execute(
                () -> {
                    occupied.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
        assertTrue(occupied.await(2, TimeUnit.SECONDS));
        var original = new ToolInputValidationException("bad argument");
        try {
            var failure =
                    assertThrows(
                            TimeoutException.class,
                            () ->
                                    ToolCallDeadline.call(
                                            "late",
                                            20,
                                            () -> {
                                                try {
                                                    Thread.sleep(80);
                                                } catch (InterruptedException unexpected) {
                                                    throw new AssertionError(unexpected);
                                                }
                                                throw original;
                                            }));
            assertSame(original, failure.getCause());
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            release.countDown();
            assertTrue(done.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void timelyValidationAndFatalErrorsKeepTheirIdentity() {
        var validation = new ToolInputValidationException("invalid");
        assertSame(
                validation,
                assertThrows(
                        ToolInputValidationException.class,
                        () ->
                                ToolCallDeadline.call(
                                        "validation",
                                        1000,
                                        () -> {
                                            throw validation;
                                        })));
        var fatal = new AssertionError("fatal");
        assertSame(
                fatal,
                assertThrows(
                        AssertionError.class,
                        () ->
                                ToolCallDeadline.call(
                                        "fatal",
                                        1000,
                                        () -> {
                                            throw fatal;
                                        })));
    }

    @Test
    void callerCancellationKeepsInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    CancellationException.class,
                    () ->
                            ToolCallDeadline.callWithDeadline(
                                    "cancelled",
                                    1000,
                                    guard -> {
                                        guard.run();
                                        return "no";
                                    }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void timeoutInterruptsCallbackAndDoesNotPoisonNextCall() throws Exception {
        ThreadLocal<String> context = new ThreadLocal<>();
        context.set("conversation");
        try {
            assertThrows(
                    TimeoutException.class,
                    () ->
                            ToolCallDeadline.call(
                                    "extract_document_text",
                                    30,
                                    () -> {
                                        assertEquals("conversation", context.get());
                                        try {
                                            Thread.sleep(2_000);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return "partial result must not be treated as success";
                                    }));
            assertFalse(Thread.currentThread().isInterrupted());
            assertEquals("next", ToolCallDeadline.call("next", 1000, () -> "next"));
        } finally {
            context.remove();
        }
    }

    @Test
    void successfulCallbackCancelsItsWatchdog() throws Exception {
        assertEquals("ok", ToolCallDeadline.call("fast", 30, () -> "ok"));
        Thread.sleep(80);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void delayedWatchdogCannotAdvertiseLateCallbackAsSuccess() throws Exception {
        Field timerField = ToolCallDeadline.class.getDeclaredField("TIMER");
        timerField.setAccessible(true);
        var timer = (ScheduledThreadPoolExecutor) timerField.get(null);
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        timer.execute(
                () -> {
                    blocked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        try {
            assertThrows(
                    TimeoutException.class,
                    () ->
                            ToolCallDeadline.call(
                                    "late",
                                    20,
                                    () -> {
                                        try {
                                            Thread.sleep(80);
                                        } catch (InterruptedException failure) {
                                            throw new AssertionError(
                                                    "Blocked watchdog must not interrupt callback",
                                                    failure);
                                        }
                                        return "late success";
                                    }));
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        }
        assertEquals("next", ToolCallDeadline.call("next", 1000, () -> "next"));
    }
}
