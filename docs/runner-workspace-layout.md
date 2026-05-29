# Runner Workspace Layout

Story 3.1 introduces a per-execution workspace tree used by the real Docker runner adapter
(`DockerRunnerAdapter`, profile-gated to `runners.docker`). This page documents the host-side
layout, the container-side mount paths, the bind-mount permissions, the network posture, the
label set, and the retention policy stub that story 3.2's cleanup job enforces.

> **Scope.** This story owns the *creation* of the workspace tree and the *file-based
> handoff* between the broker and the runner container. Timeout enforcement, cleanup, secret
> injection, log capture, and the Codex/Claude entrypoints are explicit follow-ups (stories
> 3.2, 3.5, 3.6, 3.3/3.4).

## Host-side layout

```
${deliveryline.home}/
├── runner-scratch/          # story 1.13 — broker-owned bundle + result leaf files
│   └── {rex_id}/
│       ├── context-bundle.v1.json
│       └── runner-result.v1.json
└── runner-work/             # story 3.1 — adapter-owned bind-mount tree
    └── {rex_id}/
        ├── input/           # ro mount, holds the redacted context bundle
        │   └── context-bundle.v1.json
        ├── output/          # rw mount, runner writes runner-result.v1.json + artifact refs
        │   └── runner-result.v1.json (written by the runner)
        └── logs/             # rw mount, runner writes its own log files (story 3.6 captures)
```

`runner-scratch/` and `runner-work/` are deliberately separate directory trees:

- **Lifetime.** Scratch survives the broker (it is the broker's own correlation surface across
  process restarts). Workspace survives the runner exit *plus* the
  `deliveryline.runner.docker.workspace-retention-hours` window (default `24`, declared on the
  `RunnerProperties.Docker` record so story 3.2's cleanup job has a property to read).
- **Ownership.** Scratch is owned by the broker (`RunnerScratchStore`). Workspace is owned by
  the runner adapter (`RunnerWorkspaceStore`).
- **Permissions.** Both trees are created with POSIX `0700` on Linux/macOS; on Windows the
  permission step is skipped and the host ACL applies.

## Container-side mount paths

The Docker adapter binds the three host-side subdirectories at fixed container paths:

| Host path                                | Container path        | Access |
|------------------------------------------|-----------------------|--------|
| `…/runner-work/{rex_id}/input/`          | `/workspace/input`    | `ro`   |
| `…/runner-work/{rex_id}/output/`         | `/workspace/output`   | `rw`   |
| `…/runner-work/{rex_id}/logs/`           | `/workspace/logs`     | `rw`   |

Runner-image entrypoint authors (stories 3.3 / 3.4) reach for
`/workspace/input/context-bundle.v1.json` and write `/workspace/output/runner-result.v1.json`
plus any referenced artifact-content files. **No other host mounts exist** — no
`docker.sock`, no host secrets, no repo workspace (story 3.9 adds a `/workspace/repo` mount via
the same port).

## Network posture

Every container is launched with `--network=none` (AC8). Runner ↔ backend communication is
strictly file-based: the runner cannot reach `127.0.0.1:8080` or any other backend port. The
posture is set at container *create* time (trap T7); the Docker engine rejects attempts to
patch `NetworkMode` on a running container, and the adapter never tries.

## Label set (operator forensics)

`docker inspect` reports the following labels on every dispatched container, allowing
operators to filter via `docker ps --filter label=deliveryline.runnerExecutionId=rex_xyz`:

| Label                            | Value                                  |
|----------------------------------|----------------------------------------|
| `deliveryline.runnerExecutionId` | `rex_…` public id                      |
| `deliveryline.workflowRunId`     | `run_…` public id                      |
| `deliveryline.runnerKind`        | `codex` \| `claude`                    |
| `deliveryline.dispatchedAt`      | ISO-8601 timestamp from injected Clock |

Story 3.2 adds `deliveryline.stage` and consumes `deliveryline.dispatchedAt` for orphan
recovery.

## Retention policy

This story **declares** `deliveryline.runner.docker.workspace-retention-hours` (default `24`)
and **forbids immediate deletion**: dispatch + poll → Completed + tryReadResult leaves the
workspace intact on disk for diagnostic inspection. Story 3.2 ships
`RunnerWorkspaceCleanupJob` that walks `runner-work/` and prunes workspaces older than the
retention window.

## Filename convention

Story 3.1 OQ-4: the input bundle inside the container is `input/context-bundle.v1.json`. The
`.v1` segment aligns with the scratch leaf filename and the JSON schema `$id`. Runner-image
entrypoint authors should read **exactly** that filename (and write
`output/runner-result.v1.json`).

## ArchUnit guardrails

Two ArchUnit rules in `ArchitectureRuleCatalog` pin story 3.1's boundary invariants:

- `DOCKER_RUNNER_ADAPTER_MUST_NOT_DEPEND_ON_ARTIFACT_APPLICATION` (AC6) — keeps the adapter a
  pure transport; artifact ingestion belongs to the broker via `ArtifactOperationService`.
- `ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY` (trap T8) — confines
  `com.github.dockerjava.*` imports to `adapters.runner.docker..` (the `DockerEngineGateway`
  wrapper); upgrades to the docker-java version stay local to that subpackage.

## Local prerequisites

Activating the `runners.docker` profile requires a running Docker daemon. Use
`/doctor` (story 1.16) to probe the local environment, or run
`docker info` directly. Without Docker, stick to the default `runners.mock` profile and the
fast tier — the `DockerClient` Spring bean is profile-gated (trap T10) so the absence of a
daemon never breaks the boot.

## Story 3.2 — lifecycle additions

### Workspace cleanup (`RunnerWorkspaceCleanupJob`)

Activated under `runners.docker` only. Runs on the configured cadence
(`deliveryline.runner.docker.workspace-cleanup-interval-ms`, default 1h) and performs three
sweeps in order (Trap T9):

1. **Workspace deletion** — rows whose `completed_at < now() - workspace-retention-hours` AND
   `archived_at IS NULL` AND `status IN (completed, failed, timed_out, orphaned)` are deleted
   via `RunnerWorkspaceStore.deleteWorkspace`. On success the row is marked
   `archived_at = now()` so it is not re-scanned.
2. **Orphan-directory preservation (Trap T7)** — `rex_*` subdirectories of the workspace root
   that do NOT correspond to a `runner_executions` row are NEVER deleted. They emit a WARN log
   line (`workspace orphan dir found ... action=preserve`) and are left alone for the recovery
   pipeline to reconcile.
3. **Dangling-container cleanup (AC6)** — `docker ps --filter label=deliveryline.runnerExecutionId`
   returns every container the engine still owns under our label namespace. Containers whose
   row is missing or no longer active (`status NOT IN (pending, running)`) are stopped (10s
   grace) then removed. Best-effort; failures WARN but never throw.

### Heartbeat conventions (AC2)

Runner images MAY signal liveness one of three ways. The adapter consults them in priority
order on every `poll`:

1. **Container `State.StartedAt`** — a brand-new start counts as activity.
2. **`output/heartbeat.touch`** — optional file the runner image MAY touch periodically. The
   adapter reads its last-modified timestamp under the workspace containment guard
   (NOFOLLOW_LINKS); a `mtime` later than the previous observation emits
   `RunnerPollStatus.HeartbeatTouched`.
3. **`logs/runner.stdout` byte-count growth** — when both byte count AND modified time advance
   over the prior observation, the adapter emits `HeartbeatTouched`. The previous observation
   is kept in an in-memory map cleared on `cancel`.

The broker's existing `HeartbeatTouched → executionService.touchActivity` branch advances
`last_activity_at` (and the derived `timeout_at` floor) unchanged.

### Timeout enforcement (AC1)

`RunnerBroker.scanForTimeouts → processSingleTimeout` runs the existing heartbeat-race guard
(Trap T1) FIRST. Only after that guard returns does the docker path issue:

1. `docker stop` with a 10s graceful window (SIGTERM → SIGKILL escalation by the engine).
2. If post-stop inspection still shows `running | paused | restarting`, `docker kill`
   (immediate SIGKILL).

The row flips to `timed_out` via `executionService.recordTimedOut`, and a dedicated
`runner.timeout` event is appended (replaces the legacy `runner.failed + details.failureCategory
= runner_timeout` emission on the docker path). Mock path is unaffected.

### Recovery probe on broker restart (AC4)

`RunnerBroker.recoverOnStartup → processOrphan` consults the runner adapter via the new
`RecoverableRunnerAdapter` sub-interface (OQ-6). The order (Trap T6):

1. Scratch-replay FIRST (mock-runner happy-path path; story 1.13 backward compatibility).
2. Docker probe SECOND via `recoverHandle(rex)` — uses `docker ps -a --filter label=...` to
   reconstruct the container id, then runs the full `poll` classification. `Running` resumes
   monitoring; `Completed` runs the existing `onResult` ingest path; `Failed(RUNNER_CRASH)`
   marks the row failed; `Unknown`/empty falls through.
3. Orphan flip LAST — `executionService.recordOrphaned` + `runner.orphaned` event +
   `recovery.reconciled` (kept complementary per AC8 Trap T10).

### Stale-execution scan (AC3)

`RunnerBroker.scanForStaleExecutions` (new, scheduled at
`deliveryline.runner.stale-scan-interval-ms`, default 60s) runs two sub-passes per stage:

1. **Heartbeat-stale WARN** for rows past `1 × stage_timeout` but inside `2 × stage_timeout`.
   Idempotent via the new `heartbeat_stale_emitted_at` column (V10 migration) — the column is
   set when `runner.heartbeatStale` is appended and cleared on any subsequent activity /
   terminal transition.
2. **Orphan flip** for rows past `2 × stage_timeout`. Appends `runner.orphaned` with
   `details.failureCategory = "orphan"` and `details.reason = "lease_expired"`. Does NOT drive
   workflow state — Epic 4 recovery decides reconcile vs. fail-forward (AC3 sub-bullet (c)).

### Container labels (AC7)

Story 3.1 establishes four labels; story 3.2 adds a fifth:

```
deliveryline.runnerExecutionId   # rex_*
deliveryline.workflowRunId       # run_*
deliveryline.runnerKind          # codex|claude
deliveryline.dispatchedAt        # ISO-8601 UTC
deliveryline.stage               # investigation|execution   ← story 3.2
```

The label namespace is the only sanctioned correlation surface (Trap T8). The cleanup +
recovery code extracts `runnerExecutionId` from labels, never from container name.

### Event family extension (AC8)

`WorkflowEventType` gains five new values registered in `RegistryContractTest`,
`workflow-events-response.schema.json`, and `workflow-history.v1.schema.json`:

| Wire form | Emit point | Purpose |
| --------- | ---------- | ------- |
| `runner.dispatched` | `RunnerBroker.dispatch` (docker path only) | Replaces `runner.started` on the docker path — `details.runnerKind`, `containerId`, `image`, `dispatchedAt` |
| `runner.heartbeatStale` | `scanForStaleExecutions` heartbeat sub-pass | WARN-level observability; at-most-once per stale-window per row |
| `runner.timeout` | `processSingleTimeout` | Replaces `runner.failed + details.failureCategory=runner_timeout` on the docker path |
| `runner.orphaned` | `scanForStaleExecutions` orphan sub-pass + `recoverOnStartup` orphan branch | `details.reason = "lease_expired" \| "broker_restart_orphan"` |
| `runner.completed` | `RunnerBroker.onResult` happy-path success | Closes the audit-trail gap for successful completions |

Mock path keeps emitting `runner.started` (legacy) — see OQ-4. Backend frontends consume both
old and new shapes via the regenerated `schema.d.ts`.

### WSL2 Ubuntu local-CI parity smoke

Before pushing, run the docker-tagged IT tier natively on WSL2 Ubuntu per memory
`wsl-linux-ci-reproduction.md`. Windows-vs-Linux `docker stop`/`docker kill` signal
propagation differs subtly — the `timed_out` flip relies on `state.exitCode()` being non-null
after the kill. Test images that ignore `SIGTERM` (e.g., `trap '' TERM; sleep 3600`) deliberately
exercise the kill fallback path. The tier needs `alpine:3.20` pre-pulled (`docker pull
alpine:3.20`) — the adapter uses a raw DockerClient that does not auto-pull.

> **`docker stop` semantics (story 3.2a):** `docker stop -t N` sends SIGTERM then SIGKILLs after
> the grace, so `DockerRunnerAdapter.terminate` typically observes the container already exited and
> reports `STOPPED_GRACEFULLY`; `KILLED_AFTER_GRACE` only arises when the `docker stop` call itself
> fails to stop the container. Both outcomes are written verbatim into the `runner.timeout` event.

## Story 3.2a hardening notes

### Recovery lease re-arm (documented AC4 deviation, D2)

`RunnerBroker.processOrphan` re-arms the lease on a container recovered as **Running** after a
broker restart: it advances `last_activity_at` to `now()` (`executionService.touchActivity`).
Story 3.2 AC4 literally says "no row change" for a recovered-running container, but a long broker
outage may leave the pre-crash `timeout_at` already elapsed — without the re-arm the very next
`scanForTimeouts` would immediately kill a genuinely-alive container. A future-dated engine
`StartedAt` / FS mtime is clamped to the broker clock so the deadline is never over-extended.
`DockerRunnerAdapter.recoverHandle` also re-seeds its in-process log-growth observation floor at
recovery time so a stale `StartedAt` cannot re-emit a spurious heartbeat after a restart.

### Dangling-container sweep — min-age guard + two-pass removal (AC4)

`RunnerWorkspaceCleanupJob.sweepDanglingContainers`:

- **Min-age guard** (`deliveryline.runner.docker.dangling-container-min-age-seconds`, default
  `120`, `0` disables): a labelled container with **no** `runner_executions` row is preserved
  while it is younger than the window — it may be in the dispatch→row-insert gap, so destroying it
  would kill a just-launching runner. The guard applies only to rowless containers; a container
  whose row exists and is terminal is genuinely dangling and removed regardless of age.
- **Normalized status**: `DefaultDockerEngineGateway.listContainersByLabel` populates
  `DanglingContainerInfo.status()` from the engine **state** (`running|exited|created|paused|dead|
  restarting`, lower-cased), never the human `getStatus()` ("Up 3 minutes"), so the sweep's
  running-match is reliable.
- **Two-pass removal**: a still-running dangling container is `docker stop`ped (10s grace) then
  removed with `force=true`, avoiding the stop→rm 409 race; an already-exited container is removed
  with `force=false`.
- A `rex_`-prefixed but malformed label value (not a valid public id) is preserved (logged
  `reason=invalid_id`) rather than destroyed — mirrors the orphan-workspace-dir Trap-T7 posture.
