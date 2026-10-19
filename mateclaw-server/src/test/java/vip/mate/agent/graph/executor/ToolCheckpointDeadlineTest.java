package vip.mate.agent.graph.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import vip.mate.config.ToolTimeoutProperties;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class ToolCheckpointDeadlineTest {
    @Test
    void expiredCheckpointCannotEnterOrdinaryCallback() {
        assertExpiredCheckpointCannotEnter(false);
    }

    @Test
    void expiredCheckpointCannotEnterApprovedCallback() {
        assertExpiredCheckpointCannotEnter(true);
    }

    private void assertExpiredCheckpointCannotEnter(boolean approved) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_external")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        AtomicInteger effects = new AtomicInteger();
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            effects.incrementAndGet();
                            return "effect";
                        });
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        var timeouts = new ToolTimeoutProperties();
        timeouts.setDefaultTimeoutSeconds(1);
        ReflectionTestUtils.setField(executor, "toolTimeoutProperties", timeouts);
        AtomicInteger checks = new AtomicInteger();
        executor.setExecutionCheckpoint(
                (origin, key, id) -> {
                    // Ordinary execution checkpoints the batch before the timed callback.
                    if (!approved && checks.incrementAndGet() == 1) return;
                    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1300);
                    while (System.nanoTime() < until) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ignored) {
                        }
                    }
                });
        var call = new AssistantMessage.ToolCall("one", "function", "write_external", "{}");
        if (approved) executor.executePreApproved(call, "{}", new ArrayList<>(), "conv", null);
        else executor.execute(List.of(call), "conv", "2", false, "alice", null, null, Set.of());
        assertEquals(
                0,
                effects.get(),
                "Expired checkpoint must not enter callback; approved=" + approved);
        assertFalse(
                Thread.currentThread().isInterrupted(), "Deadline must clean its timer interrupt");
    }
}
