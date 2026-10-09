---
title: DeepSeek Harness Integration
description: Configure, verify, upgrade, and recover the managed DeepSeek Harness SDK runtime.
---

# DeepSeek Harness Integration

DSH is an employee runtime. SnSclaw launches the official `dsh --profile sdk` process and translates its JSON-RPC session events into conversation output. Configure the DeepSeek provider in **Settings → Models**, then select **DSH / DeepSeek Harness** when creating a digital employee with a workspace.

## Requirements and installation

The managed installer requires **Node.js 22**, npm, and registry access. It supports macOS arm64 and Linux x64 candidates. Actual package installation and Java adapter/model checks have passed on macOS arm64 with Node 22.20.0 and npm 11.6.2. Linux x64 remains pending actual verification; Windows/WSL has not been verified.

Use the DSH management console to install the pinned **`0.2.0-rc.1`** target. The reviewed alternative is **`0.1.7-rc.2`**. Installation uses checked-in dependency locks and `npm ci` in a separate candidate directory; it does not resolve a moving `latest` tag or modify global npm packages. Both are prereleases.

Run **Verify**, **Test connection**, and **Test task**. These mean different things: configuration checks, an SDK `initialize` handshake, and a real model task requiring the answer `OK`. A successful handshake alone does not establish model access. The wire server version `0.0.1` is not the npm package version; managed provenance records the pinned package separately. An external executable remains unverified unless independently checked.

## SDK configuration

Use an absolute executable path to `node_modules/.bin/dsh`, profile `sdk`, an existing working directory, and a dedicated home root. Optional patch paths are an ordered JSON array of absolute files. SnSclaw assigns a separate `DSH_HOME` for each workspace/employee pair and launches arguments directly, without a shell.

| Managed key | Purpose |
| --- | --- |
| `dsh.executable_path` | Absolute SDK CLI executable |
| `dsh.profile` | Must be `sdk` |
| `dsh.working_directory` | Default workspace directory |
| `dsh.home_root` | Root for isolated runtime homes |
| `dsh.patch_paths` | Ordered JSON array of patch files, for example `[]` |

Legacy `dsh-jsonrpc-agent`/Cordis entrypoint configuration requires migration. Clear `dsh.cordis_config_path` and the old `DSH_CORDIS_CONFIG` environment setting; replace the executable with the SDK CLI. Do not put a shell command or extra arguments in the executable field. `DSH_JSONRPC_AGENT` remains a compatibility environment name for an executable path, not an instruction to use the old binary.

Keep credentials in the provider configuration or dedicated protected runtime settings. SnSclaw passes them to the child environment. For the official DeepSeek host, the adapter maps its ordinary API root to the Messages API `/anthropic` route required by these packages. Arbitrary custom endpoints still need a compatible Messages API.

SnSclaw sends an explicit model output cap through `initialize.maxTokens`: the configured positive cap, defaulting to 4096, limited to half the known context window. This does not replace DSH's context management.

## Upgrade and rollback

Managed upgrades prepare a separate package generation, block new DSH turns, and wait up to 60 seconds for current turns to drain. They copy runtime homes and configured patch files, check the candidate handshake and a new model task in each affected home, then atomically select the executable/configuration/home generation together. Original homes and the previous generation remain available. Failure before activation keeps the previous selection. Native turns are not part of the DSH admission gate.

The home copier rejects symlinks and special files instead of following them. Customized homes containing such files require explicit migration. Generation records and package locations are retained; active home contents remain mutable during normal use.

Rollback is available for a completed operation while its candidate generation is still active. It copies and checks the retained previous home/configuration before switching. It does **not** merge sessions created after the upgrade or reverse database changes. Keep retained generations and backups until acceptance is complete.

Candidate checks start new sessions. They do not prove that every historic DSH session can be reopened or migrated. Actual persisted **V3 → V4 history migration has not been verified**.

DSH uses its profile-managed tools and does not provide SnSclaw host approval integration. Member file isolation blocks this runtime. Rollback does not undo tool writes to workspace files or delete SnSclaw chat records.

## Conversations and diagnostics

Each SnSclaw turn uses a fresh SDK session ID. SnSclaw replays bounded completed user/assistant text history; this is not restoration of DSH tool state. Do not reuse an existing persisted SDK ID as a fresh session. Completion is based on the SDK terminal event, not merely on an enqueue receipt or idle status.

The admin API is under `/api/v1/admin/dsh`: `GET /status`, `POST /verify`, `/test-connection`, `/test-task`, `/upgrades`, and `GET /upgrades/{id}`. Upgrade requests carry an exact `targetVersion`, the current `expectedRevision`, and an `idempotencyKey`; rollback is `POST /upgrades/{id}/rollback` with revision and idempotency key. Poll the operation record for results. Logs and status must not expose credentials.

## Recovering an orphaned runtime

`dsh.orphaned_runtime_requires_recovery` means a lease belongs to a JVM that is no longer alive. Admission deliberately remains blocked: its DSH process or tool descendants may still be writing the runtime home.

1. Stop DSH traffic and identify the lease under the managed install root's `leases/` directory (default root: `~/.mateclaw/runtimes/deepseek-harness`).
2. Verify the recorded JVM owner is gone, identify and stop its orphaned DSH processes **and tool descendants**, and confirm no process is writing the affected home.
3. Back up the affected state, then remove only the confirmed stale lease. Do not remove live leases or blindly delete the leases directory.
4. Inspect the durable upgrade operation and active generation, then retry verification before admitting work.

A backend restart alone is not orphan-process cleanup. Do not delete a stale lease merely to bypass the error.

> AI生成
