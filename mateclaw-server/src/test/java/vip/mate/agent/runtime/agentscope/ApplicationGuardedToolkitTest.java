package vip.mate.agent.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ApplicationGuardedToolkitTest {
    private static final List<ToolSchema> SCHEMAS =
            List.of(schema("first"), schema("second"), schema("third"));

    private static ToolSchema schema(String name) {
        return ToolSchema.builder()
                .name(name)
                .description("Schema-only tool")
                .parameters(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("value", Map.of("type", "integer")),
                                "required",
                                List.of("value")))
                .build();
    }

    private static ToolUseBlock call(String id, String name, int value) {
        return new ToolUseBlock(
                id, name, Map.of("value", value), "{\"value\":" + value + "}", null);
    }

    private static ToolResultBlock result(
            ToolUseBlock call, ToolResultState state, Map<String, Object> metadata) {
        return new ToolResultBlock(
                call.getId(),
                call.getName(),
                List.of(TextBlock.builder().text("result-" + call.getId()).build()),
                metadata,
                state);
    }

    private static Model model(
            List<ToolUseBlock> calls,
            AtomicInteger rounds,
            AtomicReference<List<ToolResultBlock>> history) {
        return new Model() {
            @Override
            public String getModelName() {
                return "deterministic";
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> schemas, GenerateOptions options) {
                int round = rounds.incrementAndGet();
                assertEquals(3, schemas.size());
                if (round == 1)
                    return Flux.just(
                            ChatResponse.builder()
                                    .content(List.copyOf(calls))
                                    .finishReason("tool_calls")
                                    .build());
                assertEquals(2, round, "No duplicate model rounds");
                history.set(
                        messages.stream()
                                .flatMap(
                                        message ->
                                                message
                                                        .getContentBlocks(ToolResultBlock.class)
                                                        .stream())
                                .toList());
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("final-answer").build()))
                                .finishReason("stop")
                                .build());
            }
        };
    }

    private static ReActAgent agent(ApplicationGuardedToolkit toolkit, Model model) {
        return ReActAgent.builder()
                .name("guarded-probe")
                .model(model)
                .toolkit(toolkit)
                .middleware(toolkit.boundaryMiddleware())
                .maxIters(4)
                .maxRetries(3)
                .enableMetaTool(false)
                .enablePendingToolRecovery(false)
                .build();
    }

    @Test
    void nestedSchemasRemainAnImmutableSnapshotAcrossToolkitCopies() {
        Map<String, Object> field = new HashMap<>(Map.of("type", "integer"));
        ToolSchema schema =
                ToolSchema.builder()
                        .name("snapshot")
                        .description("Snapshot")
                        .parameters(Map.of("type", "object", "properties", Map.of("value", field)))
                        .build();
        var toolkit =
                new ApplicationGuardedToolkit(List.of(schema), (batch, context) -> Mono.empty());
        field.put("type", "string");
        Map<?, ?> properties =
                (Map<?, ?>) toolkit.copy().getTool("snapshot").getParameters().get("properties");
        Map<?, ?> retainedField = (Map<?, ?>) properties.get("value");
        assertEquals("integer", retainedField.get("type"));
        assertThrows(UnsupportedOperationException.class, retainedField::clear);
    }

    @Test
    void oneGuardedBatchOwnsParallelCallsAndResultsKeepOriginalIdsInOrder() {
        var calls = List.of(call("id-first", "first", 1), call("id-second", "second", 2));
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger rounds = new AtomicInteger();
        AtomicReference<List<ToolResultBlock>> history = new AtomicReference<>();
        var toolkit =
                new ApplicationGuardedToolkit(
                        SCHEMAS,
                        (batch, context) -> {
                            executions.incrementAndGet();
                            assertEquals(
                                    calls.stream().map(ToolUseBlock::getId).toList(),
                                    batch.stream().map(ToolUseBlock::getId).toList());
                            assertEquals(
                                    calls.stream().map(ToolUseBlock::getContent).toList(),
                                    batch.stream().map(ToolUseBlock::getContent).toList());
                            assertEquals(
                                    calls.stream().map(ToolUseBlock::getInput).toList(),
                                    batch.stream().map(ToolUseBlock::getInput).toList());
                            return Mono.just(
                                    new GuardedToolBatchResult(
                                            batch.stream()
                                                    .map(
                                                            call ->
                                                                    result(
                                                                            call,
                                                                            ToolResultState.SUCCESS,
                                                                            Map.of(
                                                                                    "source",
                                                                                    "guarded")))
                                                    .toList(),
                                            Map.of(),
                                            null));
                        });
        try (var agent = agent(toolkit, model(calls, rounds, history))) {
            List<AgentEvent> events =
                    agent.streamEvents("Question", RuntimeContext.empty())
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertEquals(1, executions.get());
            assertEquals(2, rounds.get());
            assertEquals(
                    List.of("id-first", "id-second"),
                    history.get().stream().map(ToolResultBlock::getId).toList());
            assertEquals(
                    List.of("id-first", "id-second"),
                    events.stream()
                            .filter(ToolResultEndEvent.class::isInstance)
                            .map(ToolResultEndEvent.class::cast)
                            .map(ToolResultEndEvent::getToolCallId)
                            .toList());
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    event ->
                                            event instanceof AgentResultEvent result
                                                    && "final-answer"
                                                            .equals(
                                                                    result.getResult()
                                                                            .getTextContent())));
        }
    }

    @Test
    void approvalBarrierStopsAfterOneBatchAndNeverRunsLaterCall() {
        var calls =
                List.of(
                        call("before", "first", 1),
                        call("approval", "second", 2),
                        call("after", "third", 3));
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        AtomicInteger batches = new AtomicInteger();
        AtomicInteger rounds = new AtomicInteger();
        var toolkit =
                new ApplicationGuardedToolkit(
                        SCHEMAS,
                        (batch, context) -> {
                            batches.incrementAndGet();
                            before.incrementAndGet();
                            return Mono.just(
                                    new GuardedToolBatchResult(
                                            List.of(
                                                    result(
                                                            batch.get(0),
                                                            ToolResultState.SUCCESS,
                                                            Map.of()),
                                                    result(
                                                            batch.get(1),
                                                            ToolResultState.ERROR,
                                                            Map.of(
                                                                    "pendingId",
                                                                    "pending-one",
                                                                    "awaitingApproval",
                                                                    true)),
                                                    result(
                                                            batch.get(2),
                                                            ToolResultState.ERROR,
                                                            Map.of("skipped", true))),
                                            Map.of("pendingId", "pending-one"),
                                            "awaiting_approval"));
                        });
        try (var agent = agent(toolkit, model(calls, rounds, new AtomicReference<>()))) {
            agent.streamEvents("Question", RuntimeContext.empty())
                    .collectList()
                    .block(Duration.ofSeconds(5));
            assertEquals(1, batches.get());
            assertEquals(1, before.get());
            assertEquals(0, after.get());
            assertEquals(1, rounds.get());
            var results =
                    agent.getAgentState().getContext().stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .toList();
            assertEquals(
                    List.of("before", "approval", "after"),
                    results.stream().map(ToolResultBlock::getId).toList());
            assertEquals("pending-one", results.get(1).getMetadata().get("pendingId"));
        }
    }

    @Test
    void structuredResultsAndErrorsSurviveIntoNextModelHistory() {
        var calls = List.of(call("success", "first", 1), call("error", "second", 2));
        AtomicReference<List<ToolResultBlock>> history = new AtomicReference<>();
        var toolkit =
                new ApplicationGuardedToolkit(
                        SCHEMAS,
                        (batch, context) ->
                                Mono.just(
                                        new GuardedToolBatchResult(
                                                List.of(
                                                        result(
                                                                batch.get(0),
                                                                ToolResultState.SUCCESS,
                                                                Map.of(
                                                                        "structured",
                                                                        Map.of("answer", 42))),
                                                        result(
                                                                batch.get(1),
                                                                ToolResultState.ERROR,
                                                                Map.of(
                                                                        "errorType",
                                                                        "guard_denied"))),
                                                Map.of(),
                                                null)));
        try (var agent = agent(toolkit, model(calls, new AtomicInteger(), history))) {
            agent.streamEvents("Question", RuntimeContext.empty())
                    .collectList()
                    .block(Duration.ofSeconds(5));
            assertEquals(
                    Map.of("answer", 42), history.get().get(0).getMetadata().get("structured"));
            assertEquals(ToolResultState.ERROR, history.get().get(1).getState());
            assertEquals("guard_denied", history.get().get(1).getMetadata().get("errorType"));
        }
    }

    @Test
    void cancellationReachesOnlyTheBatchOwner() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger batches = new AtomicInteger();
        var toolkit =
                new ApplicationGuardedToolkit(
                        SCHEMAS,
                        (batch, context) -> {
                            batches.incrementAndGet();
                            started.countDown();
                            return Mono.<GuardedToolBatchResult>never()
                                    .doOnCancel(cancelled::countDown);
                        });
        try (var agent =
                agent(
                        toolkit,
                        model(
                                List.of(call("one", "first", 1)),
                                new AtomicInteger(),
                                new AtomicReference<>()))) {
            var subscription = agent.streamEvents("Question", RuntimeContext.empty()).subscribe();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            subscription.dispose();
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            assertEquals(1, batches.get());
        }
    }

    @Test
    void fatalOwnerFailureStopsBeforeAnotherModelAndSurfacesOriginalException() {
        AtomicInteger batches = new AtomicInteger();
        AtomicInteger rounds = new AtomicInteger();
        var failure = new IllegalStateException("guard-owner-failed");
        var toolkit =
                new ApplicationGuardedToolkit(
                        SCHEMAS,
                        (batch, context) -> {
                            batches.incrementAndGet();
                            return Mono.error(failure);
                        });
        try (var agent =
                agent(
                        toolkit,
                        model(List.of(call("one", "first", 1)), rounds, new AtomicReference<>()))) {
            RuntimeContext context = RuntimeContext.empty();
            List<AgentEvent> emitted = new ArrayList<>();
            var thrown =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    agent.streamEvents("Question", context)
                                            .doOnNext(emitted::add)
                                            .collectList()
                                            .block(Duration.ofSeconds(5)));
            assertSame(failure, ApplicationGuardedToolkit.outcome(context).failure());
            assertFalse(emitted.stream().anyMatch(AgentResultEvent.class::isInstance));
            assertSame(failure, thrown);
            assertEquals(1, batches.get());
            assertEquals(1, rounds.get());
        }
    }

    @Test
    void mismatchedOwnerResultFailsClosedAndSchemaCallbackCannotExecute() {
        AtomicInteger rounds = new AtomicInteger();
        AtomicInteger batches = new AtomicInteger();
        var toolkit =
                new ApplicationGuardedToolkit(
                        SCHEMAS,
                        (batch, context) -> {
                            batches.incrementAndGet();
                            return Mono.just(
                                    new GuardedToolBatchResult(
                                            List.of(
                                                    result(
                                                            call("wrong", "first", 1),
                                                            ToolResultState.SUCCESS,
                                                            Map.of())),
                                            Map.of(),
                                            null));
                        });
        assertThrows(
                IllegalStateException.class,
                () -> toolkit.getTool("first").callAsync(null).block());
        assertInstanceOf(ApplicationGuardedToolkit.class, toolkit.copy());
        try (var agent =
                agent(
                        toolkit,
                        model(List.of(call("one", "first", 1)), rounds, new AtomicReference<>()))) {
            var thrown =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    agent.streamEvents("Question", RuntimeContext.empty())
                                            .collectList()
                                            .block(Duration.ofSeconds(5)));
            assertTrue(thrown.getMessage().contains("match the original calls"));
            assertEquals(1, rounds.get());
            assertEquals(1, batches.get());
        }
    }

    @Test
    void unknownToolStopsBeforeOwnerOrAnotherModel() {
        AtomicInteger rounds = new AtomicInteger();
        AtomicInteger batches = new AtomicInteger();
        var toolkit =
                new ApplicationGuardedToolkit(
                        SCHEMAS,
                        (batch, context) -> {
                            batches.incrementAndGet();
                            return Mono.error(
                                    new AssertionError("Unknown tool must never reach owner"));
                        });
        try (var agent =
                agent(
                        toolkit,
                        model(
                                List.of(call("one", "unknown", 1)),
                                rounds,
                                new AtomicReference<>()))) {
            var thrown =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    agent.streamEvents("Question", RuntimeContext.empty())
                                            .collectList()
                                            .block(Duration.ofSeconds(5)));
            assertEquals("Unknown tool: unknown", thrown.getMessage());
            assertEquals(1, rounds.get());
            assertEquals(0, batches.get());
        }
    }

    @Test
    void approvalAndDirectStopAtOneIterationPreserveBoundOutcomeAndHistory() {
        for (String stopReason : List.of("awaiting_approval", "direct_output")) {
            AtomicInteger rounds = new AtomicInteger();
            AtomicInteger batches = new AtomicInteger();
            RuntimeContext context = RuntimeContext.empty();
            var calls = List.of(call("one", "first", 1));
            var toolkit =
                    new ApplicationGuardedToolkit(
                            SCHEMAS,
                            (batch, ignored) -> {
                                batches.incrementAndGet();
                                return Mono.just(
                                        new GuardedToolBatchResult(
                                                List.of(
                                                        result(
                                                                batch.getFirst(),
                                                                ToolResultState.SUCCESS,
                                                                Map.of("stopReason", stopReason))),
                                                Map.of("pendingOrDirect", "bound-value"),
                                                stopReason));
                            });
            try (var agent =
                    ReActAgent.builder()
                            .name("single-iteration")
                            .model(model(calls, rounds, new AtomicReference<>()))
                            .toolkit(toolkit)
                            .middleware(toolkit.boundaryMiddleware())
                            .maxIters(1)
                            .maxRetries(1)
                            .enableMetaTool(false)
                            .enablePendingToolRecovery(false)
                            .build()) {
                var events =
                        agent.streamEvents("Question", context)
                                .collectList()
                                .block(Duration.ofSeconds(5));
                assertEquals(1, rounds.get());
                assertEquals(1, batches.get());
                var outcome = ApplicationGuardedToolkit.outcome(context);
                assertNull(outcome.failure());
                assertEquals(stopReason, outcome.result().stopReason());
                assertEquals("bound-value", outcome.result().metadata().get("pendingOrDirect"));
                var results =
                        agent.getAgentState().getContext().stream()
                                .flatMap(
                                        message ->
                                                message
                                                        .getContentBlocks(ToolResultBlock.class)
                                                        .stream())
                                .toList();
                assertEquals(List.of("one"), results.stream().map(ToolResultBlock::getId).toList());
                assertEquals(stopReason, results.getFirst().getMetadata().get("stopReason"));
                assertTrue(events.stream().anyMatch(ToolResultEndEvent.class::isInstance));
                assertFalse(
                        events.stream().anyMatch(AgentResultEvent.class::isInstance),
                        "Suppressed summary has no accumulated model response; application outcome"
                                + " is terminal");
            }
        }
    }

    @Test
    void fatalFailureAtOneIterationIsBoundAndSurfacedWithoutAnotherModelRound() {
        var failure = new IllegalStateException("fatal-one-iteration");
        AtomicInteger rounds = new AtomicInteger();
        RuntimeContext context = RuntimeContext.empty();
        var toolkit =
                new ApplicationGuardedToolkit(SCHEMAS, (batch, ignored) -> Mono.error(failure));
        try (var agent =
                ReActAgent.builder()
                        .name("single-iteration")
                        .model(
                                model(
                                        List.of(call("one", "first", 1)),
                                        rounds,
                                        new AtomicReference<>()))
                        .toolkit(toolkit)
                        .middleware(toolkit.boundaryMiddleware())
                        .maxIters(1)
                        .maxRetries(1)
                        .enableMetaTool(false)
                        .enablePendingToolRecovery(false)
                        .build()) {
            assertSame(
                    failure,
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    agent.streamEvents("Question", context)
                                            .collectList()
                                            .block(Duration.ofSeconds(5))));
            assertSame(failure, ApplicationGuardedToolkit.outcome(context).failure());
            assertEquals(1, rounds.get());
            var results =
                    agent.getAgentState().getContext().stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .toList();
            assertEquals(ToolResultState.ERROR, results.getFirst().getState());
        }
    }

    @Test
    void missingExplicitRuntimeContextFailsBeforeModelOrOwner() {
        AtomicInteger rounds = new AtomicInteger();
        var toolkit =
                new ApplicationGuardedToolkit(
                        SCHEMAS,
                        (batch, context) -> Mono.error(new AssertionError("Must not run")));
        try (var agent =
                agent(
                        toolkit,
                        model(List.of(call("one", "first", 1)), rounds, new AtomicReference<>()))) {
            var thrown =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    agent.streamEvents("Question")
                                            .collectList()
                                            .block(Duration.ofSeconds(5)));
            assertTrue(thrown.getMessage().contains("explicit per-run RuntimeContext"));
            assertEquals(0, rounds.get());
        }
    }

    @Test
    void unknownNameIsDispatchedOnceToOwnerAndErrorReachesNextModel() {
        AtomicInteger executions = new AtomicInteger(), rounds = new AtomicInteger();
        AtomicReference<List<ToolResultBlock>> history = new AtomicReference<>();
        var toolkit =
                new ApplicationGuardedToolkit(
                        SCHEMAS,
                        (batch, context) -> {
                            executions.incrementAndGet();
                            assertEquals("unknown", batch.getFirst().getName());
                            assertEquals("{\"value\":1}", batch.getFirst().getContent());
                            return Mono.just(
                                    new GuardedToolBatchResult(
                                            List.of(
                                                    result(
                                                            batch.getFirst(),
                                                            ToolResultState.ERROR,
                                                            Map.of("skillHint", "load_skill"))),
                                            Map.of(),
                                            null));
                        },
                        ApplicationGuardedToolkit.UnknownToolPolicy.APPLICATION_OWNER);
        try (var agent =
                agent(
                        toolkit,
                        model(List.of(call("original-unknown", "unknown", 1)), rounds, history))) {
            var events =
                    agent.streamEvents("Question", RuntimeContext.empty())
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertEquals(1, executions.get());
            assertEquals(2, rounds.get());
            assertEquals("original-unknown", history.get().getFirst().getId());
            assertEquals("load_skill", history.get().getFirst().getMetadata().get("skillHint"));
            assertTrue(events.stream().anyMatch(AgentResultEvent.class::isInstance));
        }
    }
}
