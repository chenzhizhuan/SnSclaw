package vip.mate.agent.runtime.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.Toolkit;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** SDK orchestration with one application-owned guarded batch executor. */
public final class ApplicationGuardedToolkit extends Toolkit {
    public enum UnknownToolPolicy {
        FAIL_CLOSED,
        APPLICATION_OWNER
    }

    private final UnknownToolPolicy unknownToolPolicy;
    private final GuardedToolBatchExecutor executor;
    private final List<ToolSchema> schemas;

    public ApplicationGuardedToolkit(List<ToolSchema> schemas, GuardedToolBatchExecutor executor) {
        this(schemas, executor, UnknownToolPolicy.FAIL_CLOSED);
    }

    public ApplicationGuardedToolkit(
            List<ToolSchema> schemas,
            GuardedToolBatchExecutor executor,
            UnknownToolPolicy unknownToolPolicy) {
        this.unknownToolPolicy = Objects.requireNonNull(unknownToolPolicy);
        this.executor = Objects.requireNonNull(executor);
        this.schemas =
                schemas.stream()
                        .map(
                                schema ->
                                        ToolSchema.builder()
                                                .name(schema.getName())
                                                .description(schema.getDescription())
                                                .strict(schema.getStrict())
                                                .parameters(immutableSchema(schema.getParameters()))
                                                .outputSchema(
                                                        schema.getOutputSchema() == null
                                                                ? null
                                                                : immutableSchema(
                                                                        schema.getOutputSchema()))
                                                .build())
                        .toList();
        for (ToolSchema schema : this.schemas) registerAgentTool(new SchemaOnlyTool(schema));
    }

    private static Map<String, Object> immutableSchema(Map<String, Object> schema) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        schema.forEach((key, value) -> snapshot.put(key, immutableSchemaValue(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    private static Object immutableSchemaValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            map.forEach(
                    (key, item) -> {
                        if (!(key instanceof String name))
                            throw new IllegalArgumentException("Schema keys must be strings");
                        snapshot.put(name, immutableSchemaValue(item));
                    });
            return Collections.unmodifiableMap(snapshot);
        }
        if (value instanceof Collection<?> items) {
            List<Object> snapshot = new ArrayList<>();
            items.forEach(item -> snapshot.add(immutableSchemaValue(item)));
            return Collections.unmodifiableList(snapshot);
        }
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) return value;
        throw new IllegalArgumentException(
                "Unsupported schema value: " + value.getClass().getName());
    }

    @Override
    public Toolkit copy() {
        return new ApplicationGuardedToolkit(schemas, executor, unknownToolPolicy);
    }

    /** Install once; stop before the next model call, after SDK batch result persistence. */
    public MiddlewareBase boundaryMiddleware() {
        return new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onAgent(
                    Agent agent,
                    RuntimeContext context,
                    AgentInput input,
                    Function<AgentInput, Flux<AgentEvent>> next) {
                return Flux.defer(
                        () -> {
                            if (context == null)
                                return Flux.error(
                                        new IllegalArgumentException(
                                                "Guarded toolkit requires an explicit per-run"
                                                        + " RuntimeContext"));
                            context.put(BatchState.class, new BatchState());
                            return next.apply(input)
                                    .filter(
                                            event ->
                                                    !(event instanceof AgentResultEvent)
                                                            || outcome(context).failure() == null)
                                    .concatWith(
                                            Flux.defer(
                                                    () -> {
                                                        BoundaryOutcome outcome = outcome(context);
                                                        return outcome.failure() == null
                                                                ? Flux.empty()
                                                                : Flux.error(outcome.failure());
                                                    }));
                        });
            }

            @Override
            public Flux<AgentEvent> onActing(
                    Agent agent,
                    RuntimeContext context,
                    ActingInput input,
                    Function<ActingInput, Flux<AgentEvent>> next) {
                return Flux.defer(
                        () -> {
                            BatchState state = new BatchState();
                            context.put(BatchState.class, state);
                            return next.apply(input);
                        });
            }

            @Override
            public Flux<AgentEvent> onModelCall(
                    Agent agent,
                    RuntimeContext context,
                    ModelCallInput input,
                    Function<ModelCallInput, Flux<AgentEvent>> next) {
                BoundaryOutcome outcome = outcome(context);
                if (outcome.failure() != null)
                    return Flux.just(
                            new RequestStopEvent(
                                    "guarded_boundary_failed",
                                    GenerateReason.MIDDLEWARE_STOP_REQUESTED));
                if (outcome.result() != null && outcome.result().stopReason() != null)
                    return Flux.just(
                            new RequestStopEvent(
                                    outcome.result().stopReason(),
                                    GenerateReason.MIDDLEWARE_STOP_REQUESTED));
                return next.apply(input);
            }

            @Override
            public Flux<AgentEvent> onReasoning(
                    Agent agent,
                    RuntimeContext context,
                    ReasoningInput input,
                    Function<ReasoningInput, Flux<AgentEvent>> next) {
                BatchState state = context.get(BatchState.class);
                if (state != null && state.failure != null)
                    return Flux.just(
                            new RequestStopEvent(
                                    "guarded_boundary_failed",
                                    GenerateReason.MIDDLEWARE_STOP_REQUESTED));
                if (state != null && state.result != null && state.result.stopReason() != null)
                    return Flux.just(
                            new RequestStopEvent(
                                    state.result.stopReason(),
                                    GenerateReason.MIDDLEWARE_STOP_REQUESTED));
                return next.apply(input);
            }
        };
    }

    @Override
    public Mono<List<ToolResultBlock>> callTools(
            List<ToolUseBlock> toolCalls,
            ExecutionConfig ignored,
            Agent agent,
            RuntimeContext context,
            ToolRequestConfig request,
            BiConsumer<ToolUseBlock, ToolResultBlock> chunks) {
        return Mono.defer(
                        () -> {
                            BatchState state = context.get(BatchState.class);
                            if (state == null)
                                return Mono.error(
                                        new IllegalStateException(
                                                "Guarded toolkit requires its boundary"
                                                        + " middleware"));
                            List<ToolUseBlock> calls = List.copyOf(toolCalls);
                            Map<String, ToolUseBlock> byId = new LinkedHashMap<>();
                            for (ToolUseBlock call : calls) {
                                if (call.getId() == null
                                        || call.getId().isBlank()
                                        || byId.put(call.getId(), call) != null)
                                    return Mono.error(
                                            new IllegalArgumentException(
                                                    "Tool call IDs must be nonblank and unique"));
                                if (unknownToolPolicy == UnknownToolPolicy.FAIL_CLOSED
                                        && getTool(call.getName()) == null)
                                    return Mono.error(
                                            new IllegalArgumentException(
                                                    "Unknown tool: " + call.getName()));
                            }
                            return executor.execute(calls, context)
                                    .switchIfEmpty(
                                            Mono.error(
                                                    new IllegalStateException(
                                                            "Missing guarded batch result")))
                                    .map(
                                            result -> {
                                                Map<String, ToolResultBlock> results =
                                                        new LinkedHashMap<>();
                                                for (ToolResultBlock block : result.results()) {
                                                    ToolUseBlock call = byId.get(block.getId());
                                                    if (call == null
                                                            || !call.getName()
                                                                    .equals(block.getName())
                                                            || results.put(block.getId(), block)
                                                                    != null)
                                                        throw new IllegalArgumentException(
                                                                "Guarded result IDs and names must"
                                                                    + " match the original calls");
                                                }
                                                if (results.size() != calls.size())
                                                    throw new IllegalArgumentException(
                                                            "Every tool call requires one guarded"
                                                                    + " result");
                                                List<ToolResultBlock> ordered =
                                                        calls.stream()
                                                                .map(
                                                                        call ->
                                                                                results.get(
                                                                                        call
                                                                                                .getId()))
                                                                .toList();
                                                state.result =
                                                        new GuardedToolBatchResult(
                                                                ordered,
                                                                result.metadata(),
                                                                result.stopReason());
                                                if (chunks != null)
                                                    for (int i = 0; i < calls.size(); i++)
                                                        chunks.accept(calls.get(i), ordered.get(i));
                                                return ordered;
                                            });
                        })
                .doOnError(
                        failure -> {
                            BatchState state = context.get(BatchState.class);
                            if (state != null) state.failure = failure;
                        });
    }

    /** Application binding remains available after SDK completion, cancellation, or failure. */
    public static BoundaryOutcome outcome(RuntimeContext context) {
        BatchState state = context.get(BatchState.class);
        return state == null
                ? new BoundaryOutcome(null, null)
                : new BoundaryOutcome(state.result, state.failure);
    }

    public record BoundaryOutcome(GuardedToolBatchResult result, Throwable failure) {}

    private static final class BatchState {
        private volatile GuardedToolBatchResult result;
        private volatile Throwable failure;
    }

    /** No executable business callback is registered in the SDK toolkit. */
    private record SchemaOnlyTool(ToolSchema schema) implements AgentTool {
        @Override
        public String getName() {
            return schema.getName();
        }

        @Override
        public String getDescription() {
            return schema.getDescription();
        }

        @Override
        public Map<String, Object> getParameters() {
            return schema.getParameters();
        }

        @Override
        public Boolean getStrict() {
            return schema.getStrict();
        }

        @Override
        public Map<String, Object> getOutputSchema() {
            return schema.getOutputSchema();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam ignored) {
            return Mono.error(
                    new IllegalStateException(
                            "SDK tool callbacks must never execute; the application owns the whole"
                                    + " batch"));
        }
    }
}
