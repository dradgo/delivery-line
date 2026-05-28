# Story 3.2a: DockerRunnerAdapter Lifecycle — Test Surface (AC10) + Review Hardening + Retry Event Anchor + OpenAPI Verification

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer,
I want the deferred AC10 test surface from story 3.2 authored in full (fast-tier units + Testcontainers ITs + the opt-in `docker-runner-it` engine tier), the remaining open story-3.2 code-review action items resolved (the HIGH stale-scan stage-starvation bug, the HIGH dangling-container sweep safety bug, the AC9/T13 retry event-id anchor wiring, and the residual MED/LOW heartbeat/event/cleanup hardening), and the hand-edited OpenAPI snapshot + frontend `schema.d.ts` verified against a real springdoc boot,
So that every behavioral acceptance criterion of story 3.2 (timeout enforcement, heartbeat detection, lease-expiry/orphan, broker-restart recovery, workspace + dangling cleanup, the five new lifecycle event types, and the recovery-retry fresh-rex anchor) is proven by automated tests, no real Docker runner failure can leak containers / lose tracking / silently advance workflow state / exhaust disk, and the contract gates (`OpenApiSnapshotContractTest`, `schema.d.ts` drift, `RegistryContractTest`, `FlywaySchemaContractTest`) stay green — flipping story 3.2 to a defensible `done`.

## Context

This story is the test-and-hardening close-out of **story 3.2** ([[3-2-docker-runner-adapter-lifecycle-timeout-heartbeat-lease-expiry-cleanup-idempotent-restart]]). Story 3.2's production surface landed structurally complete, but its dev-story pass **deferred the entire AC10 test surface (entries a–q, ~17 tests)** and a subsequent adversarial code review (2026-05-28, decision D1) split that surface into this dedicated story, plus left several production fixes open as action items. This story owns all of them.

- **Source of truth for the deferred work:** the `### Review Findings` section of [[3-2-docker-runner-adapter-lifecycle-timeout-heartbeat-lease-expiry-cleanup-idempotent-restart]] and `_bmad-output/implementation-artifacts/deferred-work.md` (heading `## Deferred from: code review of story-3.2 (2026-05-28)`).
- **Already applied in the 3.2 working tree** (do NOT redo; just cover with tests): recovery "Running" re-arm + `HeartbeatTouched` clamp; `terminate`→`UNKNOWN` on unconfirmed stop; `recoverHandle` safe-poll; empty-log no-growth baseline; Trap-T3 ms-precision truncation; recovery container selection prefers running; Trap-T7 orphan-dir invalid-id → preserve.
- **Still open from review (this story fixes them so the tests can pass):** HIGH stage-starvation; HIGH dangling-sweep safety; AC9/T13 retry event-id anchor; detectHeartbeat ConcurrentHashMap atomicity + recover re-seed; event double-emission gate; non-atomic workspace delete; `processHeartbeatStale` null handling.

## Acceptance Criteria

1. **Fast-tier stale-detection unit tests** (`RunnerBrokerStaleDetectionUnitTest`, Mockito, no Docker, no Spring context) cover story-3.2 AC10 (d) + (e):
   - (d) seed a `running` row with `last_activity_at = now() - 3h`, `timeoutFor(stage) = 10min`; call `broker.scanForStaleExecutions()`; assert the row flips to `orphaned`, a `runner.orphaned` event is appended with `details.failureCategory = "orphan"` / `details.reason = "lease_expired"`, and `workflowTransitionService` / `driveWorkflowFailed` is **NOT** invoked (AC3 sub-bullet (c) posture — orphan is left to Epic 4 recovery).
   - (e) seed a row past `1 × timeout` but inside `2 × timeout`; run the scan **twice**; assert exactly **one** `runner.heartbeatStale` event total (Trap T4 idempotency via `heartbeat_stale_emitted_at`), and the row's status is unchanged (WARN-only).

2. **Stale-scan stage-starvation fix + regression test** (closes 3.2 review HIGH `edge#2 / blind#1 / auditor F8`). The current `scanForStaleExecutions` runs the `findStaleByStatusInAndLastActivityAtBefore(ACTIVE_STATUSES, window, batchSize)` query per `RunnerStage` value but the query is **not stage-scoped** and is `LIMIT batchSize`, then discards rows with `snapshot.stage() != stage` in-memory — so a backlog of one stage starves the other every tick. Fix server-side:
   - Add a `RunnerStage stage` predicate to the port method (becomes `findStaleByStatusInAndStageAndLastActivityAtBefore(statuses, stage, window, limit)` OR an overload — keep the existing signature if other callers depend on it) so the `LIMIT` applies per-stage.
   - Implement in `RunnerExecutionPersistenceAdapter` + `RunnerExecutionRepository` (JPQL adds `and runnerExecution.stage = :stage`).
   - `RunnerBrokerStaleDetectionUnitTest` adds a case: seed `batchSize + 5` `investigation` stale rows + 3 `execution` stale rows; assert all 3 `execution` rows are processed in a single `scanForStaleExecutions()` (no starvation).

3. **Workspace-cleanup Testcontainers tests** (`RunnerWorkspaceCleanupJobIT`, Testcontainers Postgres for the DB row + `@TempDir` `deliveryline.home` for the workspace; no Docker engine) cover story-3.2 AC10 (i) + (j) + (k):
   - (i) row with `completed_at = now() - 25h`, status terminal, workspace dir present → `sweepWorkspaces()` deletes the dir AND sets `archived_at`.
   - (j) row with `completed_at = now() - 2h` → dir NOT deleted AND `archived_at` stays null.
   - (k) `rex_orphan_xyz` dir with **no** DB row → dir preserved + one WARN `workspace orphan dir found ... action=preserve` (list-appender). Add a second sub-case: a `rex_`-prefixed-but-**malformed** dir name → still preserved with `reason=invalid_id` (covers the applied Trap-T7 fix).
   - Add a Trap-T16 case: a row with `completed_at = now()-25h` AND `status = running` is **NOT** returned by `findCompletedBeforeAndNotArchived` (SQL status guard).

4. **Dangling-container sweep safety fix + tests** (closes 3.2 review HIGH `blind#9 / edge#5 / auditor F4`). Before tests can pass, fix `RunnerWorkspaceCleanupJob.sweepDanglingContainers` + the gateway:
   - (a) **min-age guard** — add a `RunnerProperties.Docker.danglingContainerMinAgeSeconds` (default e.g. `120`) and skip any container whose `createdAt` is within the min-age window when the DB row is absent, so a container in the dispatch→row-insert window is never destroyed. `createdAt` is already on `DanglingContainerInfo`.
   - (b) **status normalization** — `DefaultDockerEngineGateway.listContainersByLabel` must populate `DanglingContainerInfo.status()` from a normalized engine state (`getState()` returns `running|exited|created|paused|dead`), not the human `getStatus()` ("Up 3 minutes"); the sweep's `running|paused|restarting` match then works and a running container is `stop`ped before `rm`.
   - (c) **two-pass / force-aware removal** (AC6 (b)) — for a still-running container, `stop` (10s) then `rm` only after a poll observes it exited (or use `force` deliberately), avoiding the stop-then-rm 409 race.
   - Tests: `DockerRunnerDanglingContainerCleanupIT` (`@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`) for AC10 (l) — launch an `alpine:3.20` container with the 5 `deliveryline.*` labels but no DB row, run the sweep, assert it is stopped then removed and unrelated containers are untouched; PLUS a fast Mockito unit test for the min-age guard (no engine) asserting a `createdAt = now()-30s` rowless container is preserved while a `now()-10m` one is removed.

5. **Broker-restart recovery ITs** (`DockerRunnerLifecycleRecoveryIT`, `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`) cover story-3.2 AC10 (f) + (g):
   - (f) dispatch via Docker; simulate JVM restart by emptying `rexIdToContainerId` and invoking `recoverOnStartup`; assert the container id is recovered via the label filter, the row stays `running`, **its lease is re-armed** (`last_activity_at` advanced — covers the applied D2 deviation), and subsequent `pollActiveExecutions` drives it normally.
   - (g) same but the container already wrote `output/runner-result.v1.json` and exited → `recoverOnStartup` ingests via `onResult`; row → `completed`.
   - Add a fast Mockito unit case (AC10 (h)) for "no container matches the label filter" → row → `orphaned` with `RUNNER_ORPHANED` (NOT `RECOVERY_RECONCILED`-only) — Trap T5/T6 (scratch-replay before docker probe; `instanceof RecoverableRunnerAdapter` never invoked under mock).

6. **Timeout-enforcement IT** (`DockerRunnerLifecycleTimeoutIT`, `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`) covers story-3.2 AC10 (a): dispatch `alpine:3.20` running `sh -c "trap '' TERM; sleep 3600"` (deliberately ignores SIGTERM so the kill path is exercised — Trap T15) with a 5s stage-timeout override; drive `broker.scanForTimeouts()` manually; assert `docker stop` then `docker kill` is issued (terminate outcome `KILLED_AFTER_GRACE`), `docker inspect` shows the container exited within ~12s, `runner_executions.status = timed_out`, and a `runner.timeout` event is appended (NOT `runner.failed`). Trap T1: a heartbeat that bumps `timeout_at` mid-scan must NOT trigger the kill (assert no stop invocation in that case — Mockito unit variant if the IT can't force the race).

7. **Heartbeat-detection ITs** (`DockerRunnerLifecycleHeartbeatIT`, `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`) cover story-3.2 AC10 (b) + (c):
   - (b) test image writes to `/workspace/logs/runner.stdout` every 2s without exiting; `pollActiveExecutions` observes size growth across ≥2 ticks → `HeartbeatTouched` → `touchActivity` extends `timeout_at`; row never enters `timed_out` despite passing the original `timeout_at`; assert `last_activity_at` advances ≥ twice.
   - (c) test image touches `/workspace/output/heartbeat.touch` every 2s; same activity-advance assertion.
   - Add fast unit coverage for the applied detectHeartbeat fixes: an **empty** `runner.stdout` does NOT register as growth on first poll; ms-precision timestamps compare monotonically.

8. **detectHeartbeat residual hardening** (closes 3.2 review MED `blind#4 / edge#3b`):
   - (a) make the `rexIdToLastLogObservation` read-modify-write **atomic** (use `ConcurrentHashMap.compute`/`merge`) so concurrent polls cannot regress the high-water mark; add a focused concurrency unit test (two threads polling the same rex; assert the stored observation never regresses).
   - (b) re-seed (or clear) `rexIdToLastLogObservation` inside `recoverHandle` so a broker restart does not re-emit `HeartbeatTouched(stale startedAt)` for a long-running recovered container; assert via unit test.

9. **Event double-emission gate + null-activity handling** (closes 3.2 review MED `blind#7 / edge#12 / auditor F6` and LOW `blind#2`):
   - Gate the `RUNNER_ORPHANED` / `RUNNER_COMPLETED` event append on the row **actually** transitioning (e.g. only append when `recordOrphaned` / `recordCompleted` reports a real state change, or guard with a re-read under lock), so recovery replay re-entering `onResult` or a concurrent scan-vs-recovery race cannot append duplicate lifecycle events. Add a unit test that invokes the orphan path twice and asserts a single `runner.orphaned` event.
   - `processHeartbeatStale`: handle a null `lastActivityAt` explicitly (do not silently conflate it with "past orphan threshold").

10. **AC9 / T13 retry event-id anchor — production wiring + contract test** (`RecoveryServiceDockerRetryContractTest`, light `@SpringBootTest` slice with a mocked docker gateway; covers story-3.2 AC10 (n) + Trap T12 + T13, closes 3.2 review `auditor F7` / decision D3):
   - **Production wiring:** thread the new `runner.dispatched` event public id (emitted by `RunnerBroker.appendRunnerDispatchedEventIfDocker` under `runners.docker`) back into `RetryRecoveryResult.recoveryRetriedEventPublicId`. Today the event is appended outside the dispatch transaction with no id returned to the caller, so the anchor is currently unsatisfiable — this story adds the return/propagation path through `RecoveryService` → `WorkflowCommandService.retryWorkflow` → `RunnerBroker.dispatch`.
   - **Test:** run `retry` against a `timed_out` (and an `orphaned`) `runner_executions` row under `runners.docker`; assert (a) a NEW `runner_executions` row with a distinct `rex_*` id is dispatched, (b) the original row's `status` is unchanged (`timed_out`/`orphaned`) and its fields are preserved verbatim, (c) `RetryRecoveryResult.recoveryRetriedEventPublicId` carries the `runner.dispatched` event id (NOT legacy `runner.started`). No real Docker.

11. **Logging contract test** (`DockerRunnerLifecycleLoggingContractTest`, Logback list-appender per the `RunnerLoggingContractTest` template from story 1.19) covers story-3.2 AC10 (q) and **closes the open story-3.1 `P-Logging-Contract` review item**. Pin ≥ one assertion per new log branch: timeout-stop, timeout-kill (terminate outcome), heartbeat-stale-warn, orphan-warn, workspace cleanup-deleted, workspace orphan-dir-preserve, dangling-container removed, recovery `resumed via=docker_probe`, recovery `no-container-found`. Forbidden in output: workspace bytes, credential-bearing image tags (assert `DockerLogSanitizer.redactImageTag` is applied).

12. **Registry + schema contract coverage** (story-3.2 AC10 (o) + (p)):
   - Extend / verify `RegistryContractTest` asserts all five new `WorkflowEventType` values (`runner.dispatched`, `runner.heartbeatStale`, `runner.timeout`, `runner.orphaned`, `runner.completed`) are registered and match the wire-form fixture.
   - Add the directed `RunnerPropertiesTest` (does NOT currently exist) covering the new binding fields + positive-checks: `staleScanIntervalMs`, `pollIntervalMs`, `Docker.workspaceCleanupIntervalMs`, and (new in this story) `Docker.danglingContainerMinAgeSeconds`.
   - Extend `FlywaySchemaContractTest` to assert the `heartbeat_stale_emitted_at TIMESTAMPTZ NULL` column exists on `runner_executions` (story-3.2 V10 migration).

13. **Label-propagation IT extension** (story-3.2 AC10 (m)) — extend the existing `DockerRunnerAdapterContainerLifecycleIT` (story 3.1) to assert the FULL 5-label set on a dispatched container, including the new `deliveryline.stage` label.

14. **OpenAPI / `schema.d.ts` real-boot verification** (closes 3.2 review defer `auditor F16`). Story 3.2 hand-edited `openapi.json` + frontend `schema.d.ts`. This story re-generates them via `scripts/regen-openapi.{sh,ps1}` per the cross-shell pattern in memory [[openapi-regen-platform-shim]], confirms `OpenApiSnapshotContractTest` passes on a **real springdoc boot**, and confirms the frontend `schema.d.ts` drift contract from story 2-13 stays green. If the regen produces any diff vs. the hand-edit, commit the regenerated artifacts.

15. **CI tiering preserved** (Trap T14) — the docker-tagged ITs (entries (a), (b), (c), (f), (g), (l) and the label-set extension) join the existing opt-in `docker-runner-it` tier (`@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`); they do NOT join the foundation/fast gate. All other entries (units, Testcontainers, contract slices) are fast-tier. The WSL2 Ubuntu native parity smoke per memory [[wsl-linux-ci-reproduction]] MUST be run before pushing (Windows-vs-Linux `docker stop`/`docker kill` signal differences).

## Tasks / Subtasks

- [ ] **Task 1: Production hardening the tests depend on** (AC: 2, 4, 8, 9, 10)
  - [ ] AC2 — stage-scoped stale query: new/overloaded `RunnerExecutionRecordPort` method + `RunnerExecutionPersistenceAdapter` + `RunnerExecutionRepository` JPQL `and stage = :stage`; rewire `RunnerBroker.scanForStaleExecutions` to query per-stage so `LIMIT` is per-stage.
  - [ ] AC4 — `RunnerProperties.Docker.danglingContainerMinAgeSeconds` (default 120; extend record + `Docker.defaults()` + `application.yml` `deliveryline.runner.docker.dangling-container-min-age-seconds: 120`); min-age guard in `sweepDanglingContainers`; normalize `DanglingContainerInfo.status()` from `getState()` in `DefaultDockerEngineGateway.listContainersByLabel`; two-pass/force-aware removal.
  - [ ] AC8 — `DockerRunnerAdapter.detectHeartbeat` atomic map update (`compute`); re-seed/clear `rexIdToLastLogObservation` in `recoverHandle`.
  - [ ] AC9 — gate `RUNNER_ORPHANED`/`RUNNER_COMPLETED` append on actual transition; explicit null-`lastActivityAt` handling in `processHeartbeatStale`.
  - [ ] AC10 — thread `runner.dispatched` event id into `RetryRecoveryResult.recoveryRetriedEventPublicId` (`RunnerBroker.dispatch` → `WorkflowCommandService.retryWorkflow` → `RecoveryService`).
- [ ] **Task 2: Fast-tier unit + Testcontainers tests** (AC: 1, 2, 3, 8, 9)
  - [ ] `RunnerBrokerStaleDetectionUnitTest` — AC10 (d), (e) + stage-starvation regression (AC2).
  - [ ] `RunnerWorkspaceCleanupJobIT` (Testcontainers Postgres + `@TempDir`) — AC10 (i), (j), (k) + Trap-T16 + invalid-id-preserve.
  - [ ] Dangling min-age guard unit test (Mockito, no engine).
  - [ ] detectHeartbeat unit tests — empty-log no-growth, ms-precision monotonic, concurrent-map non-regression, recover re-seed.
  - [ ] Event double-emission + null-activity unit tests.
- [ ] **Task 3: Docker-tagged engine ITs** (AC: 4, 5, 6, 7, 13) — `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable`, in new package `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/lifecycle/`
  - [ ] `DockerRunnerLifecycleTimeoutIT` (AC10 a).
  - [ ] `DockerRunnerLifecycleHeartbeatIT` (AC10 b, c).
  - [ ] `DockerRunnerLifecycleRecoveryIT` (AC10 f, g) + recovery-no-container unit (AC10 h).
  - [ ] `DockerRunnerDanglingContainerCleanupIT` (AC10 l).
  - [ ] Extend `DockerRunnerAdapterContainerLifecycleIT` with the 5-label assertion (AC10 m).
- [ ] **Task 4: Contract slices + directed tests** (AC: 10, 11, 12)
  - [ ] `RecoveryServiceDockerRetryContractTest` (AC10 n + T12/T13).
  - [ ] `DockerRunnerLifecycleLoggingContractTest` (AC10 q; closes 3-1 P-Logging-Contract).
  - [ ] Extend/verify `RegistryContractTest` (AC10 o); add `RunnerPropertiesTest`; extend `FlywaySchemaContractTest` (V10 column).
- [ ] **Task 5: OpenAPI regen + verification** (AC: 14)
  - [ ] Run `scripts/regen-openapi.sh` then `scripts/regen-openapi.ps1` per [[openapi-regen-platform-shim]]; confirm `OpenApiSnapshotContractTest` + the frontend `schema.d.ts` drift contract are green on a real boot; commit any regenerated diff.
- [ ] **Task 6: Cross-platform smoke + close-out** (AC: 15)
  - [ ] WSL2 Ubuntu native run of the `docker-runner-it` tier per [[wsl-linux-ci-reproduction]] before pushing; verify the `timed_out` flip relies on a non-null `exitCode` after the kill.
  - [ ] On green, flip story 3.2 Status → `done` and tick off its remaining `### Review Findings` action items; update `sprint-status.yaml`.
  - [ ] Update `docs/runner-workspace-layout.md` to document the AC4 recovery-re-arm deviation, the dangling-container min-age guard, and the two-pass stop-then-rm.
- [ ] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] Any NEW production code paths added here (stage-scoped query, min-age guard, status normalization, retry event-id propagation) get SLF4J structured logs at entry/exit + every decision branch, parameterized, carrying `runnerExecutionId` / `workflowRunId` / `containerId` via `MdcKeys`. The `DockerRunnerLifecycleLoggingContractTest` (AC11) pins them.
  - [ ] No `System.out` / `printStackTrace`; never log workspace bytes or credential-bearing image tags (reuse `DockerLogSanitizer`).

## Dev Notes

### Scope

This story owns: the **full story-3.2 AC10 test surface (a–q)**; the production fixes those tests require but that were left open by the 3.2 code review (stage-starvation, dangling-sweep safety, detectHeartbeat atomicity/re-seed, event double-emission gate, `processHeartbeatStale` null, and the **AC9/T13 retry event-id anchor wiring**); the `danglingContainerMinAgeSeconds` config; the new `RunnerPropertiesTest`; the `FlywaySchemaContractTest` V10 column assertion; and the real-boot OpenAPI verification. On green it flips story 3.2 to `done`.

This story does **NOT** own: any NEW lifecycle behavior beyond what story 3.2 specified; Codex/Claude runner images (3-3/3-4 — test image stays `alpine:3.20`); secret env injection (3-5); log capture/redaction copy-out (3-6); the real-runner contract IT against actual agent images (3-8); repository workspace/git mount (3-9). It does not add new `WorkflowEventType` values, migrations, or OpenAPI surface beyond verifying 3.2's.

### Architectural anchors (DO NOT REINVENT)

- **Already-applied 3.2 fixes** (in the uncommitted working tree — cover with tests, do not re-implement): recovery "Running" re-arm + `HeartbeatTouched` clamp (`RunnerBroker.processOrphan`); `terminate`→`UNKNOWN` on unconfirmed stop + `recoverHandle` `safeRecoverPoll` + empty-log baseline `0` (`DockerRunnerAdapter`); Trap-T3 `truncatedTo(MILLIS)` (`LocalRunnerWorkspaceStore`); recovery container selection prefers `running` (`DefaultDockerEngineGateway`); Trap-T7 invalid-id → `action=preserve` (`RunnerWorkspaceCleanupJob`).
- **`EnabledIfDockerAvailable`** (`deliveryline-backend/src/test/java/org/dradgo/adapters/runner/EnabledIfDockerAvailable.java`) — reuse the story-3.1 JUnit condition for every docker-tagged IT.
- **`docker-runner-it` tag** — already used by `DockerRunnerAdapterContainerLifecycleIT` + `DockerRunnerAdapterUnitTest`; the CI tier filter from story 3-1 already excludes it from the foundation gate (Trap T14 — confirm `.github/workflows/ci.yml` is unchanged).
- **`RunnerLoggingContractTest`** (`application/runner`) — the list-appender template for `DockerRunnerLifecycleLoggingContractTest`. Other examples: `RecoveryLoggingContractTest`, `ArtifactLoggingContractTest`.
- **`FlywaySchemaContractTest`** + **`RegistryContractTest`** (`org/dradgo/contract/`) — extension targets for AC12.
- **`OpenApiSnapshotContractTest`** (`adapters/rest`) — boots the app and byte-compares `/v3/api-docs` to committed `openapi.json`; the AC14 gate.
- **`RunnerWorkspaceCleanupJob`** (`application/runner`) is unit-test-friendly (constructor-injected `ObjectProvider<DockerHostPort>`; mock the provider to no-op the engine for fast tests, or supply a stub for the dangling unit test).
- **`RecoverableRunnerAdapter`** SPI (`application/runner/spi`) — broker stays adapter-type-agnostic via `instanceof RecoverableRunnerAdapter`; `MockRunnerAdapter` implements it as no-ops. Recovery/timeout tests that don't need Docker should drive the broker against a Mockito `RecoverableRunnerAdapter`.
- **Testcontainers Postgres** is already wired (`TestcontainersConfiguration`); `RunnerWorkspaceCleanupJobIT` reuses it for the DB row while using `@TempDir` for the on-disk workspace (no Docker engine needed for that IT).

### Trap registry (carried from story 3.2 — verify in tests)

- **T1** — heartbeat-race guard before `docker stop` (AC6 here / 3.2 AC1).
- **T4** — `runner.heartbeatStale` at most once per stale-window (`heartbeat_stale_emitted_at`); AC1(e).
- **T5/T6** — docker probe only under `RecoverableRunnerAdapter`; scratch-replay before probe; AC5 unit case.
- **T7** — never delete a `rex_*` dir lacking a DB row; AC3(k).
- **T9** — workspace pass before dangling pass; assert ordering via list-appender in AC11.
- **T10** — `RUNNER_STARTED` retained (mock); `RUNNER_TIMEOUT`/`ORPHANED`/`COMPLETED` replace the generic `RUNNER_FAILED` emissions; AC1/AC6/AC12.
- **T11** — OpenAPI schema-enum + `schema.d.ts` in the same change; AC14.
- **T12/T13** — retry yields fresh `rex_*` id + original row unchanged + `runner.dispatched` event-id anchor; AC10.
- **T14** — docker ITs opt-in only; AC15.
- **T15** — WSL2 native verify; test image must ignore SIGTERM (`trap '' TERM; sleep 3600`) so the kill path is exercised; AC6/AC15.
- **T16** — cleanup SQL guards `status IN (completed, failed, timed_out, orphaned)`; AC3.

### Project Structure Notes

- New test package: `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/lifecycle/` for the docker-tagged ITs (groups them for the `@Tag` filter).
- Fast units live next to their production class per existing convention: `RunnerBrokerStaleDetectionUnitTest` + `RecoveryServiceDockerRetryContractTest` under `application/runner`; `RunnerPropertiesTest` under `application/runner`; cleanup-job IT under `application/runner`; `FlywaySchemaContractTest`/`RegistryContractTest` under `contract/`.
- Production edits stay in the same files story 3.2 touched (no new production packages). The only new production config field is `danglingContainerMinAgeSeconds` on the existing `RunnerProperties.Docker`.
- No new Maven dependencies — docker-java + Testcontainers landed in story 3.1.
- ArchUnit rules from 3.1/3.2 keep holding (cleanup job stays in `application.runner`; docker-java types stay behind the gateway; `application` reaches the engine via `DockerHostPort` per [[application-cannot-import-adapters]]).

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback; no `System.out` / `printStackTrace()`.
- New branches added in Task 1 (stage-scoped query, min-age skip, status normalization, retry event-id propagation) must log at the levels story 3.2 established (INFO lifecycle, WARN recoverable anomaly, ERROR unhandled). Required context keys via `MdcKeys`: `runnerExecutionId`, `workflowRunId`, `containerId`, `runnerKind`, `failureCategory`, `lastActivityAt`, `timeoutAt`.
- **Forbidden:** workspace file bytes, credential-bearing image tags (use `DockerLogSanitizer.redactImageTag`).
- **Test contract:** `DockerRunnerLifecycleLoggingContractTest` (AC11) pins each new/changed log branch.

### References

- [Source: _bmad-output/implementation-artifacts/3-2-docker-runner-adapter-lifecycle-timeout-heartbeat-lease-expiry-cleanup-idempotent-restart.md#Acceptance-Criteria] — AC10 (a–q) is the canonical test list; the `### Review Findings` section is the canonical open-action list.
- [Source: _bmad-output/implementation-artifacts/deferred-work.md#Deferred-from-code-review-of-story-3.2-2026-05-28] — the deferred test surface, OpenAPI verification, and markArchived note.
- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.2] — primary AC1–AC10 spec.
- [Source: _bmad-output/planning-artifacts/architecture.md#L187-188] — real Dockerized runner containers covered by a separate contract/integration suite verifying lifecycle, timeout, heartbeat, malformed output, duplicate result, failure normalization.
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/runner/EnabledIfDockerAvailable.java] — docker-availability JUnit condition (3.1).
- [Source: deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerLoggingContractTest.java] — list-appender logging-contract template (1.19).
- [Source: deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java] — schema-contract extension target.
- [Source: deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java] — event-type registry contract.
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java] — OpenAPI snapshot gate (2-13).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java] — `scanForStaleExecutions` / `processOrphan` / `dispatch` (stage-scope + retry-anchor edits).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerWorkspaceCleanupJob.java] — dangling-sweep min-age + two-pass edits.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGateway.java] — `listContainersByLabel` status normalization.
- Memory: [[wsl-linux-ci-reproduction]] — WSL2 native verify for the docker-runner-it tier before pushing.
- Memory: [[openapi-regen-platform-shim]] — cross-shell `regen-openapi.{sh,ps1}` for AC14.
- Memory: [[verify-ci-fixes-in-clean-env]] — local green ≠ CI green; reproduce in clean env before claiming done.
- Memory: [[application-cannot-import-adapters]] — `DockerHostPort` / `RecoverableRunnerAdapter` SPI boundary holds.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date       | Change                                                                       |
| ---------- | ---------------------------------------------------------------------------- |
| 2026-05-28 | Story created via `bmad-create-story` as the test-surface + review-hardening follow-up to story 3.2 (code-review decision D1; `backlog → ready-for-dev`). Owns the deferred AC10 test surface (a–q), the open 3.2 review action items the tests pin (stage-starvation, dangling-sweep safety, AC9/T13 anchor, detectHeartbeat/event/cleanup hardening), and the real-boot OpenAPI verification. |
