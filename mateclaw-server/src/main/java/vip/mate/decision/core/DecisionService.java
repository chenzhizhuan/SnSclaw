package vip.mate.decision.core;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.provider.*;
import vip.mate.decision.record.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DecisionService implements AutoCloseable {
    private final DecisionProperties properties;
    private final Map<String, DecisionProvider> providers;
    private final DecisionRecordStore store;
    private final MeterRegistry metrics;
    private final ThreadPoolExecutor providerExecutor;
    private final ThreadPoolExecutor shadowExecutor;

    public DecisionService(DecisionProperties properties, List<DecisionProvider> providers, DecisionRecordStore store, MeterRegistry metrics) {
        properties.validate();
        this.properties = properties;
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(p -> DecisionCodes.requireCode(p.id()), Function.identity()));
        this.store = store; this.metrics = metrics;
        providerExecutor = executor(properties.getProviderThreads(), properties.getQueueCapacity(), "decision-provider-");
        shadowExecutor = executor(properties.getShadowThreads(), properties.getQueueCapacity(), "decision-shadow-");
    }
    private static ThreadPoolExecutor executor(int threads, int capacity, String prefix) {
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().daemon(true).name(prefix, 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    public boolean enabled(DecisionType type) { return properties.modeFor(type) != DecisionMode.OFF; }

    public DecisionTicket decide(DecisionRequest request) {
        var mode = properties.modeFor(request.type());
        metrics.counter("mate.decision.requests", "type", request.type().name(), "mode", mode.name()).increment();
        if (mode == DecisionMode.OFF) return new DecisionTicket(null, mode, request.baseline());
        var policy = properties.policyFor(request.type());
        String id = UUID.randomUUID().toString();
        if (mode == DecisionMode.SHADOW) {
            submitShadow(() -> evaluateAndRecord(request, mode, id, policy), request.type());
            return new DecisionTicket(id, mode, request.baseline());
        }
        return new DecisionTicket(id, mode, evaluateAndRecord(request, mode, id, policy));
    }
    private DecisionValue evaluateAndRecord(DecisionRequest request, DecisionMode mode, String id, DecisionProperties.ResolvedPolicy policy) {
        long start = System.nanoTime();
        var evaluated = evaluate(request, policy);
        DecisionValue effective = evaluated.value;
        String reason = evaluated.reason;
        String overrideReason = "NONE";
        if (request.guardValue() == null && request.policyOverride() != null) {
            effective = request.policyOverride(); overrideReason = "POLICY_OVERRIDE";
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        String providerId = request.guardValue() != null ? "none" : providers.containsKey(policy.provider()) ? policy.provider() : "unavailable";
        var record = new DecisionRecord(id, request.type(), request.scope(), request.phase(), request.question().version(), mode,
                providerId, evaluated.version, kind(request.baseline()), request.baseline().encoded(),
                evaluated.proposed == null ? null : evaluated.proposed.encoded(), effective.encoded(), evaluated.confidence, reason, overrideReason, elapsed);
        metrics.counter("mate.decision.total", "type", request.type().name(), "mode", mode.name(), "provider", providerId, "reason", reason, "override", overrideReason).increment();
        metrics.timer("mate.decision.duration", "type", request.type().name(), "mode", mode.name()).record(elapsed, TimeUnit.MILLISECONDS);
        if (evaluated.proposed != null || "BASELINE".equals(evaluated.reason)) {
            var proposed = evaluated.proposed == null ? request.baseline() : evaluated.proposed;
            metrics.counter("mate.decision.comparison", "type", request.type().name(), "mode", mode.name(), "stage", "proposed",
                    "agreement", Boolean.toString(proposed.equals(request.baseline()))).increment();
            metrics.counter("mate.decision.comparison", "type", request.type().name(), "mode", mode.name(), "stage", "effective",
                    "agreement", Boolean.toString(effective.equals(request.baseline()))).increment();
        }
        try { store.insert(record); }
        catch (RuntimeException ex) {
            failure("record", request.type());
            if (mode == DecisionMode.ACTIVE) throw new DecisionRecordingException();
        }
        return effective;
    }
    private Evaluation evaluate(DecisionRequest request, DecisionProperties.ResolvedPolicy policy) {
        if (request.guardValue() != null) return new Evaluation(request.guardValue(), null, null, "none", "GUARD");
        DecisionProvider provider = providers.get(policy.provider());
        if (provider == null) return fallback(request, "UNAVAILABLE");
        Future<DecisionResult> future = null;
        try {
            future = providerExecutor.submit(() -> {
                if (!provider.capability().supports(request)) return new DecisionResult(DecisionResult.Status.UNAVAILABLE, null, null, "unsupported");
                return provider.decide(request);
            });
            DecisionResult result = future.get(policy.timeoutMs(), TimeUnit.MILLISECONDS);
            if (result == null || result.status() == null) return fallback(request, "INVALID_RESULT");
            String version = DecisionCodes.requireCode(result.version());
            if (result.status() == DecisionResult.Status.BASELINE)
                return new Evaluation(request.baseline(), null, null, version, "BASELINE");
            if (result.status() != DecisionResult.Status.PROPOSED)
                return new Evaluation(request.baseline(), null, null, version, result.status().name());
            if (!request.question().accepts(result.value()) || result.confidence() == null || !Double.isFinite(result.confidence())
                    || result.confidence() < 0 || result.confidence() > 1) return fallback(request, "INVALID_RESULT");
            if (result.confidence() < policy.confidenceThreshold())
                return new Evaluation(request.baseline(), result.value(), result.confidence(), version, "LOW_CONFIDENCE");
            return new Evaluation(result.value(), result.value(), result.confidence(), version, "PROPOSED");
        } catch (TimeoutException ex) { return fallback(request, "TIMEOUT"); }
        catch (RejectedExecutionException ex) { return fallback(request, "SATURATED"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); return fallback(request, "INTERRUPTED"); }
        catch (RuntimeException | ExecutionException ex) { return fallback(request, "PROVIDER_ERROR"); }
        finally { if (future != null && !future.isDone()) future.cancel(true); }
    }
    private Evaluation fallback(DecisionRequest request, String reason) { return new Evaluation(request.baseline(), null, null, "unknown", reason); }
    private static String kind(DecisionValue value) {
        return switch (value) {
            case DecisionValue.Choice ignored -> "CHOICE";
            case DecisionValue.BooleanValue ignored -> "BOOLEAN";
            case DecisionValue.Score ignored -> "SCORE";
        };
    }
    /**
     * ACTIVE writes join the caller's transaction: call at the mutation boundary, before commit.
     * SHADOW writes run only after a successful commit and are best effort. An absent outcome
     * remains pending; returning a proposal never claims that a business mutation happened.
     */
    public void recordOutcome(DecisionTicket ticket, DecisionOutcome outcome, DecisionValue actualValue) {
        if (ticket == null || ticket.mode() == DecisionMode.OFF || ticket.id() == null) return;
        if (outcome == null || outcome == DecisionOutcome.PENDING || actualValue == null) throw new IllegalArgumentException("Terminal outcome required");
        if (ticket.mode() == DecisionMode.ACTIVE) {
            try { store.outcome(ticket.id(), outcome, actualValue); }
            catch (RuntimeException ex) { throw new DecisionRecordingException(); }
        } else {
            Runnable write = () -> submitShadow(() -> {
                try { store.outcome(ticket.id(), outcome, actualValue); }
                catch (RuntimeException ex) { failure("outcome", null); }
            }, null);
            if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { write.run(); }
                });
            } else write.run();
        }
    }
    private void submitShadow(Runnable task, DecisionType type) {
        try { shadowExecutor.execute(() -> { try { task.run(); } catch (RuntimeException ex) { failure("shadow", type); } }); }
        catch (RejectedExecutionException ex) { failure("dropped", type); }
    }
    private void failure(String stage, DecisionType type) {
        metrics.counter("mate.decision.failure", "stage", stage, "type", type == null ? "OUTCOME" : type.name()).increment();
    }
    @Override @PreDestroy public void close() { shadowExecutor.shutdownNow(); providerExecutor.shutdownNow(); }
    private record Evaluation(DecisionValue value, DecisionValue proposed, Double confidence, String version, String reason) {}
}
