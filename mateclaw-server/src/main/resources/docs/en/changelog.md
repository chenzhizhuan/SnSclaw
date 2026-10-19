# 3.0.0-SNAPSHOT: execution architecture upgrade

Development snapshot; no stable 3.0.0 release has been published.

## Cumulative changes since 2.3.0

3.0.0-SNAPSHOT includes the former 2.4.0 development cycle; 2.4.0 was not published as a separate stable release. The groups below cover capabilities added before the architecture migration as well.

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
