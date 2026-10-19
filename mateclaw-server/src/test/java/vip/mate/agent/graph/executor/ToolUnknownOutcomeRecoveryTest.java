package vip.mate.agent.graph.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.test.util.ReflectionTestUtils;

import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.config.ToolTimeoutProperties;
import vip.mate.tool.ToolInputValidationException;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class ToolUnknownOutcomeRecoveryTest {
    @Test
    void delayedWatchdogOrdinaryStillMarksUnknown() throws Exception {
        assertDelayed(true);
    }

    @Test
    void delayedWatchdogApprovedStillMarksUnknown() throws Exception {
        assertDelayed(false);
    }

    private void assertDelayed(boolean ordinary) throws Exception {
        var field = ToolCallDeadline.class.getDeclaredField("TIMER");
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
        try {
            assertValidationOutcome(!ordinary, true);
        } finally {
            release.countDown();
            assertTrue(done.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void fatalCallbackIsNotReplacedByFailedUnknownCheckpoint() {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_external")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        var fatal = new AssertionError("fatal callback");
        when(callback.call(anyString(), any(ToolContext.class))).thenThrow(fatal);
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        var rejected = new ToolExecutionCheckpointRejectedException("expired owner");
        executor.setExecutionCheckpoint(
                new ToolExecutionCheckpoint() {
                    public void beforeInvocation(ChatOrigin origin, String key, String id) {}

                    public void markUnknownOutcome(ChatOrigin origin, Throwable cause) {
                        throw rejected;
                    }
                });
        var call = new AssistantMessage.ToolCall("one", "function", "write_external", "{}");
        assertSame(
                fatal,
                assertThrows(
                        AssertionError.class,
                        () ->
                                executor.execute(
                                        List.of(call),
                                        "conv",
                                        "2",
                                        false,
                                        "alice",
                                        null,
                                        null,
                                        Set.of())));
        assertSame(rejected, fatal.getSuppressed()[0]);
    }

    @Test
    void timedOutValidationFailureOrdinaryPublishesTimeout() {
        assertValidationOutcome(false, true);
    }

    @Test
    void timedOutValidationFailureApprovedPublishesTimeout() {
        assertValidationOutcome(true, true);
    }

    @Test
    void earlyValidationOrdinaryRemainsReportedValidation() {
        assertValidationOutcome(false, false);
    }

    @Test
    void earlyValidationApprovedRemainsReportedValidation() {
        assertValidationOutcome(true, false);
    }

    private void assertValidationOutcome(boolean approved, boolean late) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_external")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            if (late) {
                                long until =
                                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1300);
                                while (System.nanoTime() < until) {
                                    try {
                                        Thread.sleep(20);
                                    } catch (InterruptedException ignored) {
                                    }
                                }
                            }
                            throw new ToolInputValidationException("invalid after slow validation");
                        });
        var unknown = new AtomicInteger();
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        var timeout = new ToolTimeoutProperties();
        timeout.setDefaultTimeoutSeconds(1);
        ReflectionTestUtils.setField(executor, "toolTimeoutProperties", timeout);
        executor.setExecutionCheckpoint(
                new ToolExecutionCheckpoint() {
                    public void beforeInvocation(ChatOrigin origin, String key, String id) {}

                    public void markUnknownOutcome(ChatOrigin origin, Throwable cause) {
                        unknown.incrementAndGet();
                    }
                });
        var call = new AssistantMessage.ToolCall("one", "function", "write_external", "{}");
        var events = new ArrayList<GraphEventPublisher.GraphEvent>();
        String response;
        if (approved) {
            response = executor.executePreApproved(call, "{}", events, "conv", null).responseData();
        } else {
            var result =
                    executor.execute(
                            List.of(call), "conv", "2", false, "alice", null, null, Set.of());
            response = result.responses().getFirst().responseData();
            events.addAll(result.events());
        }
        assertEquals(late ? 1 : 0, unknown.get());
        if (late) {
            assertTrue(response.contains("timed out"), response);
            assertFalse(response.contains("Tool input validation failed"), response);
        } else {
            assertEquals("Tool input validation failed: invalid after slow validation", response);
        }
        var completion =
                events.stream()
                        .filter(event -> "tool_call_completed".equals(event.type()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(false, completion.data().get("success"));
        assertEquals(response, completion.data().get("result"));
    }
}
