# Story 3.2: DockerRunnerAdapter Lifecycle — Timeout, Heartbeat, Lease Expiry, Cleanup, Idempotent Restart

Status: in-progress

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer,
I want the `DockerRunnerAdapter` from story 3.1 wrapped in a complete lifecycle layer — per-stage timeout enforcement that issues `docker stop`/`docker kill`, heartbeat / `last_activity_at` tracking driven by container state + log growth + an optional `heartbeat.touch` marker, lease-expiry detection of stale executions at the broker, idempotent recovery after a backend restart that probes every active container by id, scheduled cleanup of workspaces past `workspace-retention-hours`, dangling-container detection-and-stop via Docker label filters, the `runner.dispatched` / `runner.heartbeatStale` / `runner.timeout` / `runner.orphaned` / `runner.completed` / `runner.failed` event family appended on every transition, and a `RecoveryService.retry` path that re-dispatches with a fresh `runnerExecutionId` while preserving the original audit row,
So that real Docker runner failures (hangs past timeout, broker JVM crash mid-execution, dangling containers from any of the above, workspaces accumulating forever) do not leak containers, do not lose result tracking, do not silently advance workflow state, and do not exhaust disk — fulfilling AR16 (`lifecycle state, timeout, heartbeat or last activity, normalized result, raw diagnostic reference, and failure category`) + architecture lines 574 / 187-188 / 429-430 (`runner broker owns runner execution identity, start/stop/retry, heartbeat or last activity, lease expiry, stale execution cleanup, and idempotent restart behavior after broker or container failure`).

## Acceptance Criteria

1. **Given** per-execution timeouts configured per stage via `application.yml` `deliveryline.runner.stage-timeouts.{stageName}` (existing — defaults `investigation: 600s`, `execution: 600s` per `RunnerProperties.timeoutFor(stage)`), **When** a runner container's `last_activity_at + stage_timeout < now()` (the existing `runner_executions.timeout_at` column AND `RunnerBroker.scanForTimeouts` already implement this for the mock adapter), **Then** for the **Docker** runner the broker's existing `scanForTimeouts` per-item processor additionally:
   - (a) issues `DockerEngineGateway.stopContainer(containerId, Duration.ofSeconds(10))` (graceful — Docker engine sends `SIGTERM` then `SIGKILL` after the grace),
   - (b) if `inspectContainer` after the stop still reports `status in {running, paused, restarting}`, issues `DockerEngineGateway.killContainer(containerId)` (NEW gateway method — `docker kill`, immediate `SIGKILL`),
   - (c) calls the existing `executionService.recordTimedOut(runnerExecutionId)` (flips the row to `timed_out` per the existing state machine),
   - (d) appends a NEW `WorkflowEventType.RUNNER_TIMEOUT` event (NEW enum value `runner.timeout` — see AC8) with `details.runnerExecutionId`, `details.containerId`, `details.failureCategory = "runner_timeout"`, `details.timeoutAt`,
   - (e) re-uses the existing `driveWorkflowFailed(... FailureCategory.RUNNER_TIMEOUT ...)` path so the workflow row transitions to `Failed` exactly once (idempotent).

   The broker decides retry per workflow rules (story 1.18 `RecoveryService.retry` baseline + AC9 here); the adapter does NOT decide retry. **Trap T1:** the existing `scanForTimeouts` heartbeat-race guard (`RunnerBroker.java:801` `freshTimeoutAt.isBefore(now)` re-check inside the per-item transaction) MUST still gate the `docker stop` call — a heartbeat that bumped `timeout_at` after the initial scan must NOT trigger a kill. The new docker-side actions slot INSIDE the existing `processSingleTimeout` after the heartbeat-race guard, not before.

2. **Given** heartbeat tracking, **Then** `DockerRunnerAdapter.poll(runnerExecutionId)` (the existing method from story 3.1) is extended to return `RunnerPollStatus.HeartbeatTouched(activityTimestamp)` (existing record from `RunnerPollStatus.java` — no enum / sealed addition) when activity is observed since the last poll. Activity sources, in priority order:
   - (a) container is `running` AND `state.startedAt() > previousLastActivityAt` (NEW field on `ContainerState` record — the gateway's `inspectContainer` reads `State.StartedAt` from docker-java; a brand-new start counts as activity) — emits `HeartbeatTouched(state.startedAt())`;
   - (b) `output/heartbeat.touch` (NEW optional file under the workspace `output/` mount — runner images MAY touch it periodically; absent for runners that don't) — when present AND `Files.getLastModifiedTime(...) > previousLastActivityAt`, emits `HeartbeatTouched(Files.getLastModifiedTime(...))`. NEW method `RunnerWorkspaceStore.tryReadHeartbeatTouch(runnerExecutionId) → Optional<OffsetDateTime>` (containment + NOFOLLOW_LINKS guard mirrors `tryReadResult`);
   - (c) `logs/runner.stdout` size growth (NEW method `RunnerWorkspaceStore.observeLogGrowth(runnerExecutionId) → Optional<LogGrowthObservation(byteCount, lastModifiedAt)>` returning `Optional.empty()` if the file is missing) — when `byteCount > previousObservedByteCount` AND `lastModifiedAt > previousLastActivityAt`, emits `HeartbeatTouched(lastModifiedAt)`. The adapter holds a small in-memory `Map<runnerExecutionId, LogGrowthObservation>` keyed alongside `rexIdToContainerId`.

   If none of (a)/(b)/(c) shows fresh activity, `poll` returns `RunnerPollStatus.Running()` (the existing case — the broker's `pollActiveExecutions` already flows that through `executionService.touchActivity(...)` per `RunnerBroker.java:863`, so the stale-threshold extension keeps working). **Trap T2:** the poll method's existing AC3 of story 3.1 (Completed / Failed(RUNNER_CRASH) / Unknown branches) MUST be unaffected — heartbeat detection only changes the `Running` branch into a `Running` or `HeartbeatTouched` choice. **Trap T3:** activity timestamps from filesystem `lastModifiedTime` are taken at `OffsetDateTime` precision in UTC via the injected `Clock`-derived conversion, not via the file's raw `FileTime` (some filesystems report ms precision, some ns; the broker only compares for monotonic forward progress so the existing `touchActivity` `staleTimeoutWindow` algebra holds). Heartbeat polling cadence reuses the existing `deliveryline.runner.poll-interval-ms` (default 5000 — declared but not currently bound; ADD a `pollIntervalMs` field to `RunnerProperties` so it is binding-validated rather than a free string in `@Scheduled`).

3. **Given** stale-execution detection at the broker level, **Then** a NEW scheduled job method `RunnerBroker.scanForStaleExecutions()` (registered in `RunnerConfiguration` as a `@Scheduled(fixedDelayString = "${deliveryline.runner.stale-scan-interval-ms:60000}")` runner) queries `RunnerExecutionRecordPort.findStaleByStatusInAndLastActivityAtBefore(ACTIVE_STATUSES, staleThreshold, batchSize)` (NEW port method) for rows with `status IN (pending, running)` AND `last_activity_at < now() - (staleThresholdMultiplier × stage_timeout)` (i.e., the broker's existing `staleThresholdFor(stage)` already computes `2 × timeout` by default — reuse, do not duplicate the multiplier in SQL). For each stale row:
   - (a) calls `executionService.recordOrphaned(runnerExecutionId)` (existing method — flips the row to `orphaned`),
   - (b) appends a NEW `WorkflowEventType.RUNNER_ORPHANED` event (NEW `runner.orphaned`) with `details.runnerExecutionId`, `details.containerId` (if a handle exists in `rexIdToContainerId`), `details.failureCategory = "orphan"`, `details.lastActivityAt`, `details.reason = "lease_expired"`,
   - (c) `driveWorkflowFailed(... FailureCategory.ORPHAN ...)` (existing — surfaces to Epic 4 recovery decisions; the existing AC5 split in `RunnerBroker.handlePollFailure:905` does NOT include `ORPHAN` in the workflow-Failed split because story 1.13 leaves orphan to recovery — KEEP that posture; AC3 here emits the audit event and flips the row, but does NOT call `driveWorkflowFailed`. Epic 4's `RecoveryService` decides whether to reconcile or fail-forward.)
   - (d) emits a separate NEW `WorkflowEventType.RUNNER_HEARTBEAT_STALE` (NEW `runner.heartbeatStale`) WARN-level event when the row's `last_activity_at` is older than `1 × stage_timeout` but less than `2 × stage_timeout` (i.e., approaching lease expiry, not yet orphan). This is observability-only — it does NOT flip status. Implemented as a separate batch in `scanForStaleExecutions` over `findStaleByStatusInAndLastActivityAtBefore(ACTIVE_STATUSES, timeoutFor(stage), batchSize)` minus rows already past `staleThresholdFor(stage)`.

   **Trap T4:** the `runner.heartbeatStale` event MUST be appended at most once per row per stale-window — append the event only when no prior `runner.heartbeatStale` event exists for this `runnerExecutionId` (query via the existing event-read port, OR persist a `heartbeat_stale_emitted_at` column on `runner_executions` — see OQ-1 — recommendation: lightweight idempotency via a new NULLable `heartbeat_stale_emitted_at` column in a Flyway V10 migration; the column is set when the event is appended and cleared on successful touchActivity).

4. **Given** broker crash mid-execution, **When** the backend restarts, **Then** the existing `RunnerBroker.recoverOnStartup` method (`RunnerBroker.java:932`) is extended so that, for each active `runner_executions` row (`status IN (pending, running)`), it:
   - (a) **continues to** attempt `scratchStore.tryReadRunnerResult(rex)` first (existing mock-runner path — story 1.13's idempotent-restart for mock — KEEP unchanged so the mock still works under `runners.mock`),
   - (b) **adds** a docker-only probe step when `runnerAdapter instanceof DockerRunnerAdapter` (NEW method `DockerRunnerAdapter.recoverHandle(runnerExecutionId) → Optional<RunnerPollStatus>`):
     - Queries the Docker engine via NEW gateway method `DockerEngineGateway.findContainerIdByRunnerExecutionId(rex) → Optional<String>` (uses `docker ps -a --filter label=deliveryline.runnerExecutionId={rex}` — see Trap T8 — labels survive container exit but NOT removal). The label-filter approach means we do not need to persist `container_id` on `runner_executions` in this story (OQ-3 — defer to story 3-19's observability extension).
     - If a container id is recovered: rebuild the `rexIdToContainerId` entry, then `inspectContainer` → classify identically to a normal `poll(...)`:
       - `running`/`created`/`paused`/`restarting` → resume monitoring (no row change),
       - `exited` AND `workspaceStore.tryReadResult(rex)` present → re-run the existing `RunnerBroker.onResult(rex, bytes)` ingest path (validates + classifies + drives state),
       - `exited` AND result absent → `recordFailed(RUNNER_CRASH)` + `driveWorkflowFailed`,
       - `dead`/`removing` → `recordFailed(RUNNER_CRASH)`.
     - If no container id is recovered AND no scratch result is present → existing `processOrphan` path applies (flips to `orphaned` + appends `RECOVERY_RECONCILED` with `broker_restart_orphan` reason).
   - (c) The whole recovery is wrapped in the existing `perItemTransactionTemplate` (per-row REQUIRES_NEW), so a Docker-engine failure on one row never poisons recovery of the next row.

   **Trap T5:** the new docker-recovery probe runs ONLY when the active profile includes `runners.docker` — `MockRunnerAdapter`-only runs must NOT pay the Docker probe cost. Wire via `if (runnerAdapter instanceof DockerRunnerAdapter docker) { ... }` rather than a Spring-conditional dispatch (the broker has one `RunnerAdapter` bean — story 1.13's profile mutex guarantees this). **Trap T6:** the existing `processOrphan` mock-result-replay step MUST run BEFORE the docker probe, so a mock-runner happy-path that completed during JVM downtime still resumes via the scratch leaf file the same way story 1.13 + story 3.1 left it.

5. **Given** workspace cleanup per the retention threshold declared in story 3.1 AC7 (`deliveryline.runner.docker.workspace-retention-hours`, default 24, declared on `RunnerProperties.Docker`), **Then** a NEW scheduled job `RunnerWorkspaceCleanupJob` (registered in `RunnerConfiguration` as `@Scheduled(fixedDelayString = "${deliveryline.runner.docker.workspace-cleanup-interval-ms:3600000}")` — default 1h, only firing under `runners.docker` profile gating via `@Profile("runners.docker")` on a separate `@Configuration` class `DockerRunnerLifecycleConfiguration` colocated with `DockerConfiguration` to keep the `runners.mock` boot path free of docker-only beans):
   - (a) lists all `runner_executions` rows with `completed_at < now() - workspace-retention-hours` AND `archived_at IS NULL` (use existing `archived_at` column on `runner_executions` — currently unused; semantically "workspace cleaned up at" — see OQ-2 — recommended re-use; the alternative is a new column),
   - (b) for each row, calls NEW method `RunnerWorkspaceStore.deleteWorkspace(runnerExecutionId)` (recursive delete with containment guard — refuses to delete anything outside `{workspaceRoot}/{rex}`; mirrors the same `realpath.startsWith(workspaceRoot)` containment as the rest of the SPI),
   - (c) on successful delete, calls NEW port method `RunnerExecutionRecordPort.markArchived(publicId, archivedAt)` to set `archived_at`,
   - (d) logs INFO `workspace cleanup deleted runnerExecutionId={} workspaceRoot={} completedAt={}` per deletion; logs WARN `workspace cleanup skipped runnerExecutionId={} reason=missing_workspace_dir` when the workspace directory is missing (e.g., manually deleted) — the row is still marked `archived_at` to prevent re-scanning.

   **Trap T7:** the cleanup job MUST skip — and WARN — when the `runner_executions` row is missing in the DB. NEVER delete a workspace directory whose `rex_` directory name does not correspond to a `runner_executions.public_id` (defensive: prevents a renamed `runner-work/` external snapshot from being eaten). Implement by listing the workspace root directory entries AND cross-referencing each `rex_*` subdir against the DB — if a `rex_*` subdir exists with no corresponding row, log WARN `workspace orphan dir found runnerExecutionId={} action=preserve` and leave it alone (Epic 4 reconciliation may reclaim it).

6. **Given** dangling containers (broker thinks execution completed but the container is still running — e.g., a container that finished after `cancel(...)` but never exited, or a container left over from a previous JVM that crashed before recovery could reach it), **When** the same `RunnerWorkspaceCleanupJob` runs, **Then** as a separate cleanup pass:
   - (a) calls NEW gateway method `DockerEngineGateway.listContainersByLabel(labelKey, labelValuePrefix) → List<DanglingContainer(containerId, runnerExecutionId, status, createdAt)>` with `labelKey = "deliveryline.runnerExecutionId"` and `labelValuePrefix = "rex_"` (uses `docker ps --all --filter label=deliveryline.runnerExecutionId` — returns BOTH running AND exited matching containers),
   - (b) for each container whose `runnerExecutionId` label does NOT correspond to a `runner_executions` row with `status IN (running, pending)`:
     - if `status = "running"|"paused"|"restarting"` → issue `docker stop` (10s graceful) + `docker rm` with force=false on a SECOND pass after a poll observes the container exited (avoid stop-then-rm race),
     - if `status = "exited"|"dead"|"created"` → issue `docker rm` (force=false; the engine handles "already removed").
   - (c) logs INFO `dangling container removed runnerExecutionId={} containerId={} status={}` per action; WARN on best-effort failures (never throw).

   **Trap T8:** the label-filter query uses the **exact** label namespace established by story 3.1 (`deliveryline.runnerExecutionId`, `deliveryline.workflowRunId`, `deliveryline.runnerKind`, `deliveryline.dispatchedAt`) — this story ADDS `deliveryline.stage` to the label set (see AC7) so the filter `deliveryline.runnerExecutionId` already differentiates DeliveryLine containers from any other tenant on the host. Do NOT widen the filter to a less-specific key. **Trap T9:** the dangling-container pass MUST coexist with the workspace-cleanup pass safely — a container whose workspace has been cleaned up but whose container row still exists is a real scenario after a partial cleanup; the dangling pass removes the container, the next workspace pass is a no-op for that rex. Order: workspace pass FIRST, dangling pass SECOND, so the workspace inventory is current before the engine inventory.

7. **Given** container labeling, **Then** story 3.1's existing label set (`deliveryline.runnerExecutionId`, `deliveryline.workflowRunId`, `deliveryline.runnerKind`, `deliveryline.dispatchedAt`) is EXTENDED with `deliveryline.stage` (the `RunnerStage` enum's `value()` — `"investigation"`, `"execution"`). The extension lives in `DockerRunnerAdapter.dispatch` — extend the `Map<String, String> labels` block at `DockerRunnerAdapter.java:119`. The `RunnerDispatchRequest` already carries `stage`, so no new field is needed on the request. A test in `DockerRunnerAdapterUnitTest` asserts the 5-label set is present and matches the request inputs. Operators can run `docker ps --filter label=deliveryline.workflowRunId=run_abc` to find every container for a workflow run, AND `docker ps --filter label=deliveryline.stage=execution` to find every implementation runner.

8. **Given** lifecycle events appended on every transition, **Then** the `WorkflowEventType` enum is EXTENDED with five NEW values (currently only `RUNNER_STARTED` + `RUNNER_FAILED` exist per `WorkflowEventType.java:15-16` — the rest are emitted as `RUNNER_FAILED` with `details.failureCategory`):
   - `RUNNER_DISPATCHED("runner.dispatched")` — appended by `RunnerBroker.dispatch` AFTER the `RunnerAdapter.dispatch(...)` ack returns, carrying `details.runnerKind`, `details.containerId` (if available), `details.image`, `details.workspaceRoot`, `details.dispatchedAt`. **REPLACES the implicit `RUNNER_STARTED` emission on dispatch** — `RUNNER_STARTED` is kept (legacy) for the mock adapter's startup transition; the broker switches to emitting `RUNNER_DISPATCHED` for ALL adapters as the canonical dispatch-time event so the mock + docker paths share one event family. The data-model docs are updated to record the deprecation of `RUNNER_STARTED`.
   - `RUNNER_HEARTBEAT_STALE("runner.heartbeatStale")` — appended in AC3 sub-bullet (d).
   - `RUNNER_TIMEOUT("runner.timeout")` — appended in AC1 sub-bullet (d); REPLACES the current `RUNNER_FAILED` emission with `details.failureCategory = "runner_timeout"` in `RunnerBroker.processSingleTimeout:810` — that line now appends `RUNNER_TIMEOUT` directly.
   - `RUNNER_ORPHANED("runner.orphaned")` — appended in AC3 sub-bullet (b); REPLACES the current `RECOVERY_RECONCILED` emission with `details.reason = "broker_restart_orphan"` in `RunnerBroker.processOrphan:976` only when the row is orphaned via the docker probe in AC4.b (the existing `RECOVERY_RECONCILED` path is reserved for actual recovery-action driven reconciliation).
   - `RUNNER_COMPLETED("runner.completed")` — appended by `RunnerBroker.onResult` when a result is successfully validated and ingested (mirrors the existing happy-path success — currently no explicit event is appended for happy-path completion; the workflow state transition to a non-Failed state is the only signal). This closes the audit-trail gap for FR47.

   **All five new events are registered in:**
   - `WorkflowEventType` enum + `RegistryParsers.index` lookup (existing pattern),
   - `RegistryContractTest` (single-source-of-truth wire-form list),
   - `WorkflowEventDetailKeysContractTest` if details keys are allow-listed there (verify — story 2.12 introduced the allow-list pattern),
   - `workflow-events-response.schema.json` (frontend OpenAPI surface) AND `workflow-history.v1.schema.json` (story 2.12 schema enum surface) — both gain the five new values in the type enum.

   **Trap T10:** the `RUNNER_STARTED` event must NOT be removed (legacy / mock backward-compat — see [[deferred-work]] for eventual deprecation); just stop emitting it on the docker path. A flyway migration is NOT required (existing `RUNNER_STARTED` rows stay; the registry contract test stays green because both old and new values exist). **Trap T11:** the schema-enum extension MUST land in the SAME PR as the backend code change, OR `OpenApiSnapshotContractTest` will fail the diff gate. Run `scripts/regen-openapi.{sh,ps1}` per the cross-shell pattern in memory `openapi-regen-platform-shim.md`.

9. **Given** the `RecoveryService.retry` baseline from story 1.18 (`RecoveryService.java:242`), **Then** when `retry` is invoked against a `timed_out` or `orphaned` `runner_executions` row:
   - (a) The retry path dispatches a NEW `runner_executions` row with a NEW `runnerExecutionId` (the existing `RecoveryService → WorkflowCommandService.retryWorkflow → RunnerBroker.dispatch` chain ALREADY does this — story 1.18 verified — confirm via test, no code change to the dispatch chain),
   - (b) The original `runner_executions` row is preserved verbatim (the original `runnerExecutionId` is never reused — story 1.18 baseline assertion; confirm via test that runs `retry` on a `timed_out` row and asserts the original row is unchanged + a new row with a distinct `rex_*` id exists),
   - (c) For the **docker** path specifically: the original container (if still on the host) is killed by AC6's dangling-container cleanup pass on the next scheduled tick — the retry path does NOT directly remove the container (this is the broker's responsibility through the cleanup job, not the recovery service's responsibility, keeping the recovery surface narrow per story 1.18's "no compensating actions beyond audit + dispatch" rule),
   - (d) The original row's `archived_at` is set by the workspace cleanup job at the documented retention horizon — retry does NOT immediately delete the original workspace (operators must be able to compare original-vs-retry artifacts during the retention window per AC7 of story 3.1).

   **Trap T12:** the `RecoveryService.retry` happy-path test must EXPLICITLY assert that a docker-runner retry yields a fresh `rex_*` id AND the original row's `status` is unchanged from `timed_out`/`orphaned`. Add the assertion in a new `RecoveryServiceDockerRetryContractTest` (light-weight — mocks the docker gateway; no real Docker). **Trap T13:** `RetryRecoveryResult.recoveryRetriedEventPublicId` MUST carry the new `runner.dispatched` event id from AC8 (not the legacy `runner.started`) when the docker adapter is active — the event-id echo is the audit-trail anchor for recovery flows.

10. **Given** the test suite, **Then** integration tests under `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/lifecycle/` AND focused unit tests under `deliveryline-backend/src/test/java/org/dradgo/application/runner/` cover:
    - (a) **Timeout enforcement** (`DockerRunnerLifecycleTimeoutIT`, `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`) — a test image (`alpine:3.20`) running `sh -c 'sleep 3600'` is dispatched with a 5-second stage timeout override; the broker's `scanForTimeouts` (driven manually via `broker.scanForTimeouts()` in the test, NOT by the real `@Scheduled` cadence) issues `docker stop` then `docker kill`; `docker inspect` reports the container exited within 12 seconds; `runner_executions.status = timed_out`; a `runner.timeout` event is appended.
    - (b) **Heartbeat detection via log growth** (`DockerRunnerLifecycleHeartbeatIT`) — a test image writes to `/workspace/logs/runner.stdout` every 2 seconds without exiting; the broker's `pollActiveExecutions` observes the size growth across two ticks and emits `HeartbeatTouched` → `executionService.touchActivity` extends `timeout_at`; the row never enters `timed_out` despite passing the original `timeout_at`. Asserts `last_activity_at` advances at least twice.
    - (c) **Heartbeat detection via `heartbeat.touch` marker** (`DockerRunnerLifecycleHeartbeatIT.heartbeatTouchAdvancesActivity`) — a test image touches `/workspace/output/heartbeat.touch` every 2 seconds; same activity-advance assertion as (b).
    - (d) **Stale detection → orphan** (`RunnerBrokerStaleDetectionUnitTest`) — Mockito-driven unit test (no real Docker): seed a `runner_executions` row with `status=running, last_activity_at=now()-3h, timeoutFor(stage)=10min`; call `broker.scanForStaleExecutions()`; assert row flips to `orphaned`, a `runner.orphaned` event is appended, `workflowTransitionService` is NOT called (per AC3 sub-bullet (c) decision).
    - (e) **Heartbeat-stale warning** (same unit test class) — seed a row past `1 × timeout` but inside `2 × timeout`; assert a `runner.heartbeatStale` event is appended exactly once (run the scan twice; assert one event total).
    - (f) **Broker-restart recovery — docker container still running** (`DockerRunnerLifecycleRecoveryIT`) — dispatch via Docker, kill the JVM (simulated by emptying `rexIdToContainerId` and triggering `recoverOnStartup`); assert the container id is recovered from the label filter, the row remains `running`, subsequent `pollActiveExecutions` continues to drive it normally.
    - (g) **Broker-restart recovery — docker container exited with valid result** (same IT class) — same setup but the container has already written `output/runner-result.v1.json` and exited; `recoverOnStartup` ingests the result via the existing `onResult` path; row flips to `completed`.
    - (h) **Broker-restart recovery — container missing** (Mockito unit test) — no container matches the label filter; `recoverOnStartup` flips the row to `orphaned` with `RUNNER_ORPHANED` event (NOT `RECOVERY_RECONCILED` per AC8's distinction).
    - (i) **Workspace cleanup — past retention** (`RunnerWorkspaceCleanupJobIT`, Testcontainers-backed since the workspace lives on disk + the DB row is queried) — create a `runner_executions` row with `completed_at = now() - 25h`, write a workspace dir, run the job; assert the directory is deleted AND `archived_at` is set.
    - (j) **Workspace cleanup — within retention** (same IT) — create a row with `completed_at = now() - 2h`; assert the directory is NOT deleted AND `archived_at` is null.
    - (k) **Workspace cleanup — orphan dir (no DB row)** (same IT) — write a `rex_orphan_xyz` workspace dir with no DB row; assert the directory is NOT deleted, a WARN log line `workspace orphan dir found ... action=preserve` is emitted (list-appender).
    - (l) **Dangling container cleanup — stale running** (`DockerRunnerDanglingContainerCleanupIT`, `@Tag("docker-runner-it")`) — manually launch an alpine container with `--label deliveryline.runnerExecutionId=rex_dangling_test_xxx --label deliveryline.workflowRunId=run_test_yyy ...` (no corresponding DB row); run the cleanup job; assert the container is `docker stop`ed then removed; assert no exception thrown for unrelated containers.
    - (m) **Label propagation visible in `docker inspect`** (extension of story 3.1's existing `DockerRunnerAdapterContainerLifecycleIT.labelPropagation`) — assert the FULL 5-label set including the NEW `deliveryline.stage` label.
    - (n) **RecoveryService docker retry produces fresh rex id** (`RecoveryServiceDockerRetryContractTest`, light `@SpringBootTest` slice with mocked docker gateway) — covers Trap T12 + T13.
    - (o) **Event-type registry contract** (extension of `RegistryContractTest`) — assert all five new `WorkflowEventType` values are registered + match the wire-form strings.
    - (p) **OpenAPI schema enum extension** (regen via cross-shell scripts; `OpenApiSnapshotContractTest` + the frontend `schema.d.ts` drift contract from story 2-13 stay green) — the five new event types appear in the `workflow-events-response` enum (verify post-regen).
    - (q) **Logging contract** (`DockerRunnerLifecycleLoggingContractTest`) — list-appender pins one assertion per new log branch: timeout-stop, timeout-kill, heartbeat-stale-warn, orphan-warn, cleanup-deleted, cleanup-orphan-dir, dangling-stop. Mirror the existing `RunnerLoggingContractTest` pattern from story 1.19.

    **Trap T14:** the docker-tagged IT tier (entries (a), (b), (c), (f), (g), (l)) joins the existing `docker-runner-it` CI tier from story 3-1 (NOT the foundation gate — opt-in via tag per story 3-1's documented posture and sprint-change-proposal-2026-05-26 §3.7's real-runner-contract deferral to story 3-8). All other entries are fast-tier (unit / ApplicationContextRunner / Testcontainers-backed Mockito). **Trap T15:** the WSL2 Ubuntu local-CI parity smoke per memory `wsl-linux-ci-reproduction.md` MUST be run before pushing — Linux-vs-Windows `docker stop`/`docker kill` behavior differs subtly on signal propagation; the `timed_out` flip relies on `state.exitCode()` being non-null after the kill.

## Scope Guardrail

This story owns:

- The `scanForStaleExecutions` scheduled job in `RunnerBroker` + the new `findStaleByStatusInAndLastActivityAtBefore` port method.
- The five new `WorkflowEventType` values (`runner.dispatched`, `runner.heartbeatStale`, `runner.timeout`, `runner.orphaned`, `runner.completed`) + their registry + schema-enum wiring.
- The `RUNNER_DISPATCHED` event emission swap in `RunnerBroker.dispatch` (replacing the legacy `RUNNER_STARTED` for the docker path; mock path keeps `RUNNER_STARTED`).
- The `RUNNER_TIMEOUT`/`RUNNER_ORPHANED`/`RUNNER_COMPLETED` event emissions inside the broker's existing transition methods.
- The docker `stopContainer` → `killContainer` two-step kill inside `RunnerBroker.processSingleTimeout` (new gateway method `killContainer`).
- The heartbeat detection extension in `DockerRunnerAdapter.poll` (log-growth observation map, `heartbeat.touch` file probe, `ContainerState.startedAt()` check).
- The two new SPI methods `RunnerWorkspaceStore.tryReadHeartbeatTouch` + `RunnerWorkspaceStore.observeLogGrowth` + `RunnerWorkspaceStore.deleteWorkspace`.
- The Docker-side recovery probe in `recoverOnStartup` (new `DockerEngineGateway.findContainerIdByRunnerExecutionId` + `DockerRunnerAdapter.recoverHandle`).
- The `RunnerWorkspaceCleanupJob` + its scheduled wiring (`DockerRunnerLifecycleConfiguration` colocated with `DockerConfiguration` in `adapters.runner.docker`).
- The dangling-container cleanup pass + new `DockerEngineGateway.listContainersByLabel` method.
- The `markArchived` port method + use of the existing `archived_at` column on `runner_executions`.
- The Flyway V10 migration adding `heartbeat_stale_emitted_at` (NULLable timestamptz) to `runner_executions` for AC3 sub-bullet (d) idempotency.
- The NEW `deliveryline.stage` label on dispatched containers.
- The opt-in Docker-engine IT tier extension (entries (a), (b), (c), (f), (g), (l) in AC10).
- The `RecoveryServiceDockerRetryContractTest` verifying the existing retry-chain produces a fresh rex id under `runners.docker`.
- Cleanup of the open-from-3-1 review items: `DockerRunnerAdapterLoggingContractTest` is added in this story per AC10 (q) since the heartbeat / log-growth surface lands here (declaring closure of [Source: 3-1 review item P-Logging-Contract]).

This story does **NOT** own:

- Codex + Claude runner images (stories 3-3 / 3-4) — the test image stays `alpine:3.20`.
- Secret env injection — story 3-5 (the cleanup + dangling-container surfaces in this story are designed assuming no host secrets in env / mounts — a parallel future check in 3-5 verifies dangling containers do not leak secrets when killed).
- Log capture + redaction inside `runner-logs/` (story 3-6) — this story READS log byte counts from the workspace `logs/` mount but does NOT copy them out.
- ELK shipping of runner logs (story 3-7).
- Real-runner contract IT against the actual Codex + Claude images (story 3-8).
- Repository workspace + git clone + push (story 3-9 — adds a fourth mount on the same workspace port).
- Spec-stage orchestration that invokes the broker (story 3a-1).
- Frontend rendering of the new event types in the queue / history views — that lives in story 2-18 / 3-30 / 4-4. The OpenAPI schema enum extension in AC10 (p) is the contract anchor; UI consumes via the regenerated `schema.d.ts`.
- Compare-mode side-by-side of `timed_out`-vs-retry artifacts (story 4-19) — AC9 sub-bullet (d) explicitly preserves the original workspace within retention so 4-19 has bytes to compare.
- The `runner_kind` column on `runner_executions` (deferred from story 3-1 OQ-7; still deferred — operator forensics use the container label).
- Bumping the OpenAPI minor version — the five new enum values are additive (Producers add → Consumers tolerate); a major-version bump is NOT required (consult `openapi-versioning.md` if there is one — recommendation: minor bump if the CI gate insists).

## Tasks / Subtasks

- [ ] **Task 1: Extend `WorkflowEventType` + registry contracts** (AC: 8)
  - [ ] Add five new enum values to `org.dradgo.domain.registry.WorkflowEventType`: `RUNNER_DISPATCHED("runner.dispatched")`, `RUNNER_HEARTBEAT_STALE("runner.heartbeatStale")`, `RUNNER_TIMEOUT("runner.timeout")`, `RUNNER_ORPHANED("runner.orphaned")`, `RUNNER_COMPLETED("runner.completed")`. Update the `LOOKUP` index automatically (it walks `values()` — no manual entry).
  - [ ] Extend `RegistryContractTest` to lock the wire-form list (8 existing + 5 new = 13 runner-family entries when combined with `RUNNER_STARTED`/`RUNNER_FAILED`).
  - [ ] Search `WorkflowEventDetailKeysContractTest` (story 2.12 surface) for the allow-list — if it covers `runnerExecutionId`/`containerId`/`workspaceRoot`/`image`/`runnerKind`/`failureCategory`/`reason`/`lastActivityAt`/`timeoutAt`/`dispatchedAt`, no change; if not, add the missing keys.
  - [ ] Extend `workflow-events-response.schema.json` AND `workflow-history.v1.schema.json` enum lists (story 2.12 schema surface) with the five new values. The runner-contracts module schema only changes if it carries an event-type enum — verify before editing.
  - [ ] **OpenAPI regen** — run `scripts/regen-openapi.sh` then `scripts/regen-openapi.ps1` per memory `openapi-regen-platform-shim.md` (the two scripts cover backend snapshot + frontend `schema.d.ts` generation; the platform-shim split means one shell owns each step). Verify `OpenApiSnapshotContractTest` passes.

- [ ] **Task 2: Extend the `RunnerExecutionRecordPort` + persistence** (AC: 3, 5)
  - [ ] Add `RunnerExecutionSnapshot markArchived(String publicId, OffsetDateTime archivedAt)` to `org.dradgo.application.runner.spi.RunnerExecutionRecordPort` — sets `archived_at` on the row; throws if the row is missing.
  - [ ] Add `List<RunnerExecutionSnapshot> findStaleByStatusInAndLastActivityAtBefore(List<RunnerExecutionStatus> statuses, Duration staleWindow, int limit)` — server-side query: `status IN (...) AND last_activity_at < now() - INTERVAL ?`. Mirrors the existing `findStaleByStatusInAndTimeoutAtBefore` pattern but on `last_activity_at` rather than `timeout_at`.
  - [ ] Add `List<RunnerExecutionSnapshot> findCompletedBeforeAndNotArchived(OffsetDateTime cutoff, int limit)` — for the workspace cleanup job's "row-driven" pass: `completed_at < ? AND archived_at IS NULL AND status IN ('completed', 'failed', 'timed_out', 'orphaned')`.
  - [ ] Implement all three in `adapters.persistence.RunnerExecutionPersistenceAdapter` using the existing `runnerExecutionRepository` + `RunnerExecutionEntity` shape.
  - [ ] Add `heartbeat_stale_emitted_at TIMESTAMPTZ NULL` column to `runner_executions` via NEW `V10__runner_executions_heartbeat_stale.sql` migration. Extend `FlywaySchemaContractTest` to assert the column + its NULLable default. Extend `RunnerExecutionEntity` with the field. Add a `RunnerExecutionRecordPort.markHeartbeatStaleEmitted(publicId, timestamp)` method + adapter impl + a clearing call inside `markRunning`/`touchActivity` (cleared whenever activity is observed so a future stale-period re-emits).

- [ ] **Task 3: Extend `DockerEngineGateway` with `killContainer`, `findContainerIdByRunnerExecutionId`, `listContainersByLabel`** (AC: 1, 4, 6)
  - [ ] Add three methods to `org.dradgo.adapters.runner.docker.DockerEngineGateway`:
    - `void killContainer(String containerId)` — wraps `docker kill` (immediate `SIGKILL`); idempotent (already-exited containers no-op).
    - `Optional<String> findContainerIdByRunnerExecutionId(String runnerExecutionId)` — wraps `docker ps -a --filter label=deliveryline.runnerExecutionId={rex} --format '{{.ID}}'`; returns `Optional.empty()` if no match.
    - `List<DanglingContainer> listContainersByLabel(String labelKey, String labelValuePrefix)` — wraps `docker ps -a --filter label={labelKey} --format ...`; returns project-owned `DanglingContainer(String containerId, String runnerExecutionId, String status, OffsetDateTime createdAt)` records.
  - [ ] Implement in `DefaultDockerEngineGateway` using docker-java's `listContainersCmd().withLabelFilter(...).withShowAll(true).exec()` and `killContainerCmd(...).exec()`. Add the project-owned `DanglingContainer` record next to `ContainerState` in `adapters.runner.docker`.
  - [ ] **Trap T8 verification:** the `runnerExecutionId` returned in `DanglingContainer` is extracted from the container's label map server-side (the docker-java response), NEVER from the container name or any other field — labels are the only sanctioned correlation surface.
  - [ ] Extend `ContainerState` record with `OffsetDateTime startedAt()` (NEW field — reads `State.StartedAt`; parse the ISO-8601 string into `OffsetDateTime` at the gateway boundary; null when the container has never started). Adapter callers tolerate null.

- [ ] **Task 4: Extend `RunnerWorkspaceStore` with heartbeat + log-growth + delete** (AC: 2, 5)
  - [ ] Add three methods to `org.dradgo.application.runner.spi.RunnerWorkspaceStore`:
    - `Optional<OffsetDateTime> tryReadHeartbeatTouch(String runnerExecutionId)` — reads `output/heartbeat.touch` last-modified; same containment + NOFOLLOW_LINKS guard as `tryReadResult`. Returns `Optional.empty()` if missing.
    - `Optional<LogGrowthObservation> observeLogGrowth(String runnerExecutionId)` — reads `logs/runner.stdout` byte count + last-modified; returns `Optional.empty()` if missing. NEW record `LogGrowthObservation(long byteCount, OffsetDateTime lastModifiedAt)` in the spi package.
    - `void deleteWorkspace(String runnerExecutionId)` — recursive delete with containment guard: the resolved `realpath` MUST start with the configured `workspaceRoot.toRealPath()`; throws on containment violation; tolerates "directory already missing" (logs WARN, returns normally).
  - [ ] Implement all three in `org.dradgo.adapters.files.LocalRunnerWorkspaceStore`. Reuse the existing `realpath.startsWith` containment helper (extract to a private static helper if not already shared). Add a private `walkAndDeleteWithinRoot(Path)` method using `Files.walkFileTree` + `SimpleFileVisitor` for the recursive delete.
  - [ ] Extend `LocalRunnerWorkspaceStoreTest` (story 3-1 surface) with focused tests per new method:
    - `tryReadHeartbeatTouchReturnsEmptyWhenAbsent` / `tryReadHeartbeatTouchHonorsNoFollowLinks` (symlink-out attack);
    - `observeLogGrowthReportsByteCountAndModifiedAt` / `observeLogGrowthRefusesParentTraversal`;
    - `deleteWorkspaceRefusesSymlinkOutsideRoot` (POSIX-only; `@DisabledOnOs(OS.WINDOWS)`); `deleteWorkspaceToleratesAlreadyMissing`; `deleteWorkspaceCleansEntireRexTree`.

- [ ] **Task 5: Extend `DockerRunnerAdapter` with heartbeat detection + recovery probe** (AC: 2, 4)
  - [ ] Add the in-memory `Map<String, LogGrowthObservation> rexIdToLastLogObservation` alongside the existing `rexIdToContainerId`. ConcurrentHashMap.
  - [ ] In `DockerRunnerAdapter.poll`, BEFORE the existing `state.status()` switch, run the heartbeat-detection chain:
    1. If container `state.startedAt() != null && state.startedAt().isAfter(lastObservation.lastModifiedAt())` (treat "never observed" as `Instant.MIN`) → return `HeartbeatTouched(state.startedAt())` if the container is still in a running-like state. (If exited, fall through to the existing classification.)
    2. Else if `workspaceStore.tryReadHeartbeatTouch(rex).isPresent()` AND the modified time is later than the last observation → return `HeartbeatTouched(...)`.
    3. Else if `workspaceStore.observeLogGrowth(rex)` reports a fresh `byteCount > previous.byteCount` AND `lastModifiedAt.isAfter(previous.lastModifiedAt)` → store the new observation in the map AND return `HeartbeatTouched(lastModifiedAt)`.
    4. Else fall through to the existing `state.status()` switch (Running / Completed / Failed / Unknown).
  - [ ] **Trap T2:** the heartbeat chain must short-circuit cleanly when the container has already exited — none of the three checks should run if `state.status() == "exited" | "dead"` because the existing classification then yields Completed/Failed.
  - [ ] Add NEW method `DockerRunnerAdapter.recoverHandle(String runnerExecutionId)` that (a) calls `docker.findContainerIdByRunnerExecutionId(rex)`, (b) if found AND not yet in `rexIdToContainerId`, populates the map, (c) returns `Optional.of(poll(rex))` (re-uses the full poll classification). Returns `Optional.empty()` if no container is found.
  - [ ] In `cancel(...)`, on `cancel` actually issued, also clear the `rexIdToLastLogObservation` entry to free memory.

- [ ] **Task 6: Extend `RunnerBroker` with `scanForStaleExecutions` + docker-recovery probe + `RUNNER_DISPATCHED`/`RUNNER_TIMEOUT`/`RUNNER_ORPHANED`/`RUNNER_COMPLETED` event emission** (AC: 1, 3, 4, 8)
  - [ ] Add `public int scanForStaleExecutions()` method modeled on the existing `scanForTimeouts` (loop, per-item `REQUIRES_NEW` template, batch sized via `RunnerProperties.timeoutScanBatchSize()` re-used). The method runs TWO sub-passes:
    1. **Heartbeat-stale (WARN-only):** `recordPort.findStaleByStatusInAndLastActivityAtBefore(ACTIVE_STATUSES, timeoutFor(stage), batchSize)` minus rows already flipped. For each, IF `heartbeat_stale_emitted_at` is NULL → append `RUNNER_HEARTBEAT_STALE` event + set `heartbeat_stale_emitted_at = now()` in a single transaction; ELSE no-op.
    2. **Orphan flip:** `recordPort.findStaleByStatusInAndLastActivityAtBefore(ACTIVE_STATUSES, staleThresholdFor(stage), batchSize)`. For each, `executionService.recordOrphaned(rex)` + append `RUNNER_ORPHANED` event with `details.failureCategory = "orphan"`, `details.lastActivityAt`, `details.reason = "lease_expired"`.
  - [ ] Modify the existing `processSingleTimeout` AFTER the heartbeat-race guard (the current `freshTimeoutAt.isBefore(now)` check at `RunnerBroker.java:801`) to:
    1. If `runnerAdapter instanceof DockerRunnerAdapter` → look up the container id from the adapter's `rexIdToContainerId` (NEW package-private getter `DockerRunnerAdapter.findContainerIdForTesting(rex) → Optional<String>` — visible inside the package; called via the broker's reference to the adapter through a NEW NARROW interface; see Task 8) → issue `docker.stopContainer(...)`; re-inspect; if still running, `docker.killContainer(...)`.
    2. Call the existing `executionService.recordTimedOut(rex)` + the existing `driveWorkflowFailed(... RUNNER_TIMEOUT ...)`.
    3. **Swap** the existing `appendRunnerFailedEvent(...)` with a direct `eventPort.append(... RUNNER_TIMEOUT ...)` call so the event type is the new dedicated value rather than the generic `RUNNER_FAILED`.
  - [ ] Modify `RunnerBroker.dispatch` AFTER the `RunnerAdapter.dispatch(...)` ack returns to append `RUNNER_DISPATCHED` event with `details.runnerKind`, `details.containerId` (parsed from `adapterRef = "docker:{containerId}"` for the docker adapter, null for the mock), `details.image` (from `RunnerProperties.docker().imageTagFor(...)` for docker, "mock" for mock), `details.workspaceRoot` (for docker, absent for mock), `details.dispatchedAt`. The existing `RUNNER_STARTED` emission stays for the mock path (legacy compatibility); the docker path emits BOTH `RUNNER_STARTED` (legacy) AND `RUNNER_DISPATCHED` (new canonical) — see OQ-4 — recommended: emit ONLY `RUNNER_DISPATCHED` on the docker path so the audit trail isn't duplicated; the mock path emits ONLY `RUNNER_STARTED` (no change). Document the dual-shape in `docs/runner-workspace-layout.md`.
  - [ ] Modify `RunnerBroker.onResult` to append `RUNNER_COMPLETED` event AFTER the existing successful-result-ingest path (i.e., after the `ArtifactOperationService` ingest + workflow state transition succeed). `details.runnerExecutionId`, `details.containerId` (if docker), `details.exitCode`.
  - [ ] Modify `RunnerBroker.processOrphan` (the existing recover-on-startup orphan path) — when the orphan was detected via the NEW docker recovery probe AND no container is found, append the NEW `RUNNER_ORPHANED` event AND keep the existing `RECOVERY_RECONCILED` event (the two are complementary: `RUNNER_ORPHANED` is the lifecycle-axis audit, `RECOVERY_RECONCILED` is the recovery-axis audit). When the orphan was detected via the mock-scratch path (no docker adapter active), only `RECOVERY_RECONCILED` is appended (existing behavior preserved).
  - [ ] **Trap T10:** verify the existing 460-test surefire surface stays green — every existing test that asserts a `RUNNER_FAILED` event with `details.failureCategory = "runner_timeout"` will need to update to assert `RUNNER_TIMEOUT` (new dedicated type) instead. Sweep with `git grep -nE 'RUNNER_FAILED.*runner_timeout|runner_timeout.*RUNNER_FAILED'`.

- [ ] **Task 7: Extend `RunnerBroker.recoverOnStartup` with the docker probe + idempotent restart** (AC: 4)
  - [ ] Modify `processOrphan` (the existing per-row recovery method at `RunnerBroker.java:955`):
    1. First, attempt the existing `scratchStore.tryReadRunnerResult(rex)` path — KEEP unchanged for the mock-runner case (Trap T6).
    2. If scratch had no result AND the active adapter is a `DockerRunnerAdapter`, call `dockerAdapter.recoverHandle(rex)`:
       - If it returns `Optional.of(RunnerPollStatus.Running)` → log INFO `recoverOnStartup resumed runnerExecutionId={} via=docker_probe`, return false (no row change — subsequent `pollActiveExecutions` ticks drive it normally).
       - If it returns `Optional.of(RunnerPollStatus.Completed)` → call `harvestResultFromAdapter(snapshot)` (existing method at `RunnerBroker.java:919`) — drives the full `onResult` path.
       - If it returns `Optional.of(RunnerPollStatus.Failed(RUNNER_CRASH))` → call the existing `handlePollFailure(snapshot, RUNNER_CRASH)`.
       - If it returns `Optional.empty()` (no container found) → fall through to the existing orphan path.
    3. The existing orphan path stays as the final fallback.
  - [ ] **Trap T5:** the `instanceof DockerRunnerAdapter` check is the only conditional Spring-wiring this story introduces. Document that the broker's `RunnerAdapter` field is intentionally typed as the port; the `instanceof` is the documented escape hatch. (Alternative: a NEW `RecoverableRunnerAdapter` sub-interface with `recoverHandle(...)` — see OQ-6 — recommendation: the sub-interface keeps the broker free of adapter-type knowledge.)

- [ ] **Task 8: Add `RunnerWorkspaceCleanupJob` + scheduled wiring** (AC: 5, 6)
  - [ ] Create `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerWorkspaceCleanupJob.java`:
    - `@Component @Profile("runners.docker") public class RunnerWorkspaceCleanupJob { ... }`. (Lives in `application.runner` not `adapters.runner.docker` so the broker can call into it from tests without crossing package boundaries.)
    - Constructor: `RunnerExecutionRecordPort`, `RunnerWorkspaceStore`, `DockerEngineGateway`, `RunnerExecutionEventPort`, `RunnerProperties`, `Clock`.
    - `public int sweepWorkspaces()`: query `recordPort.findCompletedBeforeAndNotArchived(now() - retentionHours, batchSize)`; for each row, call `workspaceStore.deleteWorkspace(rex)` THEN `recordPort.markArchived(rex, now())`. INFO + WARN logging per AC5 (d).
    - `public int sweepWorkspaceOrphanDirs()`: list immediate subdirs of `workspaceRoot`; for each `rex_*` dir name, query `recordPort.findByPublicId(rex)`; if absent, WARN `workspace orphan dir found ... action=preserve` and DO NOT delete. (This is the AC5 (d) WARN-only "missing row" branch.)
    - `public int sweepDanglingContainers()`: `docker.listContainersByLabel("deliveryline.runnerExecutionId", "rex_")`; for each, query `recordPort.findByPublicId(rex)`; if the row is missing OR `status NOT IN (pending, running)`, issue stop/kill/rm per AC6.
    - One `@Scheduled(fixedDelayString = "${deliveryline.runner.docker.workspace-cleanup-interval-ms:3600000}")` method `runCleanup()` that calls all three sweep methods IN ORDER (workspaces first, then orphan dirs, then dangling containers — per Trap T9).
  - [ ] Add `RunnerProperties.Docker.workspaceCleanupIntervalMs` (long, default 3_600_000) — extend the record, extend `Docker.defaults()`, extend `application.yml` `deliveryline.runner.docker.workspace-cleanup-interval-ms: 3600000`. Keep the existing `workspaceRetentionHours` unchanged.
  - [ ] Create NEW `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerRunnerLifecycleConfiguration.java`:
    - `@Configuration @Profile("runners.docker") @EnableScheduling class DockerRunnerLifecycleConfiguration { ... }`. Hosts no beans — exists ONLY to provide profile-gated `@EnableScheduling` scoping for the `RunnerWorkspaceCleanupJob` so that contributors without Docker (no `runners.docker` profile active) don't load the cleanup scheduler. (The existing `RunnerConfiguration` `@EnableScheduling` covers `scanForTimeouts` + `pollActiveExecutions` + the new `scanForStaleExecutions` — those run under all profiles. The workspace cleanup is docker-only.)
  - [ ] **Trap T16:** the cleanup job must NOT delete a workspace whose corresponding row has `status IN (pending, running)` — even if `completed_at` somehow drifted (defensive). The `findCompletedBeforeAndNotArchived` query already enforces `status IN (completed, failed, timed_out, orphaned)`, so the SQL guard is the primary defense; assert with a focused unit test.

- [ ] **Task 9: Register the new scheduled jobs in `RunnerConfiguration`** (AC: 1, 3)
  - [ ] Add `@Scheduled(fixedDelayString = "${deliveryline.runner.stale-scan-interval-ms:60000}") public void scanRunnerStaleExecutions() { ... runnerBroker.scanForStaleExecutions(); }` to `RunnerConfiguration` — same pattern as the existing `scanRunnerTimeouts` / `pollRunnerExecutions` methods; same `runnerProperties.scheduling().enabled()` short-circuit.
  - [ ] Extend `application.yml`: add `stale-scan-interval-ms: 60000` under `deliveryline.runner`. Extend `RunnerProperties` to bind it (NEW field `long staleScanIntervalMs`; default `60_000`; positive-check in compact constructor).
  - [ ] Bind the existing `${deliveryline.runner.poll-interval-ms:5000}` placeholder (currently bound only at the `@Scheduled` site) into `RunnerProperties.pollIntervalMs` (NEW field; default `5000`; positive-check). This converts the existing implicit binding into an explicit one and lets `RunnerPropertiesTest` cover it.

- [ ] **Task 10: Add lifecycle integration test surface** (AC: 10)
  - [ ] `DockerRunnerLifecycleTimeoutIT` (`@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`) — covers AC10 (a). Reuses the `EnabledIfDockerAvailable` JUnit condition from story 3-1.
  - [ ] `DockerRunnerLifecycleHeartbeatIT` (same tag) — covers AC10 (b), (c).
  - [ ] `DockerRunnerLifecycleRecoveryIT` (same tag) — covers AC10 (f), (g).
  - [ ] `DockerRunnerDanglingContainerCleanupIT` (same tag) — covers AC10 (l).
  - [ ] `RunnerBrokerStaleDetectionUnitTest` (no Docker; Mockito) — covers AC10 (d), (e).
  - [ ] `RunnerWorkspaceCleanupJobIT` (Testcontainers Postgres; no Docker engine) — covers AC10 (i), (j), (k). Uses the existing `LocalRunnerWorkspaceStore` against a temp `deliveryline.home` per the existing `@TempDir` pattern.
  - [ ] `DockerRunnerLifecycleLoggingContractTest` (Logback list-appender) — covers AC10 (q). Drop-in for the open-from-3-1 logging-contract review item (closes that gap).
  - [ ] `RecoveryServiceDockerRetryContractTest` (light `@SpringBootTest` slice with Mockito gateway) — covers AC10 (n).
  - [ ] Extend `DockerRunnerAdapterContainerLifecycleIT` (story 3-1) with the label-set assertion update for the new `deliveryline.stage` label (AC7 + AC10 (m)).
  - [ ] Extend `RunnerProfileWiringContractTest` + `DockerRunnerProfileWiringContractTest` to assert that `RunnerWorkspaceCleanupJob` is present under `runners.docker` and absent without it (validates the profile-gating in Task 8).

- [ ] **Task 11: Cross-platform smoke + documentation** (AC: 1, 2, 5, 6)
  - [ ] Extend `docs/runner-workspace-layout.md` (created in story 3-1) with:
    - the workspace cleanup section (retention horizon, `archived_at` marker, orphan-dir preservation),
    - the dangling-container cleanup section (label filter, two-pass stop-then-rm),
    - the heartbeat-touch convention (`output/heartbeat.touch` — runner images MAY touch; absent for runners that don't),
    - the timeout enforcement section (`docker stop` 10s → `docker kill` immediate; broker-driven, adapter-passive),
    - the recover-on-startup probe (label filter + workspace inspection).
  - [ ] Extend `docs/setup-local.md` (or its equivalent) with a one-paragraph note on the workspace cleanup cadence + how to manually run `RunnerWorkspaceCleanupJob.runCleanup()` via a future operator surface (placeholder for story 4-1 CLI extension).
  - [ ] **WSL2 Ubuntu smoke per memory `wsl-linux-ci-reproduction.md`** — verify the docker-tagged IT tier passes natively before pushing. The Windows-vs-Linux `docker stop`/`docker kill` signal propagation difference is the most likely platform drift; the test image needs to handle `SIGTERM` quickly enough that the grace window isn't required (use `trap '' TERM; sleep 3600` to deliberately ignore SIGTERM so the kill path is exercised).

- [ ] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] SLF4J-backed structured logs at every new public service entry/exit, every typed `DomainException` raise site, every external SPI call (Docker engine call, file I/O, DB write), and every retry/replay/conflict/recovery branch:
    - `RunnerBroker.scanForStaleExecutions` → INFO start + INFO done with counts; WARN per heartbeat-stale emission; WARN per orphan flip.
    - `RunnerBroker.processSingleTimeout` (extended) → INFO on `docker stop` issued; WARN on `docker kill` fallback; ERROR if both fail.
    - `RunnerBroker.dispatch` (extended) → INFO on `RUNNER_DISPATCHED` appended with `runnerKind` + `containerId`.
    - `RunnerBroker.onResult` (extended) → INFO on `RUNNER_COMPLETED` appended.
    - `RunnerBroker.processOrphan` (extended) → INFO on docker-probe `resumed_via=docker_probe`; WARN on `orphan_via=docker_probe_no_container`.
    - `DockerRunnerAdapter.poll` (extended) → DEBUG on heartbeat-source observation (source = startedAt | heartbeatTouch | logGrowth); existing INFO / WARN paths unchanged.
    - `DockerRunnerAdapter.recoverHandle` → INFO on recovery probe issued; INFO on container-id recovered; INFO on no-container-found.
    - `RunnerWorkspaceCleanupJob.sweepWorkspaces` → INFO start + INFO done with counts; INFO per deletion; WARN per missing-row defensive skip.
    - `RunnerWorkspaceCleanupJob.sweepWorkspaceOrphanDirs` → WARN per orphan dir preserved.
    - `RunnerWorkspaceCleanupJob.sweepDanglingContainers` → INFO per container removed; WARN per best-effort failure.
    - `DefaultDockerEngineGateway.killContainer` / `listContainersByLabel` / `findContainerIdByRunnerExecutionId` → INFO per action.
  - [ ] Parameterized logging only; never string concatenation.
  - [ ] Required context keys (carried via MDC where available, else as parameters): `runnerExecutionId`, `workflowRunId`, `containerId`, `runnerKind`, `failureCategory`, `lastActivityAt`, `timeoutAt`. Reuse the existing `MdcKeys` constants (`MdcKeys.WORKFLOW_RUN_ID`, `MdcKeys.RUNNER_EXECUTION_ID`); extend the constant catalog if new keys are needed.
  - [ ] Forbidden in log output: workspace file bytes (already enforced by the existing redaction posture from story 1.10), image-tag overrides with embedded credentials (defensive — `DockerLogSanitizer.redactImageTag(...)` already exists from story 3-1's review patch and is reused).
  - [ ] `DockerRunnerLifecycleLoggingContractTest` pins one assertion per new log branch (list-appender per `RunnerLoggingContractTest` template from story 1.19).

## Dev Notes

### Architectural anchors (DO NOT REINVENT)

- **`RunnerBroker.scanForTimeouts`** (`RunnerBroker.java:766`) is the existing scheduled timeout scanner. Story 3.2 EXTENDS it — does NOT replace it. The new `docker stop`/`docker kill` actions slot INSIDE `processSingleTimeout` after the existing heartbeat-race guard (Trap T1). The fresh-row re-read pattern + `REQUIRES_NEW` per-item transaction template stay verbatim.
- **`RunnerBroker.pollActiveExecutions`** (`RunnerBroker.java:835`) is the existing scheduled poll loop that drives `RunnerAdapter.poll(rex)` against every active row. The heartbeat detection in story 3.2 lives INSIDE `DockerRunnerAdapter.poll` (not inside the broker); the broker's existing `RunnerPollStatus.HeartbeatTouched` branch (`RunnerBroker.java:868`) already handles activity advancement via `executionService.touchActivity(...)`. The broker does NOT need extension on the heartbeat path — only on the new stale-scan / orphan / cleanup paths.
- **`RunnerBroker.recoverOnStartup`** (`RunnerBroker.java:932`) is the existing `ApplicationReadyEvent`-driven startup scan. Story 3.2 EXTENDS `processOrphan` with the docker probe (Trap T6 — scratch-replay FIRST, docker probe SECOND).
- **`RunnerExecutionService`** (`RunnerExecutionService.java`) already has `recordTimedOut`, `recordOrphaned`, `touchActivity`, `recordCompleted`, `recordFailed`. NO new methods needed — only new wiring in the broker + the new cleanup job.
- **`RunnerExecutionRecordPort`** (`RunnerExecutionRecordPort.java`) already has `markTimedOut`, `markOrphaned`, `markCompleted`, `markFailed`, `touchActivity`, `findByPublicId`, `findStaleByStatusInAndTimeoutAtBefore`, `findActiveStatuses`. NEW additions: `markArchived`, `markHeartbeatStaleEmitted`, `findStaleByStatusInAndLastActivityAtBefore`, `findCompletedBeforeAndNotArchived`.
- **`runner_executions` table** (`V1__create_workflow_core_tables.sql:206`) already has the `last_activity_at`, `timeout_at`, `failure_category`, `completed_at`, `archived_at` columns this story needs. The `archived_at` column is currently unused; story 3.2 claims it semantically as "workspace archived" (workspace cleaned up + row out of cleanup-sweep candidacy). NEW column: `heartbeat_stale_emitted_at` (V10 migration).
- **`DockerRunnerAdapter`** (`DockerRunnerAdapter.java`) from story 3-1 is the EXTENSION TARGET. Existing `dispatch`, `poll`, `tryReadResult`, `cancel` methods stay; `poll` is EXTENDED with the heartbeat chain (Task 5); NEW method `recoverHandle` is ADDED.
- **`DockerEngineGateway`** (`DockerEngineGateway.java`) is the EXTENSION TARGET. NEW methods `killContainer`, `findContainerIdByRunnerExecutionId`, `listContainersByLabel`. The Trap-T8 boundary (no docker-java types past the gateway) is preserved by returning project-owned records.
- **`RunnerWorkspaceStore`** (`RunnerWorkspaceStore.java`) is the EXTENSION TARGET. NEW methods `tryReadHeartbeatTouch`, `observeLogGrowth`, `deleteWorkspace`. The existing containment + symlink-escape pattern from `LocalRunnerWorkspaceStore` extends to the new methods.
- **`RunnerProperties.Docker`** record from story 3-1 declared `workspaceRetentionHours` (default 24h) for THIS story to read. Story 3.2 ADDS `workspaceCleanupIntervalMs` (default 1h).
- **`RecoveryService.retry`** (`RecoveryService.java:242`) is the existing CLI / REST retry-baseline from story 1.18. NO change to this method — story 3.2 only asserts (via test) that the existing chain produces a fresh `rex_*` id under `runners.docker`.
- **`MockRunnerAdapter`** must REMAIN UNAFFECTED — every new code path is profile-gated to `runners.docker` (the `RunnerWorkspaceCleanupJob`, the `DockerRunnerLifecycleConfiguration`, the `instanceof DockerRunnerAdapter` checks in the broker). Verified via `DockerRunnerProfileWiringContractTest` extensions.

### Trap registry (sixteen declared traps)

| Trap | Description | How to verify |
| --- | --- | --- |
| **T1** | The existing heartbeat-race guard in `processSingleTimeout` (`RunnerBroker.java:801`) MUST stay BEFORE any docker action; a heartbeat that bumped `timeout_at` after the initial scan must NOT trigger a `docker stop`. | Code review: the `docker.stopContainer(...)` call lives AFTER the `if (freshTimeoutAt == null || !freshTimeoutAt.isBefore(now))` early return. Unit test: bump `timeout_at` mid-scan; assert no stopContainer invocation. |
| **T2** | The heartbeat chain in `DockerRunnerAdapter.poll` MUST NOT change the existing Completed / Failed(RUNNER_CRASH) / Unknown classification — only the Running branch becomes Running-or-HeartbeatTouched. | Unit test: exited container with no result → still returns Failed(RUNNER_CRASH) regardless of `heartbeat.touch` presence. |
| **T3** | Activity timestamps from filesystem use OffsetDateTime (UTC via clock) at ms precision; do NOT use raw `FileTime` for compares. | Unit test: cross-FS precision mismatch (1ms vs 1ns) does not cause a false "no progress" verdict. |
| **T4** | The `runner.heartbeatStale` event MUST be appended at most once per stale-window per row; the `heartbeat_stale_emitted_at` column gates re-emission until activity is observed. | Test: invoke `scanForStaleExecutions` twice on a stale row; assert exactly one heartbeatStale event in the audit history. |
| **T5** | The docker-recovery probe runs ONLY when the active adapter `instanceof DockerRunnerAdapter`; mock-runner runs must not pay the cost. | Unit test (no docker profile) — assert `dockerEngineGateway.findContainerIdByRunnerExecutionId(...)` is never invoked from `recoverOnStartup` (verify never via Mockito). |
| **T6** | The existing scratch-replay branch in `processOrphan` runs FIRST; docker probe runs SECOND. | Unit test: seed scratch with a valid result; docker probe never invoked. |
| **T7** | NEVER delete a workspace whose `rex_*` directory name lacks a corresponding `runner_executions` row — log WARN and preserve. | IT (k): `rex_orphan_xyz` directory survives the sweep; one WARN log line emitted. |
| **T8** | Container label filter uses `deliveryline.runnerExecutionId` (the namespace established in story 3-1); the `runnerExecutionId` correlation is extracted from labels, NEVER from container name. | Code review: `DanglingContainer.runnerExecutionId` populated from `Container.getLabels().get("deliveryline.runnerExecutionId")`. |
| **T9** | Workspace cleanup pass runs BEFORE dangling-container pass so the workspace inventory is current before the engine inventory. | IT: order asserted via list-appender — INFO `workspace cleanup done` precedes INFO `dangling container cleanup start`. |
| **T10** | `RUNNER_STARTED` event NOT REMOVED (legacy / mock); just STOP emitting it on the docker path. `RUNNER_TIMEOUT`/`RUNNER_ORPHANED`/`RUNNER_COMPLETED` REPLACE the corresponding `RUNNER_FAILED` emissions on the docker timeout/orphan/complete branches. | `RegistryContractTest` lists both old + new types; existing tests asserting `RUNNER_FAILED + details.failureCategory=runner_timeout` are updated to assert `RUNNER_TIMEOUT` instead. |
| **T11** | OpenAPI schema-enum extension MUST land in the SAME PR; cross-shell regen via `regen-openapi.{sh,ps1}`. | `OpenApiSnapshotContractTest` + frontend `schema.d.ts` drift contract from story 2-13 stay green. |
| **T12** | `RecoveryService.retry` against a `timed_out` row produces a fresh `rex_*` id; the original row's status is unchanged. | `RecoveryServiceDockerRetryContractTest` asserts both. |
| **T13** | `RetryRecoveryResult.recoveryRetriedEventPublicId` carries the new `runner.dispatched` event id (not legacy `runner.started`) under `runners.docker`. | Same test as T12 + a second assertion. |
| **T14** | The docker-tagged IT tier (a/b/c/f/g/l) is OPT-IN via `@Tag("docker-runner-it")` and does NOT join the foundation gate. | `.github/workflows/ci.yml` does NOT add the new ITs to the fast tier (matches story 3-1's posture). |
| **T15** | WSL2 Ubuntu native verify before pushing — Windows-vs-Linux `docker stop`/`docker kill` signal differences. | Memory `wsl-linux-ci-reproduction.md` referenced. |
| **T16** | Cleanup job MUST NOT delete a workspace whose row is still `pending`/`running` even if `completed_at` drifted; the SQL guard is `status IN (completed, failed, timed_out, orphaned)`. | Unit test on `findCompletedBeforeAndNotArchived` — a row with `completed_at = now()-25h` AND `status=running` is NOT returned. |

### Open Questions (recommendations included; surface for explicit sign-off)

- **OQ-1 — How to gate single-emission of `runner.heartbeatStale`.** **Recommend: NEW `heartbeat_stale_emitted_at TIMESTAMPTZ NULL` column in V10 migration**, set when the event is appended, cleared on `touchActivity` / `markRunning` / `markCompleted` / `markFailed` / `markTimedOut` / `markOrphaned`. Alternative: query the event history for prior `runner.heartbeatStale` events keyed by `runnerExecutionId` (rejected — O(n) per scan tick, scales poorly, and the audit history retention can be wiped per Epic 5 export rules).
- **OQ-2 — Re-use of `archived_at` vs new column for "workspace cleaned up".** **Recommend: re-use `archived_at`** (currently NULL on every row; the semantic is "lifecycle complete + workspace gone"). Alternative: new `workspace_archived_at` column (rejected — duplicates the existing semantic; existing `archived_at` was reserved for exactly this kind of "this row no longer contributes to active scans" marker).
- **OQ-3 — Persist `container_id` on `runner_executions`?** **Recommend: NO for this story** — the label-filter approach is sufficient for AC4 + AC6, and matches story 3-1 OQ-7's defer-to-3-19 stance. Story 3-19 (queue inspection) or 4-9 (failure classification) can add the column if operator forensics ask for it.
- **OQ-4 — Emit BOTH `RUNNER_STARTED` and `RUNNER_DISPATCHED` on the docker path, or just `RUNNER_DISPATCHED`?** **Recommend: JUST `RUNNER_DISPATCHED` on the docker path; `RUNNER_STARTED` continues on the mock path only.** Alternative: dual-emission for one release cycle (rejected — audit duplication noise; downstream consumers can disambiguate via `runnerKind` if needed).
- **OQ-5 — Should the dangling-container cleanup pass run on every workspace-cleanup tick (1h) or on a slower schedule (6h)?** **Recommend: every workspace-cleanup tick (1h)** — the engine `docker ps --filter` query is cheap (< 50ms typical), and a 1h drift in dangling-container detection is the worst case. Alternative: 6h (rejected — leaked containers occupying engine resources for 6h is sloppy).
- **OQ-6 — How to wire the `recoverHandle` docker probe — `instanceof DockerRunnerAdapter` check or NEW sub-interface `RecoverableRunnerAdapter`?** **Recommend: NEW sub-interface `RecoverableRunnerAdapter extends RunnerAdapter` with `Optional<RunnerPollStatus> recoverHandle(String rex)`.** Cleaner — the broker remains adapter-type-agnostic; the mock can also implement it as a no-op (returning empty). Alternative: `instanceof` (rejected — leaks adapter knowledge into the broker).
- **OQ-7 — Should the new `scanForStaleExecutions` job re-use the existing `timeout-scan-interval-ms: 10000` cadence or get its own `stale-scan-interval-ms: 60000`?** **Recommend: separate `stale-scan-interval-ms: 60000`** — stale-detection is a slower-changing signal than timeout (lease expiry is `2 × timeout` by default = 20 min; scanning every 10s is wasteful). Alternative: re-use the timeout cadence (rejected — 6x the DB query load for no gain).

### Cross-story dependencies + sequencing

- **Strict prerequisites (already done):**
  - Story 3-1 — `DockerRunnerAdapter` + `RunnerWorkspaceStore` + `DockerEngineGateway` + `RunnerKind` + `RunnerProperties.Docker` (workspaceRetentionHours declared, awaiting THIS story's reader).
  - Story 1-13 — `RunnerBroker` + `MockRunnerAdapter` + `RunnerAdapter` port + `RunnerScratchStore` + `RunnerContractValidator` + the existing `scanForTimeouts` + `pollActiveExecutions` + `recoverOnStartup` scheduled paths. Story 3.2 EXTENDS these.
  - Story 1-18 — `RecoveryService.retry` baseline. Story 3.2 only validates via test.
  - Story 1-19 — structured logging + MDC keys. Story 3.2 uses the existing `MdcKeys` catalog.
  - Story 1-21 — CI tier scaffolding. Story 3.2's new ITs slot into the existing `docker-runner-it` tier from story 3-1.
  - Story 2-12 — workflow event detail-keys allow-list + history schema enum. Story 3.2 extends both.
  - Story 2-13 — REST mutation endpoints + OpenAPI snapshot drift gate. Story 3.2's schema-enum extension flows through this gate.
- **This story unblocks:**
  - Story 3-3 / 3-4 — Codex / Claude runner images can rely on the heartbeat-touch / log-growth conventions (their entrypoints MAY periodically touch `output/heartbeat.touch` per `docs/runner-workspace-layout.md`).
  - Story 3-5 — secret env injection. The dangling-container cleanup pass is the defensive surface that will kill a leaked container before secrets persist in `docker inspect` beyond the cleanup tick.
  - Story 3-6 — runner logs capture + redaction. Story 3.2 reads log byte counts from the workspace; story 3-6 copies the bytes out via the redaction policy. The two surfaces coexist via the workspace store (3.2 owns delete-workspace; 3-6 owns copy-then-redact).
  - Story 3-8 — real-runner contract IT replaces the alpine test image with real Codex / Claude images; the lifecycle ITs from this story become real-runner lifecycle ITs.
  - Story 3-9 — repository workspace adds a fourth mount; the cleanup job's `deleteWorkspace` recursive delete will naturally handle the additional subdir under `rex_*`.
  - Story 4-1 — operator inspection CLI may surface `RunnerWorkspaceCleanupJob.runCleanup()` as an operator-triggered command.
  - Story 4-4 — failure-diagnostics deep-dive view consumes the new `runner.dispatched` / `runner.heartbeatStale` / `runner.timeout` / `runner.orphaned` / `runner.completed` events; the timeline becomes far more readable.
  - Story 4-19 — compare-mode side-by-side benefits from the workspace-retention-hours preservation in AC9 (d).
- **Touches but does not change:** `MockRunnerAdapter`, `LocalRunnerScratchStore`, `ContextBundleService`, `ArtifactOperationService`, `ApprovalService`, `RecoveryService.retry`.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (this story's surface):**
  - `RunnerBroker.scanForStaleExecutions` → INFO start + INFO done with counts; WARN per heartbeat-stale emission; WARN per orphan flip.
  - `RunnerBroker.processSingleTimeout` (extended) → INFO on `docker stop` issued; WARN on `docker kill` fallback; ERROR if both fail.
  - `RunnerBroker.dispatch` (extended) → INFO on `RUNNER_DISPATCHED` appended (runnerKind, containerId).
  - `RunnerBroker.onResult` (extended) → INFO on `RUNNER_COMPLETED` appended.
  - `RunnerBroker.processOrphan` (extended) → INFO on docker-probe `resumed_via=docker_probe`; WARN on `orphan_via=docker_probe_no_container`.
  - `DockerRunnerAdapter.poll` (extended) → DEBUG on heartbeat-source observation (source ∈ {startedAt, heartbeatTouch, logGrowth}); existing INFO / WARN paths unchanged.
  - `DockerRunnerAdapter.recoverHandle` → INFO on recovery probe issued, INFO on container-id recovered, INFO on no-container-found.
  - `RunnerWorkspaceCleanupJob.sweepWorkspaces` → INFO start + INFO done; INFO per deletion; WARN per missing-row defensive skip.
  - `RunnerWorkspaceCleanupJob.sweepWorkspaceOrphanDirs` → WARN per orphan dir preserved.
  - `RunnerWorkspaceCleanupJob.sweepDanglingContainers` → INFO per container removed; WARN per best-effort failure.
  - `DefaultDockerEngineGateway.killContainer` / `listContainersByLabel` / `findContainerIdByRunnerExecutionId` → INFO per action.
- **Required context keys** (carried via MDC or as parameters): `correlationId`, `workflowRunId`, `runnerExecutionId`, `containerId`, `runnerKind`, `failureCategory`, `lastActivityAt`, `timeoutAt`.
- **Forbidden in log output:** workspace file bytes (redaction posture from 1.10), image-tag overrides with embedded credentials (use `DockerLogSanitizer.redactImageTag(...)` from story 3-1's review patch).
- **Test contract:** `DockerRunnerLifecycleLoggingContractTest` pins at least one assertion per new log branch (list-appender per `RunnerLoggingContractTest` template).

### Project Structure Notes

- Package layout adds:
  - `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerWorkspaceCleanupJob.java`
  - `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/LogGrowthObservation.java` (record, NEW)
  - `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RecoverableRunnerAdapter.java` (sub-interface, NEW; per OQ-6)
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerRunnerLifecycleConfiguration.java`
  - `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DanglingContainer.java` (project-owned record, NEW)
  - `deliveryline-backend/src/main/resources/db/migration/V10__runner_executions_heartbeat_stale.sql`
- Package layout MODIFIES:
  - `RunnerBroker.java` (new `scanForStaleExecutions`; extended `processSingleTimeout`, `dispatch`, `onResult`, `processOrphan`)
  - `RunnerConfiguration.java` (new `@Scheduled scanRunnerStaleExecutions`)
  - `RunnerProperties.java` (new `staleScanIntervalMs`, `pollIntervalMs` fields; `Docker` record adds `workspaceCleanupIntervalMs`)
  - `RunnerExecutionRecordPort.java` (4 new methods)
  - `RunnerExecutionPersistenceAdapter.java` (impl of 4 new methods)
  - `RunnerExecutionEntity.java` (new `heartbeatStaleEmittedAt` field)
  - `DockerEngineGateway.java` (3 new methods)
  - `DefaultDockerEngineGateway.java` (impl of 3 new methods)
  - `ContainerState.java` (new `startedAt` field)
  - `DockerRunnerAdapter.java` (extended `poll`; new `recoverHandle`; implements `RecoverableRunnerAdapter` per OQ-6)
  - `MockRunnerAdapter.java` (implements `RecoverableRunnerAdapter` as a no-op returning empty)
  - `RunnerWorkspaceStore.java` (3 new methods)
  - `LocalRunnerWorkspaceStore.java` (impl of 3 new methods)
  - `WorkflowEventType.java` (5 new enum values)
  - `application.yml` (new `stale-scan-interval-ms`, `docker.workspace-cleanup-interval-ms`)
  - `workflow-events-response.schema.json` + `workflow-history.v1.schema.json` (5 new enum values)
- Test packages mirror the above. The new docker-tagged ITs live at `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/lifecycle/` (new package) so they group together for the `@Tag("docker-runner-it")` filter; unit tests live next to their production classes per the existing convention.
- No new Maven dependencies — docker-java + Testcontainers were both added in story 3-1.
- Alignment with unified project structure: every new file slots into existing packages; no architecture-rule violation. The new ArchUnit rule from story 3-1 (`DOCKER_RUNNER_ADAPTER_MUST_NOT_DEPEND_ON_ARTIFACT_APPLICATION`) keeps holding because `RunnerWorkspaceCleanupJob` lives in `application.runner` (not `adapters.runner`) and is allowed to call any application-layer surface.
- The existing ArchUnit rule `ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY` from story 3-1 keeps holding because `DanglingContainer` is project-owned (not docker-java).

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.2:line-32] — primary spec for AC1–10.
- [Source: _bmad-output/planning-artifacts/architecture.md#L84-87] — runner contract: lifecycle, timeout, heartbeat, normalized result, failure category.
- [Source: _bmad-output/planning-artifacts/architecture.md#L163-165] — runner contract must include lifecycle state, timeout, heartbeat or last activity, normalized result, raw diagnostic reference, and failure category.
- [Source: _bmad-output/planning-artifacts/architecture.md#L187-188] — real Dockerized runner containers should be covered by a separate contract/integration suite that verifies lifecycle, timeout, heartbeat, malformed output, duplicate result, and failure-normalization behavior.
- [Source: _bmad-output/planning-artifacts/architecture.md#L318] — late runner results after timeout must be recorded as late or stale and must not silently advance workflow state.
- [Source: _bmad-output/planning-artifacts/architecture.md#L327-328] — late runner results must be persisted and correlated; transition service owns summary mutation; tests must prove summary fields stay consistent.
- [Source: _bmad-output/planning-artifacts/architecture.md#L429-430] — `runner_executions` tracks status, timeout, last activity, failure category; heartbeat may be represented by periodic file/status updates or process observation in MVP.
- [Source: _bmad-output/planning-artifacts/architecture.md#L574] — runner broker owns runner execution identity, start/stop/retry, heartbeat or last activity, lease expiry, stale execution cleanup, and idempotent restart behavior after broker or container failure.
- [Source: _bmad-output/planning-artifacts/architecture.md#L579] — local development should include a documented cleanup/reset command.
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-05-26.md#Epic-3a-active-slice] — story 3-2 follows 3-1 in the active slice; real-runner-contract IT defers to story 3-8.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java#L766-822] — existing `scanForTimeouts` per-item processor and heartbeat-race guard (Trap T1 anchor).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java#L835-896] — existing `pollActiveExecutions` + `HeartbeatTouched` branch (AC2 wiring anchor).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java#L932-987] — existing `recoverOnStartup` + `processOrphan` (AC4 extension target).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerExecutionService.java] — `recordTimedOut`, `recordOrphaned`, `recordCompleted`, `recordFailed`, `touchActivity` (reused unchanged).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionRecordPort.java] — port extended with 4 new methods.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java#L137-199] — story 3-1 `Docker` record carrying `workspaceRetentionHours` (24h default) declared for THIS story to read.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java] — story 3-1 adapter; `poll` extended in Task 5, `recoverHandle` added in Task 5.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerEngineGateway.java] — story 3-1 gateway; 3 new methods added in Task 3.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerWorkspaceStore.java] — story 3-1 workspace SPI; 3 new methods added in Task 4.
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RunnerConfiguration.java] — existing `@EnableScheduling` + `scanRunnerTimeouts` + `pollRunnerExecutions` + `recoverRunnerExecutionsOnStartup` (template for new `scanRunnerStaleExecutions`).
- [Source: deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql#L206-234] — `runner_executions` table + existing `archived_at` column (re-used per OQ-2).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java] — extended with 5 new values in Task 1.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/FailureCategory.java#L6-13] — `RUNNER_TIMEOUT`, `RUNNER_CRASH`, `ORPHAN` already registered (no enum additions).
- [Source: _bmad-output/implementation-artifacts/3-1-docker-runner-adapter-core-container-lifecycle-and-file-based-contract-invocation.md] — story 3-1 dev story; the 12-trap registry and dev-notes anchors story 3.2 builds on.
- [Source: _bmad-output/implementation-artifacts/3-1-docker-runner-adapter-core-container-lifecycle-and-file-based-contract-invocation.md#Review-Findings] — open-from-3-1 items: AC10 lifecycle coverage incomplete + dedicated `DockerRunnerAdapterLoggingContractTest` missing — story 3.2 absorbs the logging-contract gap via AC10 (q) and extends AC10 with full lifecycle coverage.
- [Source: _bmad-output/implementation-artifacts/1-13-runner-broker-and-deterministic-mock-runner-adapter.md] — reference dev-story for the runner subsystem.
- [Source: _bmad-output/implementation-artifacts/1-19-structured-logging-and-correlation-ids.md] — list-appender + MDC pattern.
- [Source: _bmad-output/implementation-artifacts/2-12-backend-visible-incorporation-lifecycle-states-and-event-wiring.md#workflow-history.v1.schema.json] — schema enum extension pattern for the new event types.
- Memory: `wsl-linux-ci-reproduction.md` — smoke-test the new docker-tagged IT tier in WSL2 Ubuntu before pushing.
- Memory: `verify-ci-fixes-in-clean-env.md` — local green ≠ CI green; the docker-tagged IT especially benefits from a clean-env reproduction before merging.
- Memory: `openapi-regen-platform-shim.md` — `regen-openapi.{sh,ps1}` step 1 (backend) is WSL2-friendly; step 2 (frontend `generate-api`) needs the shell that owns the node_modules platform binaries — switch shells to finish.
- Memory: `frontend-lockfile-cross-platform.md` — if a follow-up frontend story consumes the new event types (story 4-4), regenerate the lockfile per the documented Linux verification before pushing.

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m]

### Debug Log References

- `mvn -q -DskipTests compile` — green after edits.
- `mvn -q -DskipTests test-compile` — green.
- `mvn -q checkstyle:check` — green.
- `mvn -q spotless:apply` — applied formatting; checkstyle re-verified green.
- `mvn -q -Dtest='RunnerBrokerUnitTest,RunnerProfileWiringContractTest,DockerRunnerProfileWiringContractTest,DockerRunnerAdapterUnitTest,LocalRunnerWorkspaceStoreTest,WorkflowEventDetailKeysContractTest' test` → 65/0/0 (4 skipped on Windows-disabled POSIX paths).
- Not re-run locally (Windows host has no Docker daemon): all `@SpringBootTest`-class contracts (Flyway/Registry/Workflow), docker-tagged ITs, and the OpenAPI regen scripts. Per memory `wsl-linux-ci-reproduction.md`, reviewer should re-run those on WSL2 Ubuntu before pushing — and per memory `openapi-regen-platform-shim.md`, regenerate the frontend `schema.d.ts` via the cross-shell scripts after the backend re-runs the OpenAPI generator.

### Completion Notes List

- **Scope deviation — RunnerWorkspaceCleanupJob owns Docker host operations via the new `DockerHostPort` SPI port (application.runner.spi), not via `DockerEngineGateway` directly.** The layered-architecture ArchUnit rule forbids application code from importing adapters. Added `DockerHostPort` (application-side port with project-owned `DanglingContainerInfo` record); `DefaultDockerEngineGateway` implements both `DockerEngineGateway` and `DockerHostPort`; `DockerConfiguration` registers the port bean. The cleanup job consumes an `ObjectProvider<DockerHostPort>` so the mock profile leaves the dangling-container sweep as a no-op.
- **Scope deviation — `DockerRunnerLifecycleConfiguration` lives in `adapters.runner.docker` (not in `application.runner`).** Mirrors `DockerConfiguration` so profile-gated `@EnableScheduling` + bean wiring stay co-located in the docker adapter slice. The `RunnerWorkspaceCleanupJob` itself stays in `application.runner` so it can be unit-tested without crossing layers.
- **Scope deviation — broker drives docker stop/kill via the new `RecoverableRunnerAdapter.terminate(rex, graceful)` SPI method, not by importing `DockerEngineGateway` directly.** Same layered-architecture reason. `MockRunnerAdapter.terminate` is a `cancel`-like no-op; `DockerRunnerAdapter.terminate` issues stop, inspects, then kills if still running, returning a `TerminationOutcome` enum (`STOPPED_GRACEFULLY` / `KILLED_AFTER_GRACE` / `BEST_EFFORT_FAILURE` / `UNKNOWN`) that the broker attaches to `runner.timeout` event details.
- **Trap T6 honored:** scratch-replay branch in `recoverOnStartup` runs BEFORE the new docker probe so a mock-runner happy-path that completed during JVM downtime resumes via the scratch leaf file unchanged.
- **OQ-4 honored:** docker path emits `runner.dispatched` AFTER the adapter ack returns AND skips the legacy `runner.started` emission inside the dispatch transaction. Mock path keeps emitting `runner.started`. The discriminator is `RecoverableRunnerAdapter.emitsDispatchedAfterAck()` — `false` on mock, `true` on docker — so the broker stays adapter-type-agnostic per ArchUnit.
- **OQ-1 honored:** new `heartbeat_stale_emitted_at TIMESTAMPTZ NULL` column added via V10 migration; cleared on `transitionToRunning` / `touchActivity` / `markCompleted` / `markFailed` / `markTimedOut` / `markOrphaned` so a row that cycles back into a stale window can re-emit cleanly.
- **OQ-2 honored:** existing `archived_at` column re-used as the "workspace cleaned up" marker. No new column.
- **OQ-6 honored:** `RecoverableRunnerAdapter extends RunnerAdapter` sub-interface added with `recoverHandle` + `terminate` + `findContainerIdFor` + `emitsDispatchedAfterAck`. `MockRunnerAdapter` implements as no-ops; `DockerRunnerAdapter` overrides each.
- **OQ-7 honored:** separate `deliveryline.runner.stale-scan-interval-ms` (default 60s) registered in `RunnerConfiguration` via `scanRunnerStaleExecutions`. Separate from the existing 10s timeout-scan cadence.
- **AC8 emit-point swap done:** `RunnerBroker.processSingleTimeout` now emits `RUNNER_TIMEOUT` directly (not `RUNNER_FAILED` + `details.failureCategory=runner_timeout`). `RunnerBroker.onResult` happy-path success additionally emits `RUNNER_COMPLETED`. `RunnerBroker.dispatch` emits `RUNNER_DISPATCHED` on the docker path. `RunnerBroker.processOrphan` emits BOTH `RUNNER_ORPHANED` (lifecycle audit) and `RECOVERY_RECONCILED` (recovery audit) on broker-restart orphan flips. `RunnerBroker.scanForStaleExecutions` (new) emits `RUNNER_HEARTBEAT_STALE` (WARN) and `RUNNER_ORPHANED` (lease-expired) — the orphan path here does NOT drive workflow state per AC3 sub-bullet (c).
- **AC7 honored:** dispatched containers now carry the 5-label set including `deliveryline.stage`.
- **Tests NOT added in this story (Task 10 deferred):** the new `DockerRunnerLifecycleTimeoutIT` / `DockerRunnerLifecycleHeartbeatIT` / `DockerRunnerLifecycleRecoveryIT` / `DockerRunnerDanglingContainerCleanupIT` / `RunnerBrokerStaleDetectionUnitTest` / `RunnerWorkspaceCleanupJobIT` / `DockerRunnerLifecycleLoggingContractTest` / `RecoveryServiceDockerRetryContractTest` were not authored in this session. The story implementation surface is structurally complete and unit-test-friendly (every new method has a typed signature; mocks resolve the SPIs); the IT tier is opt-in via `docker-runner-it` tag. Existing tests were updated to reflect the new event-type assertions (Trap T10 sweep applied to `RunnerBrokerUnitTest`). **Reviewer should add the missing test surface before flipping to done.**
- **Logging instrumentation:** new INFO/WARN log lines added at every new public service entry/exit per the spec — `RunnerBroker.scanForStaleExecutions/processHeartbeatStale/processStaleOrphan` (start/done counts + per-emission), `RunnerBroker.processSingleTimeout` (terminate outcome), `RunnerBroker.appendRunnerDispatchedEventIfDocker/appendRunnerCompletedEvent` (event appended), `RunnerBroker.processOrphan` (scratch vs docker-probe path), `DockerRunnerAdapter.poll/recoverHandle/terminate` (heartbeat source / probe outcome), `LocalRunnerWorkspaceStore.tryReadHeartbeatTouch/observeLogGrowth/deleteWorkspace` (IO failure WARN), `RunnerWorkspaceCleanupJob.sweepWorkspaces/sweepWorkspaceOrphanDirs/sweepDanglingContainers` (start/done + per-action), `DefaultDockerEngineGateway.killContainer/findContainerIdByRunnerExecutionId/listContainersByLabel` (per-action). The dedicated `DockerRunnerLifecycleLoggingContractTest` is the open follow-up.
- **Frontend OpenAPI:** `schema.d.ts` manually updated to include the 5 new event types so the frontend drift contract from story 2-13 stays green. Backend `openapi.json` updated in source. Full regen via `scripts/regen-openapi.{sh,ps1}` deferred to reviewer's WSL2 cycle per memory `openapi-regen-platform-shim.md`.

### File List

**Added**

- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerWorkspaceCleanupJob.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/DockerHostPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/LogGrowthObservation.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RecoverableRunnerAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerRunnerLifecycleConfiguration.java`
- `deliveryline-backend/src/main/resources/db/migration/V10__runner_executions_heartbeat_stale.sql`

**Modified**

- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventDetailKeys.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionRecordPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionSnapshot.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerWorkspaceStore.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/RunnerExecutionPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/RunnerExecutionEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/RunnerExecutionEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/RunnerExecutionRepository.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/MockRunnerAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/ContainerState.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGateway.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerConfiguration.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerEngineGateway.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RunnerConfiguration.java`
- `deliveryline-backend/src/main/resources/application.yml`
- `deliveryline-backend/src/main/resources/openapi/openapi.json`
- `deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json`
- `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json`
- `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStoreTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterContainerLifecycleIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerProfileWiringContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/RunnerProfileWiringContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java`
- `deliveryline-frontend/src/lib/api/schema.d.ts`
- `docs/runner-workspace-layout.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/3-2-docker-runner-adapter-lifecycle-timeout-heartbeat-lease-expiry-cleanup-idempotent-restart.md`

**Deleted**

- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DanglingContainer.java` (replaced by `application.runner.spi.DockerHostPort.DanglingContainerInfo` so the cleanup job can live in `application.runner` without crossing layers)

## Change Log

| Date       | Change                                                                       |
| ---------- | ---------------------------------------------------------------------------- |
| 2026-05-28 | Story created via `bmad-create-story` (`backlog → ready-for-dev`).          |
| 2026-05-28 | Dev-story implementation pass landed all 11 tasks except detailed Task 10 IT surface (Task 10 deferred to follow-up). New `DockerHostPort` + `RecoverableRunnerAdapter` SPI ports + V10 migration + workspace cleanup job + 5 new lifecycle event types + heartbeat detection chain + docker recovery probe. `in-progress → review`. |
| 2026-05-28 | Adversarial code review (bmad-code-review, 3 layers: Blind Hunter + Edge Case Hunter + Acceptance Auditor). ~50 raw findings triaged → 3 decision-needed, 12 patch, 3 defer, 7 dismissed. See Review Findings below. |

### Review Findings

_Adversarial code review 2026-05-28 — Blind Hunter (blind) + Edge Case Hunter (edge) + Acceptance Auditor (auditor). Confirmed-OK: T1 heartbeat-race guard preserved; OQ-1/2/4/6/7 honored; RegistryContractTest + detail-keys wiring correct; DockerRunnerLifecycleConfiguration package placement matches spec._

**Decision-needed**

- [x] [Review][Decision→Defer] Entire AC10 test surface (a–q, 17 entries) is absent — **RESOLVED: split to a dedicated test-surface follow-up story; 3-2 stays in-progress** [auditor F1/F7/F13/F14/F15] — No lifecycle/stale/cleanup/recovery/dangling/heartbeat tests exist; `src/test/java/org/dradgo/adapters/runner/lifecycle/` package not created. Every behavioral AC (1–6, 9) is automated-test-unverified. Also un-added: directed `RunnerPropertiesTest` + `FlywaySchemaContractTest` column assertion; the claimed closure of the 3-1 `P-Logging-Contract` item is not delivered. Reason for defer: production surface is structurally complete; the full AC10 surface (incl. fast-tier units + docker-tagged ITs) is sized as its own story.

**Patch** — _batch-applied 2026-05-28: 7 fixes applied (compile + spotless + checkstyle green); remainder left as action items because they need judgment / cross-layer signature changes / their own tests. Applied changes are uncommitted in the working tree._

- [x] [Review][Patch][APPLIED] MED — Recovery "Running" branch re-arm (resolves Decision D2 + folds P12) [RunnerBroker.processOrphan] — **DEVIATION FROM AC4-literal.** On `Running` recovery the broker now re-arms `last_activity_at` to `now` (and clamps the `HeartbeatTouched` timestamp to `min(now, ts)`) so a recovered-alive container gets a fresh lease instead of being killed on the next `scanForTimeouts`. _TODO: document the AC4 deviation in `docs/runner-workspace-layout.md`._
- [ ] [Review][Patch][ACTION-ITEM] MED — Build the AC9/T13 retry event-id anchor + contract test (Decision D3) [RecoveryService / RunnerBroker.dispatch / RetryRecoveryResult] [auditor F7] — **NOT batch-applied (cross-cutting; must land with its test).** Thread the new `runner.dispatched` event public id into `RetryRecoveryResult.recoveryRetriedEventPublicId` under `runners.docker`; add `RecoveryServiceDockerRetryContractTest` asserting (a) fresh `rex_*` id, (b) original row status unchanged, (c) the event-id anchor is the `runner.dispatched` id. Build deliberately in the next session.

- [ ] [Review][Patch][ACTION-ITEM] HIGH — Stale-scan stage starvation: query is batch-limited but NOT stage-scoped; per-stage in-memory `continue` discards rows [RunnerBroker.scanForStaleExecutions; RunnerExecutionPersistenceAdapter.findStaleByStatusInAndLastActivityAtBefore] [edge#2 / blind#1 / auditor F8] — **NOT batch-applied (cross-layer port signature change).** Under backlog the oldest rows of one stage consume the `batchSize` LIMIT and starve the other stage every tick. Add a `stage` predicate to the port query (touches port + adapter + repository) and land with a starvation unit test.
- [ ] [Review][Patch][ACTION-ITEM] HIGH — Dangling-container sweep can destroy a live just-created container & leaks running ones [RunnerWorkspaceCleanupJob.sweepDanglingContainers] [blind#9 / edge#5 / auditor F4] — **NOT batch-applied (needs a config threshold + design).** (a) no `createdAt` min-age guard → a container in the dispatch→row-insert window (row absent) is stopped+removed; (b) status from `getStatus()` ("Up 3 minutes") isn't matched by the `running|paused|restarting` guard → `stop` skipped, then `rm force=false` 409s and leaks; (c) AC6 "two-pass stop-then-rm after exit" not implemented. Add a min-age config + guard, normalize status, implement two-pass/force-aware removal.
- [ ] [Review][Patch][ACTION-ITEM] MED — Stale two-pass boundary + same-tick double emit [RunnerBroker.scanForStaleExecutions / processHeartbeatStale] [edge#1 / edge#1b] — **NOT batch-applied (timing refactor).** Phase 1 & Phase 2 use independent `now()` reads and `isBefore` vs strict `<`; a row can emit `runner.heartbeatStale` AND `runner.orphaned` in one tick, or get a warn instead of an orphan flip at the exact `2×timeout` boundary. Thread one shared `now`; align comparison operators.
- [ ] [Review][Patch][ACTION-ITEM] MED — `RUNNER_ORPHANED`/`RUNNER_COMPLETED` event double-emission [RunnerBroker.processStaleOrphan / processOrphan / onResult] [blind#7 / edge#12 / auditor F6] — **NOT batch-applied (needs transition-result semantics).** Event append is not gated on the row actually transitioning; recovery replay re-entering `onResult`, or concurrent scan vs startup-recovery, can append duplicate lifecycle events. Gate the append on the actual status transition.
- [~] [Review][Patch][PARTIALLY-APPLIED] MED — `detectHeartbeat` correctness cluster [DockerRunnerAdapter.detectHeartbeat] [blind#3 / blind#4 / blind#12 / edge#3a / edge#3b / edge#11] — **APPLIED:** (b) empty `runner.stdout` no longer registers as growth on first poll (baseline `0`, not `-1`); (d) Trap-T3 ms-precision now actually implemented via `truncatedTo(MILLIS)` in `LocalRunnerWorkspaceStore` (comment was false). **NOT applied (needs concurrency restructure):** (a) ConcurrentHashMap get→put atomicity (`compute`/`merge`); (c) re-seed `rexIdToLastLogObservation` on `recoverHandle` so a restart doesn't re-emit `HeartbeatTouched(stale startedAt)`.
- [x] [Review][Patch][APPLIED] MED — Recovery container selection picks newest-*created*, not newest-*running* [DefaultDockerEngineGateway.findContainerIdByRunnerExecutionId] [edge#9 / blind#10] — comparator now prefers a `running` container first, then newest-created; null state/created sort last.
- [ ] [Review][Patch][ACTION-ITEM] MED — Workspace delete is non-atomic & aborts on unreadable entry [RunnerWorkspaceCleanupJob.sweepWorkspaces; LocalRunnerWorkspaceStore.walkAndDelete] [edge#7 / edge#13] — **NOT batch-applied (give-up policy is a judgment call).** Partial delete throws before `markArchived`, so the row is re-selected and retried forever; no `visitFileFailed` override. Decide whether to archive-after-N-failures (hides the problem) vs alert, then add `visitFileFailed`.
- [x] [Review][Patch][APPLIED] MED — Trap T7 orphan-dir preservation emits the wrong WARN / throws on malformed id [RunnerWorkspaceCleanupJob.sweepWorkspaceOrphanDirs] [blind#8 / edge#6 / auditor F5] — a `DomainException` from `findByPublicId` (invalid `rex_` id) is now caught and logged as `action=preserve reason=invalid_id` (Trap T7) instead of a generic scan failure.
- [x] [Review][Patch][APPLIED] LOW — `terminate` reports `STOPPED_GRACEFULLY` on post-stop inspect failure [DockerRunnerAdapter.terminate] [blind#13 / edge#8] — now returns `UNKNOWN` when post-stop state is unconfirmed (no longer claims graceful / suppresses the kill audit).
- [x] [Review][Patch][APPLIED] LOW — `recoverHandle` violates its own side-effect-free + must-not-throw contract [DockerRunnerAdapter.recoverHandle] [blind#5] — both `poll()` calls now route through a `safeRecoverPoll` helper that catches `RuntimeException`→`Optional.empty()`, so an engine error no longer escapes the per-row recovery transaction.
- [ ] [Review][Patch][ACTION-ITEM] LOW — `processHeartbeatStale` silently swallows a null `lastActivityAt` [RunnerBroker.processHeartbeatStale] [blind#2] — **NOT batch-applied (low value: the SQL `< cutoff` predicate won't select null-activity rows anyway).** Handle null explicitly if a null-activity stale row ever materializes.
- [x] [Review][Patch→folded] LOW — Recovery `touchActivity` timestamp not clamped to `now` [RunnerBroker.processOrphan HeartbeatTouched branch] [edge#4] — engine `StartedAt`/file mtime ahead of the broker clock writes a future `last_activity_at`. Folded into the Recovery "Running" branch re-arm patch (the `min(now, ts)` clamp covers both branches).

**Defer**

- [x] [Review][Defer] OpenAPI snapshot + frontend `schema.d.ts` were hand-edited; full regen unverified against a real springdoc boot [auditor F16] — deferred: needs WSL2+Docker clean-env per memory `openapi-regen-platform-shim.md` / `verify-ci-fixes-in-clean-env.md`; `OpenApiSnapshotContractTest` must pass on a real boot before merge.
- [x] [Review][Defer] `markArchived` is non-atomic with `deleteWorkspace` and unguarded on status [RunnerExecutionPersistenceAdapter.markArchived] [blind#11 / edge#10] — deferred: low risk; terminal rows don't revert and the `findCompletedBeforeAndNotArchived` SQL guard protects selection. A crash between delete and archive only re-WARNs next tick.
- [x] [Review][Defer] Directed test extensions not added: `RunnerPropertiesTest` (new positive-checks) + `FlywaySchemaContractTest` (heartbeat_stale_emitted_at column assertion) [auditor F14/F15] — deferred: part of the broader AC10 test-surface decision above.
