# Structured decision layer

The decision layer provides replaceable structured judgments. It does not execute tools or replace permissions, completion verification, or existing state machines. Requests use Choice, Boolean, or Score questions. Domain constraints remain authoritative after a Provider returns a suggestion.

## Modes and configuration

`mate.decision.mode` defaults to `SHADOW`:

| Mode | Behavior |
|---|---|
| OFF | Returns the legacy judgment without calling the new Provider or decision record store |
| SHADOW | Keeps the legacy judgment; a bounded background queue evaluates and records suggestions |
| ACTIVE | Uses suggestions that pass hard constraints, type checks, and confidence checks; domain services still commit state |

The following staged-rollout example observes only Goal decisions. Without scenario overrides, all three scenarios inherit the global SHADOW mode.

```yaml
mate:
  decision:
    mode: SHADOW
    provider: rule
    confidence-threshold: 0.8
    timeout-ms: 250
    provider-threads: 4
    shadow-threads: 2
    queue-capacity: 64
    retention-days: 30
    retention-batch-size: 500
    retention-interval-ms: 3600000
    scenarios:
      GOAL_CONTINUATION: SHADOW
      WORKER_RESULT: OFF
      AGENT_ROUTING: OFF
```

Scenario settings override the global mode. To disable the layer completely, set the global mode to OFF and remove or disable scenario overrides. The built-in `rule` Provider explicitly returns the legacy judgment without inventing a confidence value. The `llm` Provider is an unavailable stub; this phase does not call a new model service.

## Processing

1. A deterministic guard checks hard constraints and can bypass the Provider.
2. The Provider produces a typed suggestion within a bounded executor and deadline.
3. Invalid types or candidates, timeout, failure, abstention, or low confidence fall back to the legacy judgment.
4. A policy override applies domain policy without bypassing the guard.
5. The legacy value, Provider suggestion, and effective suggestion are stored separately from the actual committed outcome.

SHADOW always returns the legacy judgment. Queue saturation or background recording failure reduces observation coverage. ACTIVE persists a proposal before returning it; actual outcomes participate in the caller's business transaction. A persisted proposal does not prove that business state committed.

## Integration boundaries

- Goal continuation preserves completion evidence, budgets and leases. An ACTIVE suggestion can conservatively defer or retry an otherwise eligible continuation.
- Worker judging preserves deterministic invalid-result checks, checkpoint rules and approval. An ACTIVE semantic rejection fails the task through the existing state machine; it does not grant another execution attempt. Settlement rechecks the original owner, dispatch count and conversation under lock.
- Agent routing is limited to non-Team plan steps; Team Lead decisions and explicit channel or tool routing remain outside this layer.

## Observability and privacy

Records use `mate_decision_record` and `mate_decision_outcome`. Metadata includes scenario, mode, Provider/version, policy/question versions, workspace and execution identifiers, pipeline evaluation duration (excluding later business commits), confidence, fallback cause, and override cause. Stored values are structured; raw prompts, answers, SQL, tool arguments, evidence text, and exception messages are excluded.

| Outcome | Meaning |
|---|---|
| PENDING | A proposal exists without a confirmed outcome; inferred from a missing outcome row |
| OBSERVED | The actual legacy-path result was observed |
| APPLIED | The caller recorded an actual result at an explicit application boundary |
| NOT_APPLIED | Concurrency checks, limits, or other constraints prevented application |

Graph-local `FOLLOWUP_SUBMITTED` means a followup output was constructed and submitted. `FOLLOWUP_SUPPRESSED` means an ACTIVE DEFER/RETRY suggestion prevented the current graph followup; it does not schedule a durable retry. These outcomes do not prove subsequent nodes executed or provide an atomic database-and-graph commit guarantee.

Micrometer metrics include `mate.decision.total`, `mate.decision.duration`, `mate.decision.comparison`, and `mate.decision.failure`. Business identifiers are excluded from metric tags. Comparison metrics distinguish raw Provider suggestions from effective suggestions; guards and unavailable Providers are not valid model comparisons.

Routing v2 records correlate by parent Agent, conversation and PLAN_STEP phase. Step index is transient and plan/subplan IDs are not persisted in the audit, so exact audit-to-subplan reconstruction is not available. Actual assignment outcomes still commit with plan insertion.

Queries must be scoped to workspace and time range. No new public query API is provided. Agreement rates must be considered alongside dropped work, failures, and missing outcomes. Agreement from the Rule stub does not establish improved model quality.

## Validation and future Providers

Tests use controlled Providers and do not require real models. Database tests use H2 with MySQL/PostgreSQL compatibility modes; these do not replace native MySQL/Kingbase validation.

Future Providers can implement the Java SPI for rules, remote models, or local inference. Rollout requires fixed protocol/model versions, minimized input, domain evaluation including Chinese, confidence calibration, timeout/circuit-breaking policies, and gradual enablement by scenario. Language capability metadata does not currently implement language detection or calibration. Industrial writes, uncertain side effects, and retry idempotency remain governed by existing execution and approval mechanisms.

## Scenario policies and routing inputs (second iteration)

Keep mode overrides in `scenarios`. Optional `scenario-policies` overrides provider, confidence threshold and timeout independently; omitted fields inherit globals, so existing configuration needs no migration. This example still uses the built-in Rule provider:

```yaml
mate:
  decision:
    mode: SHADOW
    provider: rule
    confidence-threshold: 0.8
    timeout-ms: 250
    scenario-policies:
      WORKER_RESULT:
        confidence-threshold: 0.95
        timeout-ms: 500
      AGENT_ROUTING:
        provider: rule
        timeout-ms: 150
```

Overrides are validated at startup: timeouts are 1–60000 ms and thresholds are finite numbers in [0,1]. Unknown providers fall back with UNAVAILABLE rather than selecting the global provider. Each request snapshots its policy before SHADOW queuing. Dynamic configuration refresh is not provided. Example thresholds are not calibrated production recommendations; Rule baseline results do not use confidence thresholds.

Routing v2 supplies the current step as transient evidence and eligible same-workspace candidates' and parent's name, description, tags and agent type as option descriptions. It excludes the full conversation, full original goal, system prompts, runtime configuration and model credentials. The original goal is only inspected locally for explicit Agent selection. Descriptions are unverified capability claims, not tool authorization. Missing parent metadata uses a generic LOCAL description.

Limits: 2048 characters per step, 1024 per option description, and 8192 across step and option descriptions. Missing steps or excessive inputs guard the baseline instead of silently truncating meaning; stale candidates retain the existing LOCAL guard priority. Character limits are not tokenizer limits. Audit records contain typed results and question versions, never these transient texts. Future remote providers still require an outbound-data policy for transient inputs.

This iteration adds no candidate retrieval, live tool capability verification or semantic model provider. Agreement under default SHADOW/Rule does not establish better accuracy or execution efficiency.
