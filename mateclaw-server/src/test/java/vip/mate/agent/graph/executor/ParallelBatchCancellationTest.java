package vip.mate.agent.graph.executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.test.util.ReflectionTestUtils;

import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.config.ToolTimeoutProperties;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ParallelBatchCancellationTest {
    @Test
    void firstFatalInterruptsEnteredCooperativeSibling() throws Exception {
        cooperativeSibling(true);
    }

    @Test
    void laterFatalInterruptsEnteredCooperativeSibling() throws Exception {
        cooperativeSibling(false);
    }

    private void cooperativeSibling(boolean failureFirst) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var effects = new AtomicInteger();
        var unknown = new AtomicInteger();
        var fatal = new AssertionError("fatal other callback");
        var callback = callback();
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            if (invocation.getArgument(0, String.class).contains("failure")) {
                                assertTrue(entered.await(3, TimeUnit.SECONDS));
                                throw fatal;
                            }
                            worker.set(Thread.currentThread());
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException cancelled) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                                throw new CancellationException("cooperative callback stopped");
                            }
                            effects.incrementAndGet();
                            return "late effect";
                        });
        var executor = executor(callback);
        executor.setExecutionCheckpoint(
                new ToolExecutionCheckpoint() {
                    public void beforeInvocation(ChatOrigin origin, String key, String id) {}

                    public void markUnknownOutcome(ChatOrigin origin, Throwable failure) {
                        unknown.incrementAndGet();
                    }
                });
        var failure = new AtomicReference<Throwable>();
        var collector =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        executor.execute(
                                                calls(failureFirst),
                                                "conv",
                                                "2",
                                                false,
                                                "alice",
                                                null,
                                                null,
                                                Set.of());
                                    } catch (Throwable error) {
                                        failure.set(error);
                                    }
                                });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(
                    interrupted.await(1, TimeUnit.SECONDS),
                    "Entered cooperative worker must be interrupted by batch abort");
        } finally {
            release.countDown();
            if (worker.get() != null) worker.get().join(2000);
            collector.join(2000);
        }
        assertSame(fatal, failure.get());
        assertEquals(0, effects.get());
        assertEquals(2, unknown.get());
    }

    @Test
    void ordinaryDeadlineErrorDoesNotInterruptIndependentSibling() throws Exception {
        var timed = callback();
        var sibling = callback();
        when(sibling.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("long_probe")
                                .description("Probe")
                                .inputSchema("{}")
                                .build());
        var effects = new AtomicInteger();
        var siblingInterrupted = new AtomicInteger();
        when(timed.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException timeout) {
                                Thread.currentThread().interrupt();
                                throw new CancellationException("deadline");
                            }
                            return "late";
                        });
        when(sibling.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            try {
                                Thread.sleep(1400);
                            } catch (InterruptedException cancelled) {
                                siblingInterrupted.incrementAndGet();
                                throw cancelled;
                            }
                            effects.incrementAndGet();
                            return "independent result";
                        });
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(timed, sibling)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        var timeouts = new ToolTimeoutProperties();
        timeouts.setDefaultTimeoutSeconds(1);
        timeouts.getPerTool().put("long_probe", 3);
        ReflectionTestUtils.setField(executor, "toolTimeoutProperties", timeouts);
        var result =
                executor.execute(
                        List.of(
                                new AssistantMessage.ToolCall(
                                        "timed", "function", "safe_probe", "{}"),
                                new AssistantMessage.ToolCall(
                                        "sibling", "function", "long_probe", "{}")),
                        "conv",
                        "2",
                        false,
                        "alice",
                        null,
                        null,
                        Set.of());
        assertEquals(1, effects.get());
        assertEquals(0, siblingInterrupted.get());
        assertEquals(2, result.responses().size());
    }

    private ToolCallback callback() {
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("safe_probe")
                                .description("Probe")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        return callback;
    }

    private ToolExecutionExecutor executor(ToolCallback callback) {
        return new ToolExecutionExecutor(
                AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                (name, args) -> ToolGuardResult.allow(),
                null,
                null);
    }

    private List<AssistantMessage.ToolCall> calls(boolean failureFirst) {
        var failure =
                new AssistantMessage.ToolCall(
                        "failure", "function", "safe_probe", "{\"failure\":true}");
        var sibling = new AssistantMessage.ToolCall("sibling", "function", "safe_probe", "{}");
        return failureFirst ? List.of(failure, sibling) : List.of(sibling, failure);
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void firstFatalCancelsPendingSibling() throws Exception {
        pendingSibling(true, false, false);
    }

    @Test
    void laterFatalCancelsPendingSibling() throws Exception {
        pendingSibling(false, false, false);
    }

    @Test
    void firstFenceRejectionCancelsPendingSibling() throws Exception {
        pendingSibling(true, true, false);
    }

    @Test
    void laterFenceRejectionCancelsPendingSibling() throws Exception {
        pendingSibling(false, true, false);
    }

    @Test
    void laterWrappedFenceRejectionCancelsPendingSibling() throws Exception {
        pendingSibling(false, true, true);
    }

    private void pendingSibling(boolean failureFirst, boolean rejected, boolean wrapped)
            throws Exception {
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sibling = new AtomicReference<Thread>();
        var effects = new AtomicInteger();
        var callback = callback();
        var fatal = new AssertionError("fatal first tool");
        var rejection = new ToolExecutionCheckpointRejectedException("approval fence rejected");
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            if (invocation.getArgument(0, String.class).contains("failure")) {
                                await(blocked);
                                if (rejected) {
                                    if (wrapped)
                                        throw new IllegalStateException("wrapped fence", rejection);
                                    throw rejection;
                                }
                                throw fatal;
                            }
                            effects.incrementAndGet();
                            return "external effect after batch failed";
                        });
        var executor = executor(callback);
        executor.setExecutionCheckpoint(
                (origin, key, id) -> {
                    if (!Thread.currentThread().isVirtual() || !"sibling".equals(id)) return;
                    sibling.set(Thread.currentThread());
                    blocked.countDown();
                    if (failureFirst) await(release);
                    else {
                        // The later worker must publish its abort before ordered collection reaches
                        // it.
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        try {
            if (rejected)
                assertSame(
                        rejection,
                        assertThrows(
                                ToolExecutionCheckpointRejectedException.class,
                                () ->
                                        executor.execute(
                                                calls(failureFirst),
                                                "conv",
                                                "2",
                                                false,
                                                "alice",
                                                null,
                                                null,
                                                Set.of())));
            else
                assertSame(
                        fatal,
                        assertThrows(
                                AssertionError.class,
                                () ->
                                        executor.execute(
                                                calls(failureFirst),
                                                "conv",
                                                "2",
                                                false,
                                                "alice",
                                                null,
                                                null,
                                                Set.of())));
        } finally {
            release.countDown();
        }
        sibling.get().join(2000);
        assertFalse(sibling.get().isAlive());
        assertEquals(0, effects.get(), "Aborted pending sibling must not enter callback");
    }

    @Test
    void interruptedCollectorCancelsPendingCallbacks() throws Exception {
        var blocked = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var workers = new CopyOnWriteArrayList<Thread>();
        var effects = new AtomicInteger();
        var callback = callback();
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            effects.incrementAndGet();
                            return "effect";
                        });
        var executor = executor(callback);
        executor.setExecutionCheckpoint(
                (origin, key, id) -> {
                    if (!Thread.currentThread().isVirtual()) return;
                    workers.add(Thread.currentThread());
                    blocked.countDown();
                    await(release);
                });
        var failure = new AtomicReference<Throwable>();
        var collector =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        executor.execute(
                                                calls(true),
                                                "conv",
                                                "2",
                                                false,
                                                "alice",
                                                null,
                                                null,
                                                Set.of());
                                    } catch (Throwable error) {
                                        failure.set(error);
                                    }
                                });
        try {
            assertTrue(blocked.await(3, TimeUnit.SECONDS));
            collector.interrupt();
            collector.join(2000);
        } finally {
            release.countDown();
        }
        assertFalse(collector.isAlive());
        assertInstanceOf(CancellationException.class, failure.get());
        for (var worker : workers) worker.join(2000);
        assertEquals(0, effects.get());
    }

    @Test
    void enteredSiblingRetainsUnknownOutcomeAfterFatalBatchExit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sibling = new AtomicReference<Thread>();
        var unknown = new AtomicInteger();
        var effects = new AtomicInteger();
        var callback = callback();
        var fatal = new AssertionError("fatal other worker");
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            if (invocation.getArgument(0, String.class).contains("failure")) {
                                await(entered);
                                throw fatal;
                            }
                            sibling.set(Thread.currentThread());
                            effects.incrementAndGet();
                            entered.countDown();
                            await(release);
                            return "effect completed";
                        });
        var executor = executor(callback);
        executor.setExecutionCheckpoint(
                new ToolExecutionCheckpoint() {
                    public void beforeInvocation(ChatOrigin origin, String key, String id) {}

                    public void markUnknownOutcome(ChatOrigin origin, Throwable failure) {
                        unknown.incrementAndGet();
                    }
                });
        try {
            assertSame(
                    fatal,
                    assertThrows(
                            AssertionError.class,
                            () ->
                                    executor.execute(
                                            calls(true),
                                            "conv",
                                            "2",
                                            false,
                                            "alice",
                                            null,
                                            null,
                                            Set.of())));
        } finally {
            release.countDown();
        }
        sibling.get().join(2000);
        assertEquals(1, effects.get());
        assertEquals(
                2,
                unknown.get(),
                "Fatal worker and entered sibling must both retain uncertain outcomes");
    }

    @Test
    void fatalPublishesAbortBeforeSlowUnknownCheckpoint() throws Exception {
        var marking = new CountDownLatch(1);
        var releaseMark = new CountDownLatch(1);
        var siblingFinished = new CountDownLatch(1);
        var siblingCheckpointEntered = new CountDownLatch(1);
        var sibling = new AtomicReference<Thread>();
        var effects = new AtomicInteger();
        var callback = callback();
        var fatal = new AssertionError("fatal callback");
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            if (invocation.getArgument(0, String.class).contains("failure")) {
                                await(siblingCheckpointEntered);
                                throw fatal;
                            }
                            effects.incrementAndGet();
                            return "effect";
                        });
        var executor = executor(callback);
        executor.setExecutionCheckpoint(
                new ToolExecutionCheckpoint() {
                    public void beforeInvocation(ChatOrigin origin, String key, String id) {
                        if ("sibling".equals(id)) {
                            sibling.set(Thread.currentThread());
                            siblingCheckpointEntered.countDown();
                            await(marking);
                            siblingFinished.countDown();
                        }
                    }

                    public void markUnknownOutcome(ChatOrigin origin, Throwable failure) {
                        marking.countDown();
                        await(releaseMark);
                    }
                });
        var failure = new AtomicReference<Throwable>();
        var collector =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        executor.execute(
                                                calls(true),
                                                "conv",
                                                "2",
                                                false,
                                                "alice",
                                                null,
                                                null,
                                                Set.of());
                                    } catch (Throwable error) {
                                        failure.set(error);
                                    }
                                });
        try {
            assertTrue(siblingFinished.await(3, TimeUnit.SECONDS));
            sibling.get().join(2000);
            assertFalse(sibling.get().isAlive());
        } finally {
            releaseMark.countDown();
        }
        collector.join(2000);
        assertSame(fatal, failure.get());
        assertEquals(
                0,
                effects.get(),
                "Fatal callback abort must precede its durable unknown-outcome write");
    }
}
