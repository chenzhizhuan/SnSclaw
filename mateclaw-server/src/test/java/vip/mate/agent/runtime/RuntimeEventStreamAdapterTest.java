package vip.mate.agent.runtime;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeEventStreamAdapterTest {
    @Test
    void preservesCallIdentityAndOrderForInterleavedCallsToTheSameTool() {
        var deltas = RuntimeEventStreamAdapter.adapt(Flux.just(
                RuntimeEvent.of("session", 0, RuntimeEventType.TOOL_STARTED, null,
                        Map.of("callId", "call-1", "toolName", "read_file")),
                RuntimeEvent.of("session", 1, RuntimeEventType.TOOL_STARTED, null,
                        Map.of("callId", "call-2", "toolName", "read_file")),
                RuntimeEvent.of("session", 2, RuntimeEventType.TOOL_FINISHED, null,
                        Map.of("callId", "call-2", "toolName", "read_file", "result", "second file")),
                RuntimeEvent.of("session", 3, RuntimeEventType.TOOL_FINISHED, null,
                        Map.of("callId", "call-1", "toolName", "read_file", "result", "first file"))))
                .collectList().block();

        assertEquals(List.of("tool_call_started", "tool_call_started",
                        "tool_call_completed", "tool_call_completed"),
                deltas.stream().map(AgentService.StreamDelta::eventType).toList());
        assertEquals(List.of("call-1", "call-2", "call-2", "call-1"),
                deltas.stream().map(delta -> delta.eventData().get("toolCallId")).toList());
        assertEquals(List.of(0L, 1L, 2L, 3L),
                deltas.stream().map(delta -> delta.eventData().get("runtimeSequence")).toList());
        assertEquals(List.of("read_file", "read_file", "read_file", "read_file"),
                deltas.stream().map(delta -> delta.eventData().get("toolName")).toList());
        assertEquals("second file", deltas.get(2).eventData().get("result"));
        assertEquals("first file", deltas.get(3).eventData().get("result"));
    }

    @Test
    void adaptsProviderFluxInOrder() {
        var deltas = RuntimeEventStreamAdapter.adapt(Flux.just(
                RuntimeEvent.of("session", 0, RuntimeEventType.ASSISTANT_DELTA,
                        null, Map.of("delta", "a")),
                RuntimeEvent.of("session", 1, RuntimeEventType.THINKING_DELTA,
                        null, Map.of("delta", "b")))).collectList().block();

        assertEquals(2, deltas.size());
        assertEquals("a", deltas.get(0).content());
        assertEquals("b", deltas.get(1).thinking());
    }
}
