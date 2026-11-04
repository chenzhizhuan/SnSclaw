# 3.0.0: execution architecture upgrade

Release date: **2026-10-26**. These notes cover the 3.0.0 stable release. Source and package versions are `3.0.0`; download published packages from [GitHub Releases](https://github.com/mateaix/mateclaw/releases).

## Cumulative changes since 2.3.0

3.0.0 includes the former 2.4.0 development cycle; 2.4.0 was not published as a separate stable release. The groups below cover capabilities added before the architecture migration as well.

### Workspaces and conversations

- Member file isolation is an opt-in strict sandbox. With `MATECLAW_WORKSPACE_SANDBOX_MEMBER_ISOLATION_ENABLED=true`, authenticated user and workspace identities determine the file root; missing identity denies access without shared-directory fallback. Only six audited builtin file/code tools are available; DSH, MCP/plugin tools, shared skill scripts and unauthenticated channel identities are unavailable. This differs from personal memory-file fallback. (#644).
- Persistent channel conversations expire stale query context. Uploaded images resolve from conversation storage; directory uploads retain directory metadata and validate containment.
- Employee drafts accept JSON following reasoning blocks, and explicit tool opt-out is respected even for general capabilities (#648, #655).

### Structured decisions, Goals, plans and teams

- A bounded typed Choice / Boolean / Score decision pipeline adds durable audit, OFF / SHADOW / ACTIVE modes and scenario overrides. SHADOW is the default; suggestions cannot bypass domain constraints.
- Goal continuation, plan employee routing and team worker-result judgment share the decision foundation. Domain transactions commit outcomes and state; delayed decisions validate identity and fences.
- Richer routing context, worker contracts, delegated-step result checks and bounded Goal recovery retain existing completion gates and record applied follow-up suppression.

### Models and DeepSeek Harness

- DSH moves to the SDK runtime with pinned versions and controlled upgrades. Failed health checks invalidate cached health and broken settings remain editable (#650).
- Model probes validate completed responses rather than accepting connection success or empty output (#658).

### Read-aloud, chat and tool presentation

- Edge and DashScope read-aloud are restored, with provider voice lists, selection and preview (#646).
- Structured MCP results get dedicated presentation below the final answer. Ordinary tool results no longer automatically become final-answer text (#651, #656, #661).

### Channels and database operations

- Application-owned Feishu WebSocket readiness and lifecycle release owned resources on shutdown and reconnect (#659).
- Unsupported webhook channels are rejected before configuration persistence (#654).
- Flyway validation failures block startup by default; history repair requires explicit opt-in, with backup and recovery guidance (#657).
- Commercial-edition overview and links are added; commercial modules are not included in the open-source edition.

## Finalization, team delivery and deployment

- Long tasks check completion before returning a final answer and can continue unfinished work within runtime limits. Requests that need user input wait for a reply instead of repeatedly starting action turns (#668).
- When reasoning consumes the response output budget, final-answer generation can retry within runtime limits. DeepSeek Flash thinking controls are applied consistently (#672).
- Team worker progress retains full result evidence and runtime failure causes. A Team Run publishes completion after final synthesis, keeping worker output, progress and the delivered summary consistent.
- DSH keeps its reviewed default SDK version; `0.2.1-rc.2` is an opt-in candidate. Docker runtime images pin Node.js 22 and include bubblewrap for Linux sandbox execution (#670). Candidate checks on macOS arm64 do not establish Linux or Windows acceptance; see [DSH setup and limitations](./deepseek-harness).
- Every backend requires a non-empty `JWT_SECRET` of at least 32 UTF-8 bytes. Desktop local mode generates and persists its secret; source and server deployments must configure and retain theirs. See [configuration](./config).

## Platform and execution core

Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 and AgentScope Java 2.0.4. Spring AI Alibaba starter and Graph dependencies are removed. AgentScope drives ordinary ReAct and the reasoning/tool/observation loop inside each Plan-Execute step. MateClaw retains overall plan scheduling, Goal budgets and acceptance, and team coordination.

ApplicationGuardedToolkit delegates tool batches to the application executor, retaining approvals, workspace permissions, audit and cancellation. Model requests use existing Spring AI/provider adapters; MateClaw owns SSE, history and persistence.

## Models, tools and recovery

- Native DashScope chat and embedding adapters replace Alibaba AI integration; the native speech SDK remains.
- OpenAI/Anthropic adapters preserve reasoning, media, cache and usage. Cancellation aborts owned HTTP calls before reader cleanup and prevents subsequent failover.
- MCP SDK 0.18.3 preserves call IDs, structured results and protocol errors; local execution capabilities stay out of wire metadata.
- Goal tool checkpoints persist execution identity before side effects. Uncertain outcomes block blind replay, retaining approval recovery and session isolation.
- MyBatis Plus 3.5.17 uses the Spring Boot 4 starter; string IDs and existing conversation contracts are retained.
- Binary skill bundles and bundled documentation synchronization are hardened.

## Upgrade and validation

Stop the old process and back up the database, workspace, plugins and configuration before upgrading. Build with Java 21 and check third-party plugin compatibility. Rollback uses the pre-upgrade backup; downgrading an upgraded database is not verified.

Validation covers the Maven reactor, frontend build, PostgreSQL/MySQL migrations and transactional pagination, approvals/cancellation, process-crash recovery and six-hour sustained execution. A real Kingbase instance was not tested. Existing frontend interaction contracts remain compatible; this migration does not rewrite the chat UI.

## Context-budget consistency on AgentScope 2.0.4

The native execution path stays on the stable AgentScope Java 2.0.4 API; it does not adopt a beta Harness dependency. Spring AI's native option combination resolves partial runtime options against model defaults. Shared request estimates cover messages, effective tool callback schemas and one output reserve, including OpenAI completion limits. Explicitly empty runtime callbacks suppress default tools, and long totals prevent integer overflow. Reasoning preparation, compaction retries and summary-model guards use the shared estimate while retaining call budgets, permissions, approvals and Goal acceptance.

These checks estimate request size; they do not guarantee provider acceptance, exact billing or correct tool arguments. See [model budget rules](./models#context-budgets-and-request-preparation-3-0-0).

## Upgrade checklist

1. Stop the previous backend. Back up the database together with workspace files, plugin files and configuration, including the JWT secret. Retain the previous application package for rollback.
2. Build with Java 21 and check plugins against the Boot 4 / Spring AI 2 interfaces. Preserve the database profile and paths. In a source checkout, start from the repository root with explicit root `data/` database and workspace paths; `mateclaw-server/data/` is a separate database.
3. Keep Flyway validation enabled. If migration validation fails, inspect the SQL and schema before considering an explicitly authorized history repair; do not enable automatic repair as an upgrade default. See [database operations](./config).
4. After startup, check health and login, confirm existing model configurations, then exercise a real model response, tool approval/rejection, cancellation and an existing conversation. For Goal recovery, inspect checkpoints and acceptance evidence before resuming work with side effects.
5. If rollback is required, stop the new process and restore the pre-upgrade database, files and configuration alongside the old application. Do not assume the old binary can safely open an upgraded database.

## Latest budget regression evidence (2026-10-20)

The budget change passed 342 focused backend tests and the full backend suite: 6,509 tests, zero failures/errors, 50 skipped. These counts describe this verification run rather than every feature listed above.

After restarting the backend against the configured root database and the frontend on port 5173, 20 real-model rounds ran through the frontend API proxy with DeepSeek V4 Flash. Numeric results and streamed/persisted answers matched in all 20 rounds. The original strict audit passed 17/20; manual review accepted 18/20 after identifying a keyword-only false positive in round 15. Original audit flags were retained.

Two rounds remain failed: round 6 placed `language` outside the tool's nested arguments and triggered a schema rejection; round 20 reported 15 progress updates where receipts showed 41. Task-level recovery did not demonstrate a successful retry of the deliberately rejected original `print(1)` call. These results do not establish a fully passing 20-round acceptance run or an efficiency improvement.
