# Story 3.17a: Runner Execution Queue Substrate — Schema, Enqueue/Dequeue (SKIP LOCKED), Backpressure

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer preparing the runner stack for concurrent execution,
I want the PostgreSQL-backed `RunnerExecutionQueue` substrate — the `queued` status + queue columns, the `enqueue`/`dequeue` (`FOR UPDATE SKIP LOCKED`) data API, backpressure, and config — built and tested **in isolation, without yet rewiring the live dispatch path**,
so that the risky persistence/SQL/registry/contract surface lands and is proven first, and story 3.17b can then activate it (worker pool + caller refactor) against a known-good substrate without the system ever stalling.

## Context & Central Reconciliation (READ FIRST)

**This is the first of a two-slice split of epic story 3.17 (RunnerExecutionQueue + Configurable Worker Pool), the largest story in Epic 3.** The split keeps each slice independently mergeable and green:

- **3.17a (this story) — the SUBSTRATE.** Build the `V12` migration, `QUEUED` status, `runner.queued` event, `RunnerExecutionQueue.enqueue/dequeue` (SKIP LOCKED), `RUNNER_QUEUE_FULL` backpressure, config, package-placement ArchUnit, and ADR 0006. **The 7 production dispatch callers are NOT touched — the system stays on the existing synchronous `RunnerBroker.dispatch` path.** The queue is built + fully unit/IT-tested but **dormant in production** (no production caller of `enqueue` yet). This is the house "build-the-seam-before-the-call-site" pattern (exactly how 3.15 built `ARTIFACT_PR_LINK_MISMATCH` before story 3.20, and 3.16 built the post-commit hook before its 3.20 trigger).
- **3.17b — the ACTIVATION.** Worker pool (`ThreadPoolTaskExecutor`), `LISTEN/NOTIFY`, the `dispatch→enqueue` refactor of all 7 callers, graceful shutdown, lease reclamation, and the activation IT suite. **3.17b depends on 3.17a.**

**This is an out-of-slice pull from epic-3b (which remains `deferred`)** — like 3-7/3-8/3-10/3-11/3-12/3-15/3-16. **Do NOT flip `epic-3b`'s status.** Read `3-16-linear-completion-sync-...md` for the house discipline (Reconciliation-first, Scope Boundary, Decisions, OQ, Traps, three-sites).

**HEADLINE RECONCILIATION — The migration is `V12`, not `V4`.** Epic AC1 says "Flyway V4 migration". **V4 was consumed long ago** (`V4__artifact_operation_single_pending.sql`); the chain is at **V11**. The next free version is **`V12__add_queue_state_columns.sql`**. `runner_executions` already carries `heartbeat_stale_emitted_at` (V10) and `raw_output_*` (V11); none of `worker_id`/`dispatched_at`/`queue_priority`/`queue_attempt_count` exists yet (verified).

**WHY a dormant queue is safe + correct here.** `enqueue`/`dequeue` are pure data operations testable directly from test threads — the SKIP-LOCKED concurrency proof, backpressure boundary, and correlationId persistence ITs need NO worker pool, just ≥2 racing connections. Landing the substrate dormant means 3.17b's behavioral refactor (the risky 7-caller `dispatch→enqueue` switch + async pool) runs against a substrate already proven under concurrency.

**STRUCTURAL FACTS (verified against current code — confirm line numbers before editing; they drift):**

1. **`runner_executions` schema + migrations.** `CREATE` in `V1__create_workflow_core_tables.sql` (table ~`:206-234`); status `CHECK ck_runner_executions_status IN ('pending','running','completed','failed','timed_out','orphaned')` (`:224-226`); index `idx_runner_executions_workflow_run_id_status_created_at` (`:331`). Altered by `V10` (`heartbeat_stale_emitted_at`) and `V11` (`raw_output_*`, `redaction_count`). **Next free Flyway version: `V12`.**

2. **`RunnerExecutionStatus` registry** (`domain/registry/RunnerExecutionStatus.java`): `PENDING/RUNNING/COMPLETED/FAILED/TIMED_OUT/ORPHANED`. **No `QUEUED` yet.** Enum⇔DB-CHECK parity pinned by `RegistryContractTest` (`:161-162`, `extractConstraintValues("ck_runner_executions_status")`) — a **foundation-gate (Docker) test**, so a `queued` status missing from the `V12` CHECK reds the gate, NOT `mvnw test` (Trap T3).

3. **`WorkflowEventType` registry** (`domain/registry/WorkflowEventType.java`): runner events `runner.started/dispatched/completed/failed/timeout/orphaned/heartbeatStale`. **No `runner.queued` yet.** Adding it requires mirroring the wire value into `src/test/resources/contracts/events/workflow-event-types.fixture.json` (`workflowEventTypes`) — pinned by `RegistryContractTest` (`:294`). Per [[new-workfloweventtype-fixture-sites]]: also check the fixture-stream `eventType` enum; committed `openapi/openapi.json` enum is NOT auto-derived (regen byte-identical).

4. **Persistence** is Spring Data JPA (`RunnerExecutionPersistenceAdapter` + `RunnerExecutionRepository`; SPI `application/runner/spi/RunnerExecutionRecordPort.java`). `findByPublicIdForUpdate` uses `@Lock(PESSIMISTIC_WRITE)` (plain `FOR UPDATE`) but **no SKIP LOCKED anywhere**. A raw-SQL `NamedParameterJdbcTemplate` precedent exists in `WorkflowRunPersistenceAdapter` — use it for the SKIP-LOCKED dequeue (JPA `@Lock` cannot emit `SKIP LOCKED`, Trap T12).

5. **Config** `RunnerProperties` (`application/runner/RunnerProperties.java`, prefix `deliveryline.runner`, record with normalize-but-validating compact ctor + `defaults()`; min/max clamp precedent in nested `Docker`, `:415-464`). yaml: `src/main/resources/application.yml` (`deliveryline.runner.*` `:148-231`) **and** `src/test/resources/application.yml` (`:49-116`) — the test yaml **shadows, not merges** ([[validated-config-needs-test-yaml]]).

6. **Three-sites error-code pattern.** `DomainErrorCode` (e.g. `RUNNER_TIMEOUT` retryable precedent) + `ProblemDetailsCatalog` (`ProblemDetailsMetadata(status,title,retryable,typeUri)`; a parity check fails fast if an enum lacks a catalog entry) + `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`.

7. **MDC (story 1.19)** `application/observability/MdcKeys.java`: `CORRELATION_ID`/`WORKFLOW_RUN_ID`/`RUNNER_EXECUTION_ID` constants; `beginScope`/`endScope`/`sanitizeForLog`. correlationId reaches enqueue via the `ActorContext`/`systemActor(correlationId)` the callers already build (callers untouched here; 3.17a just persists the value when called from tests).

8. **ArchUnit layering** (`architecture/ArchitectureRuleCatalog.java`): `LAYERED_BOUNDARIES` (`:75-97`, Application may only be accessed by Adapters/Infrastructure; may NOT reach `adapters.*`/`infrastructure.*`), `ADAPTER_PACKAGE_LAYOUT` (`:99-115`). New package `application.runner.queue` is Application-layer.

9. **ADR directory** `docs/adr/`: `0001-0004` + `0019-0023` exist; **`0006` is free**. Format model: `0004-spec-stage-orchestration.md`.

## Scope Boundary — what 3.17a BUILDS vs DEFERS-to-3.17b

| Concern | 3.17a | Note |
|---|---|---|
| `V12__add_queue_state_columns.sql` — widen `status` CHECK to add `queued`; add `dispatched_at`, `worker_id`, `queue_priority NOT NULL DEFAULT 100`, `queue_attempt_count NOT NULL DEFAULT 0`, `correlation_id text NULL`; index `(status, queue_priority, created_at)` | **BUILD** | epic AC1 — V12 (R1) |
| `RunnerExecutionStatus.QUEUED("queued")` + V12 CHECK + `RegistryContractTest` parity | **BUILD** | foundation gate |
| `RunnerExecutionQueue` (`application.runner.queue`): `enqueue(...)` (writes `queued` row + `runner.queued` event) + `dequeue(workerId)` (native `FOR UPDATE SKIP LOCKED`) | **BUILD** | epic AC2 |
| `runner.queued` `WorkflowEventType` + fixture parity | **BUILD** | [[new-workfloweventtype-fixture-sites]] |
| Backpressure: `queue-max-depth` (default 100) → `RUNNER_QUEUE_FULL` w/ `details.currentDepth`+`maxDepth` (three-sites) | **BUILD** | epic AC7 (D5) |
| `correlationId` persisted on the `queued` row at enqueue | **BUILD** | epic AC8 (first half) |
| Config: `deliveryline.runner.queue-max-depth` (+ test yaml mirror) | **BUILD** | epic AC7 |
| ADR `docs/adr/0006-runner-queue-shared-pool.md` | **BUILD** | epic AC6 — 0006 free (D7) |
| ArchUnit: `RunnerExecutionQueue` lives in `application.runner.queue` (layer placement) | **BUILD** | epic AC11 (first half) |
| ITs: enqueue+dequeue happy, SKIP-LOCKED concurrency, backpressure, correlationId persist | **BUILD** | epic AC12 (subset) |
| **Switching the 7 callers to `enqueue`** (`WorkflowOrchestrationService`×6 + `RecoveryService.retry`) | **DEFER → 3.17b** | callers stay synchronous here |
| `RunnerWorkerPool` (`ThreadPoolTaskExecutor`), backoff loop, worker MDC restore | **DEFER → 3.17b** | epic AC3, AC8(2nd half) |
| `LISTEN/NOTIFY` low-latency wakeup | **DEFER → 3.17b** | epic AC4 |
| Relocate `RunnerBroker.dispatch` body + no-direct-dispatch ArchUnit | **DEFER → 3.17b** | epic AC5 |
| Worker-crash lease reclamation (extend stale scan) | **DEFER → 3.17b** | epic AC9 |
| Graceful shutdown `SmartLifecycle` | **DEFER → 3.17b** | epic AC10 |
| `worker-pool-size`/backoff/`worker-pool.enabled` config | **DEFER → 3.17b** | pool-specific |
| `RunnerBroker.onResult` + `@Scheduled pollActiveExecutions` (DISPATCH→RESULT harvest) | **REUSE UNCHANGED** | not this slice (or 3.17b) |

## Acceptance Criteria

> Derived from `epic-03-agent-execution.md` §"Story 3.17" (lines 344–357), scoped to the substrate slice, with **binding clarifications** in **bold parentheticals**.

1. **Given** the `runner_executions` table, **Then** Flyway **`V12__add_queue_state_columns.sql`** widens `ck_runner_executions_status` to include `queued` and adds `dispatched_at timestamptz NULL`, `worker_id text NULL`, `queue_priority integer NOT NULL DEFAULT 100`, `queue_attempt_count integer NOT NULL DEFAULT 0`, `correlation_id text NULL`, plus index `(status, queue_priority, created_at)`. `RunnerExecutionStatus.QUEUED("queued")` is added and `RegistryContractTest` enum⇔CHECK parity holds. **(R1: V12 not V4. `ALTER TABLE … DROP CONSTRAINT ck_runner_executions_status … ADD CONSTRAINT … CHECK (status IN ('pending','running','queued','completed','failed','timed_out','orphaned'))`. Extend `RunnerExecutionEntity`/`RunnerExecutionSnapshot` + adapter mapping with the new columns. Trap T3 — parity is foundation-gate, not `mvnw test`.)**

2. **Given** the `application.runner.queue` package, **Then** `RunnerExecutionQueue` exposes `enqueue(workflowRunId, stage, contextBundleRef, idempotencyKey, correlationId, queuePriority) → QueuedRunnerExecution` (writes a `runner_executions` row with `status='queued'`, persists `correlationId` + `queue_priority`, appends a `runner.queued` event) and `dequeue(workerId) → Optional<RunnerExecution>` using `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1 ORDER BY queue_priority ASC, created_at ASC`. **(D2: reinterpret `contextBundleRef` — the bundle is NOT composed at enqueue (composition clones+summarizes repos; it belongs on the bounded worker thread in 3.17b). `enqueue` writes a lightweight `queued` row only. `dequeue` is a native `NamedParameterJdbcTemplate` `UPDATE runner_executions SET status='running', worker_id=:w, dispatched_at=now(), queue_attempt_count=queue_attempt_count+1 WHERE id = (SELECT id FROM runner_executions WHERE status='queued' ORDER BY queue_priority ASC, created_at ASC FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING …` — JPA `@Lock` cannot emit SKIP LOCKED, Trap T12. In 3.17a nothing in production calls these; they are exercised by tests only — dormant seam.)**

3. **Given** the event registry, **Then** a `runner.queued` `WorkflowEventType` is added (appended by `enqueue`) with fixture parity. **(Add `WorkflowEventType.RUNNER_QUEUED("runner.queued")` before the final constant; mirror into `workflow-event-types.fixture.json` + fixture-stream enum; openapi snapshot regen is byte-identical. Keep `RegistryContractTest` green — foundation gate, Trap T13.)**

4. **Given** backpressure, **Then** `application.yml` `deliveryline.runner.queue-max-depth` (default 100) caps queued items; `enqueue` beyond the cap raises `RUNNER_QUEUE_FULL` with `details.currentDepth` + `details.maxDepth` and writes NO row. **(D5: `DomainErrorCode.RUNNER_QUEUE_FULL` three-sites — enum + `ProblemDetailsCatalog` (`HttpStatus.SERVICE_UNAVAILABLE`, **retryable=true**, mirror `RUNNER_TIMEOUT`) + `registry-api-schema-placeholders.json` ([[new-domainerrorcode-three-sites]], Trap T4). `enqueue` counts current `queued` rows under its write tx and throws BEFORE inserting when `currentDepth >= maxDepth`. The "transient / run stays in prior state" semantics are realized at the caller in 3.17b; here the error simply propagates from `enqueue`.)**

5. **Given** correlation propagation per story 1.19, **Then** `enqueue` accepts and persists the originating `correlationId` on the `queued` row (`correlation_id` column). **(The worker MDC restore is 3.17b; here just persist the value so 3.17b's worker can read it back. Never log the value unsanitized — `MdcKeys.sanitizeForLog`.)**

6. **Given** ArchUnit boundary, **Then** `RunnerExecutionQueue` resides in `application.runner.queue` (Application layer — may depend on `domain`/`application.*`/`application.runner.spi`, NOT `adapters.*`/`infrastructure.*`). **(D4 first half. The caller-restriction rules — who may call enqueue/dequeue/dispatch — land in 3.17b where the callers are wired; in 3.17a `enqueue`/`dequeue` have no production callers so a restriction rule would be vacuous. Layer placement holds via the existing `LAYERED_BOUNDARIES`/`ADAPTER_PACKAGE_LAYOUT` rules; add a focused package rule only if the new subpackage needs an explicit allow.)**

7. **Given** one shared pool (the architecture decision motivating the whole queue), **Then** `docs/adr/0006-runner-queue-shared-pool.md` records it (Postgres-only, SKIP-LOCKED, one shared pool / no per-stage pools, LISTEN/NOTIFY-as-optimization). **(D7: 0006 is free; match `0004` heading format. The shared pool is realized in 3.17b but the decision is documented now, up front.)**

8. **Given** the test suite, **Then** integration tests cover: enqueue + dequeue happy path; FOR-UPDATE-SKIP-LOCKED prevents two concurrent `dequeue` picking the same row; backpressure raises `RUNNER_QUEUE_FULL`; correlationId persisted on the queued row. **(Testcontainers Postgres 17.2; `*IT`, `@Tag("integration")` — Trap T7. The SKIP-LOCKED test needs ≥2 real connections racing `dequeue`. Unit-test priority ordering + backpressure boundary with no DB where possible.)**

**Logging instrumentation** (cross-cutting; see task below) — `INFO` on enqueue (with `queue_priority`, resulting `currentDepth`) + dequeue (worker id, leased rex); `WARN` on backpressure rejection (`RUNNER_QUEUE_FULL` with depths). Carry `correlationId`, `workflowRunId`, `runnerExecutionId`. Never log bundle bytes/secrets.

## Tasks / Subtasks

- [x] **Task 1 — V12 migration + `QUEUED` status + entity/snapshot columns** (AC: #1, #5)
  - [x] `src/main/resources/db/migration/V12__add_queue_state_columns.sql`: drop+re-add `ck_runner_executions_status` widened with `'queued'`; add `dispatched_at`, `worker_id`, `queue_priority NOT NULL DEFAULT 100`, `queue_attempt_count NOT NULL DEFAULT 0`, `correlation_id text NULL`; `CREATE INDEX idx_runner_executions_queue_pickup ON runner_executions (status, queue_priority, created_at)`.
  - [x] Add `RunnerExecutionStatus.QUEUED("queued")`. Verify `RegistryContractTest` enum⇔CHECK parity (foundation gate, Trap T3) — GREEN.
  - [x] Extend `RunnerExecutionEntity` + `RunnerExecutionSnapshot` + `RunnerExecutionPersistenceAdapter` mapping with the new columns (kept the 16-arg snapshot ctor as a shim → only the mapper changed, no 37-site fan-out).

- [x] **Task 2 — `RunnerExecutionQueue.enqueue/dequeue`** (AC: #2, #4, #5)
  - [x] New `org.dradgo.application.runner.queue.RunnerExecutionQueue` + `QueuedRunnerExecution` record. `enqueue(...)` mints `PublicIdPrefixes.RUNNER_EXECUTION`, writes a `queued` row (persisting `correlationId` + `queuePriority`), appends `runner.queued` (Task 3). Backpressure (AC4): count `queued` rows under the write tx; throw `DomainException(RUNNER_QUEUE_FULL, {currentDepth,maxDepth})` BEFORE insert when at cap (D5).
  - [x] `dequeue(workerId)` → native `NamedParameterJdbcTemplate` `UPDATE … RETURNING public_id` with the `FOR UPDATE SKIP LOCKED LIMIT 1 ORDER BY queue_priority ASC, created_at ASC` sub-select (D2/Trap T12), then JPA re-read. Added the SPI methods (`insertQueued`/`countQueued`/`dequeueNext`) on `RunnerExecutionRecordPort` + impl in `RunnerExecutionPersistenceAdapter` (threaded `NamedParameterJdbcTemplate` via ctor — no test fan-out, the adapter is only Spring-constructed).

- [x] **Task 3 — `runner.queued` event + fixture parity** (AC: #3)
  - [x] `WorkflowEventType.RUNNER_QUEUED("runner.queued")` + mirror into `workflow-event-types.fixture.json` + fixture-stream enum ([[new-workfloweventtype-fixture-sites]]) + added to `RunnerExecutionEventPersistenceAdapter` permitted set. openapi snapshot byte-identical (OpenApiSnapshotContractTest GREEN). `RegistryContractTest` GREEN.

- [x] **Task 4 — `RUNNER_QUEUE_FULL` three-sites + config** (AC: #4)
  - [x] `DomainErrorCode.RUNNER_QUEUE_FULL` + `ProblemDetailsCatalog` (`SERVICE_UNAVAILABLE`, retryable=true) + `registry-api-schema-placeholders.json` ([[new-domainerrorcode-three-sites]], Trap T4). Verified `-Pfoundation-gate` (the `RUNNER_QUEUE_FULL status=503` mapping fires in the ProblemDetails contract).
  - [x] Added `deliveryline.runner.queue-max-depth: 100` to `RunnerProperties` (clamp ≥1 in the compact ctor) + `defaults()` + `src/main/resources/application.yml` AND `src/test/resources/application.yml` ([[validated-config-needs-test-yaml]], Trap T5). Accepted the known ~13-site `new RunnerProperties(...)` fan-out ([[runnerproperties-record-component-fanout]]).

- [x] **Task 5 — ArchUnit package placement** (AC: #6)
  - [x] Added `RUNNER_EXECUTION_QUEUE_LIVES_IN_APPLICATION_RUNNER_QUEUE` (mirror of the approval/clarification `*_LIVES_IN_*` rules) + registered it in `ArchitectureBoundaryTest`. Verified via the Failsafe architecture run (45/45). (Caller-restriction rules deferred to 3.17b.)

- [x] **Task 6 — ADR 0006** (AC: #7)
  - [x] `docs/adr/0006-runner-queue-shared-pool.md` (Postgres-only, SKIP-LOCKED, one shared pool, LISTEN/NOTIFY-as-optimization, no per-stage pools, dormant-substrate-first). Matches `0004` format; notes the pool itself is realized in 3.17b.

- [x] **Task 7 — Tests** (AC: #8)
  - [x] `RunnerExecutionQueueIT` (`@Tag("integration")`, Testcontainers Postgres): enqueue+dequeue+correlationId round-trip; two concurrent `dequeue` never pick the same row (SKIP LOCKED, real connections via `CyclicBarrier`+`ExecutorService`); single-row exactly-once; backpressure → `RUNNER_QUEUE_FULL`; priority+age ordering. 5/5 GREEN against real Postgres.
  - [x] `RunnerExecutionQueueTest` (unit): mint/persist/event, backpressure-writes-nothing, dequeue delegation, input validation. `RunnerExecutionQueueLoggingContractTest` (list-appender) for enqueue/dequeue/backpressure surfaces. `RunnerPropertiesTest` clamp + default assertions.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J + MDC on enqueue/dequeue/backpressure (parameterized; `correlationId`/`workflowRunId` MDC scope around enqueue; `INFO` lifecycle, `WARN` backpressure). Each surface pinned by `RunnerExecutionQueueLoggingContractTest`. No bundle bytes / secrets logged (correlationId sanitized via `MdcKeys.sanitizeForLog`).

## Dev Notes

### THE references that matter most

| Concern | File to mirror | Why |
|---|---|---|
| **Native SKIP-LOCKED SQL** | `adapters/persistence/WorkflowRunPersistenceAdapter.java` (`NamedParameterJdbcTemplate` raw mutations); `adapters/persistence/repository/RunnerExecutionRepository.java:18-25` (`@Lock` precedent — insufficient for SKIP LOCKED) | AC2 dequeue (Trap T12). |
| **Migration shape** | `V10__runner_executions_heartbeat_stale.sql`, `V11__add_runner_raw_output_columns.sql` (ALTER + CHECK) | AC1 — V12. |
| **Status registry + parity** | `domain/registry/RunnerExecutionStatus.java`; `src/test/java/org/dradgo/contract/RegistryContractTest.java:161-162` | AC1 — foundation gate. |
| **Event registry + fixtures** | `domain/registry/WorkflowEventType.java`; `contracts/events/workflow-event-types.fixture.json`; `RegistryContractTest:294` | AC3. |
| **Three-sites** | `domain/registry/DomainErrorCode.java` (`RUNNER_TIMEOUT` retryable); `adapters/rest/ProblemDetailsCatalog.java` (`ProblemDetailsMetadata`, parity check); `registry-api-schema-placeholders.json` | AC4. |
| **Config** | `application/runner/RunnerProperties.java:26,62-124,415-464` (clamp); `application.yml:148-231` + `src/test/resources/application.yml:49-116` | AC4. |
| **Persistence SPI** | `application/runner/spi/RunnerExecutionRecordPort.java`; `adapters/persistence/RunnerExecutionPersistenceAdapter.java`; `RunnerExecutionRepository.java` | AC1/AC2 columns + dequeue. |
| **MDC** | `application/observability/MdcKeys.java` | AC5 correlationId persist + sanitize. |
| **ArchUnit** | `architecture/ArchitectureRuleCatalog.java:75-97,99-115` | AC6. |
| **IT harness** | `src/test/java/org/dradgo/TestcontainersConfiguration.java` (Postgres 17.2); a `*IT` with `@Import(...)`+`@SpringBootTest`+`@Tag("integration")` | AC8. |
| **ADR format** | `docs/adr/0004-spec-stage-orchestration.md` (0006 free) | AC7. |
| **House discipline** | `3-16-…-linear-completion-sync.md`; `3-15-…-github-pr-linkage.md` (build-the-seam-before-the-call-site) | dormant-seam pattern. |

### Decisions (made by this story; rationale)

- **D0 — Land the queue substrate DORMANT; do not touch the 7 dispatch callers (the split boundary).** `enqueue`/`dequeue` are built + tested in isolation; production still dispatches synchronously via `RunnerBroker.dispatch`. 3.17b wires callers + the pool. This keeps the system green between merges and de-risks 3.17b. Same pattern as 3.15/3.16 (guard/hook before its call-site).
- **D2 — Bundle composed at DISPATCH (3.17b worker), not at ENQUEUE.** AC2's `contextBundleRef` is reinterpreted: enqueue writes a cheap `queued` row; composition (repo clone/summarize) runs on the bounded worker thread later.
- **D3 — Reuse `RUNNING` for the leased state; track the lease via `worker_id`+`dispatched_at`.** AC1 only adds `queued`; `dequeue` flips the row off `queued` under the row lock. No separate `dispatched` status.
- **D5 — `RUNNER_QUEUE_FULL` is transient/retryable (503).** Mirror `RUNNER_TIMEOUT`. The "run stays in prior state" caller behavior is realized in 3.17b; here the error propagates from `enqueue`.
- **D7 — ADR 0006 (free); documents the shared-pool decision up front** even though the pool is built in 3.17b.

### Open Questions (each carries a recommendation — proceed unless the architect objects)

- **OQ-1 — `correlation_id` new column vs reuse.** No existing `runner_executions` column carries the originating correlationId (it is resolved from MDC at result time today). **Recommend** adding `correlation_id text NULL` in V12 (AC5) so 3.17b's worker can restore it deterministically without re-deriving.
- **OQ-3 — Idempotency reservation at enqueue vs dispatch.** Today `RunnerBroker.dispatch` reserves the idempotency key up front. **Recommend** 3.17a's `enqueue` does NOT reserve (it just persists the key on the row); reservation stays where the dispatch body runs (3.17b worker), preserving today's replay semantics. Confirm — if a duplicate enqueue should replay to the existing `queued` rex, move the reservation into enqueue instead. Flagged for the 3.17b author too.
- **OQ-2 — ADR number 0006 vs 0024.** Epic says 0006 (free, fills a gap); recent ADRs cluster 0019–0023. **Recommend** 0006 (epic-traceable).

### Traps (wiring hazards — each maps to a memory or a verified code fact)

- **T3 — `QUEUED` ⇔ V12 CHECK parity is a FOUNDATION-GATE (Docker) test** ([[verify-ci-fixes-in-clean-env]]) — `RegistryContractTest:161-162` runs only under the Docker tier; a missing `'queued'` passes `mvnw test` and reds the gate.
- **T4 — New `DomainErrorCode` → three sites** ([[new-domainerrorcode-three-sites]]) for `RUNNER_QUEUE_FULL`; the `ProblemDetailsCatalog` parity check fails fast if you add the enum without the catalog entry. Verify `-Pfoundation-gate`.
- **T5 — Validated-config / test-yaml shadow** ([[validated-config-needs-test-yaml]]) — mirror `queue-max-depth` into `src/test/resources/application.yml` or every `@SpringBootTest` reds at binding.
- **T7 — `*IT` naming + Testcontainers** ([[springboot-testcontainers-test-must-be-IT]]) — name Postgres tests `*IT` (Failsafe), `@Tag("integration")`; not `@Tag("docker-runner-it")` (no runner images needed) ([[docker-it-needs-exact-docker-runner-it-tag]]).
- **T9 — ArchUnit runs in Failsafe, not Surefire** ([[archunit-runs-in-failsafe-not-surefire]]) — verify package rule via `failsafe:integration-test -Dit.test=**/architecture/**/*Test`.
- **T12 — SKIP LOCKED needs native SQL** — JPA `@Lock(PESSIMISTIC_WRITE)` emits `FOR UPDATE` WITHOUT `SKIP LOCKED`; use `NamedParameterJdbcTemplate` raw SQL.
- **T13 — `runner.queued` → fixture sites** ([[new-workfloweventtype-fixture-sites]]) — mirror the wire value into the fixture + fixture-stream enum; openapi regen byte-identical. Foundation-gate `RegistryContractTest`.
- **T11 — `application/…` cannot import `adapters.…`** ([[application-cannot-import-adapters]]) — the queue reaches persistence via the SPI port, never `adapters.runner.*` directly.
- **T15 — Run gates via PowerShell** ([[rtk-hook-only-matches-bash]]); reproduce Docker/foundation tiers in a clean env / WSL2 ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback. No `System.out`/`printStackTrace()`. ADR `0019-structured-logging`.
- **Surface:** `RunnerExecutionQueue.enqueue/dequeue`. `INFO` lifecycle (enqueue with depth/priority, dequeue with worker+rex), `WARN` backpressure reject, `ERROR` only unexpected.
- **Required keys:** `correlationId`, `workflowRunId`, `runnerExecutionId`. **Forbidden:** bundle bytes, secrets, host paths.
- **Test contract:** new surfaces pinned by a focused list-appender assertion.

### Project Structure Notes

- Backend `deliveryline-backend/`, base `org.dradgo`, Java 21, Spring Boot 4.0.6.
- New package **`org.dradgo.application.runner.queue`** — `RunnerExecutionQueue`, `QueuedRunnerExecution`.
- Native dequeue → new method on `RunnerExecutionRecordPort` impl (`RunnerExecutionPersistenceAdapter`) using `NamedParameterJdbcTemplate`.
- Registries → `domain/registry/RunnerExecutionStatus.java` (+`QUEUED`), `WorkflowEventType.java` (+`RUNNER_QUEUED`), fixtures.
- Error code → `DomainErrorCode` + `ProblemDetailsCatalog` + `registry-api-schema-placeholders.json` (add only `RUNNER_QUEUE_FULL`).
- Migration → `src/main/resources/db/migration/V12__add_queue_state_columns.sql`.
- Config → `RunnerProperties` + `application.yml` (main + test) — `queue-max-depth` only (pool config is 3.17b).
- ADR → `docs/adr/0006-runner-queue-shared-pool.md`.
- **Do NOT touch:** the 7 dispatch callers, `RunnerBroker.dispatch`/`onResult`, the `@Scheduled` poller — all 3.17b or unchanged.

### Verification commands (PowerShell — [[rtk-hook-only-matches-bash]])

- Focused unit: `mvnw -pl deliveryline-backend test -Dtest=RunnerExecutionQueueTest,RunnerPropertiesTest`.
- Registry/three-sites contracts: `mvnw -pl deliveryline-backend test -Dtest=*Registry*,*ProblemDetails*` (event fixture + `RUNNER_QUEUE_FULL` — `QUEUED` CHECK parity needs the Docker tier below).
- ArchUnit (Failsafe): `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=**/architecture/**/*Test`.
- Queue ITs: `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=*Queue*IT`.
- Foundation gate (Docker — `RegistryContractTest` parity + three-sites): `mvnw -pl deliveryline-backend -Pfoundation-gate verify -Dtest=ZzzNone -Dsurefire.failIfNoSpecifiedTests=false`.
- Static: `mvnw -pl deliveryline-backend spotless:apply checkstyle:check`.
- WSL2 Linux smoke of the foundation gate ([[wsl-linux-ci-reproduction]] / [[verify-ci-fixes-in-clean-env]]).

### References

- Epic: [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.17] — ACs 1, 2, 6, 7, 8 (substrate subset), lines 344–357. Companion slice: **3.17b** (activation).
- Predecessor house pattern: [Source: _bmad-output/implementation-artifacts/3-16-linear-completion-sync-...md], [Source: …/3-15-integration-link-service-extended-for-github-pr-linkage.md] — out-of-slice epic-3b pull; build-the-seam-before-the-call-site.
- Persistence: `application/runner/spi/RunnerExecutionRecordPort.java`; `adapters/persistence/RunnerExecutionPersistenceAdapter.java`; `adapters/persistence/repository/RunnerExecutionRepository.java:18-25`; `adapters/persistence/WorkflowRunPersistenceAdapter.java` (NamedParameterJdbcTemplate).
- Registries + contracts: `domain/registry/RunnerExecutionStatus.java`; `domain/registry/WorkflowEventType.java`; `contracts/events/workflow-event-types.fixture.json`; `RegistryContractTest.java:161-162,294`; `DomainErrorCode.java`; `ProblemDetailsCatalog.java`; `registry-api-schema-placeholders.json`.
- Config + ArchUnit + ADR: `RunnerProperties.java:26,62-124,415-464`; `application.yml:148-231` + `src/test/resources/application.yml:49-116`; `architecture/ArchitectureRuleCatalog.java:75-97,99-115`; `docs/adr/0004-spec-stage-orchestration.md`.
- Migrations: `V1__create_workflow_core_tables.sql:206-234,331`, `V10__…`, `V11__…` (next free **V12**).
- IT harness: `src/test/java/org/dradgo/TestcontainersConfiguration.java` (Postgres 17.2).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context) — bmad-create-story 2026-06-11 (split of 3.17 into 3.17a/3.17b).

### Debug Log References

- Full fast unit tier (Surefire, no Docker): **943 run, 0 failures, 11 skipped** — proves the `QUEUED` enum addition, the 21-field `RunnerExecutionSnapshot`, the 17th `RunnerProperties` component, and the openapi snapshot are regression-free across the whole backend.
- Docker tier (Testcontainers 28.5.1): `RunnerExecutionQueueIT` **5/5** + `ArchitectureBoundaryTest` **45/45** (`jacoco-check` tripped only because the run was a subset — not a test failure).
- Foundation gate (`-Pfoundation-gate verify`): **31 run, 0 failures, 1 skipped** — `RegistryContractTest` `QUEUED`⇔`V12` CHECK parity + `runnerExecutionStatuses` placeholder + `runner.queued` fixture, `ProblemDetails` `RUNNER_QUEUE_FULL`→503, Flyway V12 applies cleanly on a fresh DB.
- Static: `spotless:check` + `checkstyle:check` clean (exit 0).
- Verification used PowerShell + quoted `-D` args ([[rtk-hook-only-matches-bash]]); Docker/foundation tiers run via the lifecycle `verify` phase (not the `failsafe:`/`surefire:` direct goals — [[maven-arglineation-goal-crash]]).

### Completion Notes List

- **Dormant substrate landed (D0).** The queue is built + fully tested but has NO production caller — the 7 dispatch callers, `RunnerBroker.dispatch`/`onResult`, and the poller were left untouched. Story 3.17b activates it.
- **HEADLINE: V12 was correct and intentional.** The story's "V12 not V4" reconciliation held — `V13__add_implementation_loop_columns.sql` (story 3.21) already existed and its own header states *"V12 is claimed by story 3-17a"*. V12 fills the reserved gap; on a fresh DB V1→V12→V13 apply in order (foundation gate confirms clean apply). No Flyway out-of-order config needed.
- **Snapshot fan-out avoided.** Kept the existing 16-arg `RunnerExecutionSnapshot` constructor as a shim delegating to the new 21-arg canonical, so the 37 snapshot-builder sites compiled unchanged — only the mapper was edited.
- **`RunnerProperties` fan-out accepted.** Per [[runnerproperties-record-component-fanout]] the ~13 full-arg `new RunnerProperties(...)` sites are unavoidable for any positional record component; updated all 8 test files + `defaults()`. A delegating shim ctor was rejected (would create `@ConfigurationProperties` constructor-binding ambiguity, [[two-public-constructors-need-autowired]]). `queue-max-depth` is clamped to ≥1 (coerce) and mirrored into the test yaml (validated → shadowed, [[validated-config-needs-test-yaml]]).
- **`@Component`, not `@Service`.** `RunnerExecutionQueue` uses `@Component` (like `RunnerBroker`) because the `APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES` ArchUnit rule pins `@Service` beans to a `*Service`/`*Orchestrator` suffix.
- **SKIP LOCKED is native SQL (Trap T12).** `dequeue` is a `NamedParameterJdbcTemplate` `UPDATE … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING public_id`, then a JPA re-read of the leased row (avoids hand-mapping 21 columns + the workflow_run join). `NamedParameterJdbcTemplate` threaded into the existing adapter ctor — no test fan-out (the adapter is only Spring-constructed). The IT proves two racing workers never pick the same row, and exactly-one on a single row.
- **`idempotencyKey` accepted but NOT reserved (OQ-3); `contextBundleRef` accepted but NOT composed/persisted (D2)** — both are forward-compat inputs for the 3.17b worker; in 3.17a they are only validated non-blank. `correlationId` IS persisted on the row (AC5) and read back in the IT (AC8).
- **`QUEUED` enum addition is broad-but-safe.** No exhaustive switch breaks (the only `switch (status)` sites are on `String`/`RunnerPollStatus`, not `RunnerExecutionStatus`); the broker's recover-on-startup scan uses an explicit `PENDING/RUNNING` list (`ACTIVE_STATUSES`), so queued rows are never recovered.

### File List

**Created — main:**
- `deliveryline-backend/src/main/resources/db/migration/V12__add_queue_state_columns.sql`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/queue/RunnerExecutionQueue.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/queue/QueuedRunnerExecution.java`
- `docs/adr/0006-runner-queue-shared-pool.md`

**Modified — main:**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/RunnerExecutionStatus.java` (`QUEUED`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java` (`RUNNER_QUEUED`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (`RUNNER_QUEUE_FULL`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/RunnerExecutionEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionSnapshot.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/RunnerExecutionEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionRecordPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/RunnerExecutionPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/RunnerExecutionEventPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java`
- `deliveryline-backend/src/main/resources/application.yml`

**Created — test:**
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/queue/RunnerExecutionQueueTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/queue/RunnerExecutionQueueLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/queue/RunnerExecutionQueueIT.java`

**Modified — test:**
- `deliveryline-backend/src/test/resources/application.yml`
- `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json`
- `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerPropertiesTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerLogCaptureServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowOrchestrationServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterContainerLifecycleIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/lifecycle/DockerLifecycleITSupport.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStoreTest.java`

## Change Log

| Date | Version | Description | Author |
|---|---|---|---|
| 2026-06-14 | 0.1 | Implemented the dormant RunnerExecutionQueue substrate: V12 migration (`queued` status + queue columns), `RunnerExecutionQueue.enqueue/dequeue` (FOR UPDATE SKIP LOCKED), `runner.queued` event, `RUNNER_QUEUE_FULL` three-sites + `queue-max-depth` config, ADR 0006, ArchUnit placement, and unit/logging/IT coverage. All gates green (943 fast, 5 queue ITs, 45 ArchUnit, 31 foundation, static clean). Status → review. | Amelia (dev-story) |

## Review Findings

> bmad-code-review 2026-06-14 — 3 adversarial layers (Blind Hunter + Edge Case Hunter + Acceptance Auditor) over the working-tree diff (1835 lines incl. new queue files). **Acceptance Auditor: all 8 ACs MET, zero scope creep, dormant seam genuinely dormant.** No Critical/High confirmed bugs. Triage: 1 decision-needed, 3 patch, 3 defer, 8 dismissed (2 verified false positives). Each High verified against source before classifying (3.11/3.12 false-premise discipline).

### Decision-Needed (RESOLVED → patched)

- [x] [Review][Decision→Patch] `enqueue` input-validation policy was inverted — rejected blank forward-compat params but left the live ordering key unbounded. **Alex chose option 1:** bound `queuePriority >= 0` AND relax `contextBundleRef`/`idempotencyKey` to nullable-accepted (D2/OQ-3 say they are accepted-but-unused, and 3.17b composes the bundle at dispatch so a caller may enqueue before a ref exists). APPLIED: added `queuePriority >= 0` guard before any port interaction; dropped the two `requireNonBlank` calls; javadoc updated; unit tests rewritten (`enqueueRejectsNegativeQueuePriorityBeforeAnyWrite` + `enqueueAcceptsNullForwardCompatBundleAndIdempotencyInputs`). [RunnerExecutionQueue.java:105-113]

### Patch (APPLIED)

- [x] [Review][Patch] Dequeue ORDER BY had no deterministic tiebreaker — added `, id asc` so FIFO-within-priority is guaranteed on exact (priority, created_at) ties [RunnerExecutionPersistenceAdapter.java DEQUEUE_SQL]
- [x] [Review][Patch] `workerId` logged unsanitized while `correlationId` is sanitized — now wrapped with `MdcKeys.sanitizeForLog(workerId)` in both dequeue log sites for log-injection parity [RunnerExecutionPersistenceAdapter.java:dequeueNext, RunnerExecutionQueue.java:dequeue]
- [x] [Review][Patch] Stale `@TestPropertySource` comment in test yaml — corrected to state the IT seeds 100 rows against the default cap (no `@TestPropertySource`) [src/test/resources/application.yml]

**Patch verification (PowerShell — [[rtk-hook-only-matches-bash]], [[maven-arglineation-goal-crash]]):** `RunnerExecutionQueueTest` 7/0 + `RunnerExecutionQueueLoggingContractTest` 3/0 + `RunnerPropertiesTest` 15/0 (25 run, 0 fail) via the `test` lifecycle phase; `spotless:apply` + `checkstyle:check` clean (0 violations), BUILD SUCCESS. Docker/foundation tiers unchanged (no SQL-shape change beyond the ORDER BY tiebreaker; recommend a WSL2/Linux clean-env `RunnerExecutionQueueIT` + `-Pfoundation-gate` confirm before merge per [[verify-ci-fixes-in-clean-env]]/[[wsl-linux-ci-reproduction]]).

### Deferred

- [x] [Review][Defer] `dequeueNext` lease + JPA re-read not atomic without an ambient tx [RunnerExecutionPersistenceAdapter.java:286] — deferred; safe today (the only path, `queue.dequeue`, is `@Transactional` so the lease + re-read are read-your-writes in one tx). Annotate `@Transactional` on `dequeueNext` when 3.17b wires direct callers.
- [x] [Review][Defer] `QUEUED` rows absent from the timeout/stale-scan + broker active-status sets [RunnerExecutionStatus.java + stale-scan callers] — deferred; harmless while dormant (no prod enqueue). 3.17b must add queued-row lease reclamation/timeout or a stuck `queued` row permanently consumes backpressure depth.
- [x] [Review][Defer] 21-arg positional `RunnerExecutionSnapshot` ctor (+16-arg shim) is brittle [RunnerExecutionSnapshot.java] — deferred, accepted tech-debt ([[runnerproperties-record-component-fanout]]); a mid-list field insertion shifts positional meaning with no compile error. Consider a builder/named factory.

### Dismissed (8)

- Backpressure count-then-insert can momentarily over-admit past `queue-max-depth` — intentional + ADR-0006-documented ("approximate, not a hard safety invariant"); AC4 met.
- V12 migration not idempotent (no `IF [NOT] EXISTS`) — Flyway guarantees single execution; matches every existing V-migration's style.
- IT `seedQueuedRows` bypasses `insertQueued` via raw `generate_series` — acceptable fast seed for a pure count/backpressure test.
- `countQueued` annotated `@Transactional(readOnly=true)` — benign; joins the `enqueue` write tx under default `REQUIRED`, so count + insert still decide atomically.
- Null `correlationId` into `MdcKeys.beginScope` — **verified false positive**; `beginScope`/`sanitizeForLog` are null-safe (no-op write on null/empty).
- `correlationId` leaked at INFO — **verified false positive**; it is only pushed to MDC (sanitized), never placed in a log message argument.
- JPA re-read after native dequeue UPDATE reads a stale first-level cache — handled; `queue.dequeue` loads nothing before the lease, so the persistence context is cold for that row.
- `resultingDepth = currentDepth + 1` reporting skew under concurrent enqueues — by-design approximate (same accepted basis as the backpressure note).
