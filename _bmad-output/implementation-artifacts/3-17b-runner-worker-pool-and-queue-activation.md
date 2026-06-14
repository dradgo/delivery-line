# Story 3.17b: Runner Worker Pool + Queue Activation — Worker Pool, dispatch→enqueue Refactor, LISTEN/NOTIFY, Graceful Shutdown, Lease Reclamation

Status: done

> **⛔ BLOCKED ON 3.17a — implement 3.17a (`3-17a-runner-execution-queue-substrate-and-backpressure.md`) FIRST.** This slice activates the substrate 3.17a builds (the `queued` status, `RunnerExecutionQueue.enqueue/dequeue`, `RUNNER_QUEUE_FULL`, the V12 columns, `correlation_id`). Its context is fully authored here so it can start the moment 3.17a closes; do not begin until then.

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a workflow operator running multiple governed tickets in parallel,
I want the configurable `RunnerWorkerPool` (a Spring `ThreadPoolTaskExecutor`) to drive every dispatch off the 3.17a queue via `dequeue` — with low-latency `LISTEN/NOTIFY` wake-up, graceful-shutdown draining, and worker-crash lease reclamation — and **all 7 production dispatch callers switched from synchronous `RunnerBroker.dispatch` to `RunnerExecutionQueue.enqueue`**,
so that multiple tickets flow through the workflow concurrently with bounded resource use, no direct-dispatch path remains, and the architecture's deferred queue-runner decision is fully satisfied on Postgres alone.

## Context & Central Reconciliation (READ FIRST)

**This is the second of a two-slice split of epic story 3.17.** Slice 3.17a built the queue **substrate dormant** (callers untouched, system on the old synchronous path). **3.17b ACTIVATES it** — and is the behavioral, higher-risk half: it rewires 7 callers, introduces async worker threads, and adds net-new runtime plumbing. Read `3-17a-...md` first (its Structural Facts + Decisions are prerequisites and are not all repeated here).

**This remains an out-of-slice epic-3b pull (epic-3b stays `deferred`).** Read `3-16-linear-completion-sync-...md` for house discipline.

**HEADLINE RECONCILIATION 1 — This story lands LAST among the dispatch callers, so it RETROFITS the queue under 7 already-wired synchronous callers.** The epic's execution-order note ("must merge before 3.11/3.12/3.14/3.15") was written when those were unbuilt; they are all `done` and call `RunnerBroker.dispatch(...)` synchronously, expecting a `RunnerDispatchResult` back. After the switch to `enqueue`, the outcome is a `QueuedRunnerExecution` (the real dispatch happens later on a worker thread). **Tracing + reconciling every consumer of the 6 orchestration methods' return values (CLI submit output, approval flows) so they report "queued" not "dispatched" is the single highest-risk task — Trap T1, the silent breaker.**

**HEADLINE RECONCILIATION 2 — The queue replaces the ENQUEUE→DISPATCH leg only; `RunnerBroker.onResult` + the `@Scheduled pollActiveExecutions` (DISPATCH→RESULT harvest) stay UNCHANGED.** Today's flow: `dispatch()` composes the bundle, inserts a `PENDING` row, calls `adapter.dispatch` (non-blocking ack); a separate `@Scheduled pollActiveExecutions` (`RunnerConfiguration:51`) polls and drives `onResult`. **The worker pool replaces only the synchronous portion of `dispatch()`** — the bulk of `dispatch()`'s body (`RunnerBroker.java:385-664`: idempotency reserve, repo-context resolve, bundle compose, insert, `adapter.dispatch`, `runner.dispatched` event) **relocates to the worker's per-item action**, run on the bounded pool. `onResult` (`:700`) and the poller are untouched (Trap T2).

**HEADLINE RECONCILIATION 3 — None of the runtime plumbing exists; it is all net-new.** Verified absent across the whole repo: `LISTEN`/`NOTIFY`/`PGConnection` (zero), `ThreadPoolTaskExecutor`/`@Async`/`@EnableAsync` (zero), `SmartLifecycle`/graceful-drain (only a cleanup-only `@PreDestroy`). The `NOTIFY` rides a `TransactionSynchronizationManager.registerSynchronization` `afterCommit` hook (in-repo precedent `WorkflowTransitionService:159-184`) on a **dedicated long-lived listener connection** (you cannot hold a pooled Hikari connection open to `LISTEN`).

**STRUCTURAL FACTS (verified; confirm line numbers before editing — they drift):**

1. **The 7 synchronous dispatch callers** (all call `RunnerBroker.dispatch(workflowRunId, stage, idempotencyKey, actor)` → `RunnerDispatchResult`):
   - `WorkflowOrchestrationService.dispatchSpecGeneration` (`:212`, INVESTIGATION) — submit path.
   - `WorkflowOrchestrationService.retrySpecGeneration` (`:267`, INVESTIGATION) — spec-reject re-dispatch.
   - `WorkflowOrchestrationService.dispatchPlanGeneration` (`:454`, EXECUTION) → `dispatchExecutionInternal` (`:628`).
   - `WorkflowOrchestrationService.retryPlanGeneration` (`:479`, EXECUTION).
   - `WorkflowOrchestrationService.dispatchImplementation` (`:531`, EXECUTION) — pr-output (story 3.12).
   - `WorkflowOrchestrationService.retryImplementation` (`:556`, EXECUTION).
   - `RecoveryService.retry` (`:418`, runs OUTSIDE a tx — audit trail durable BEFORE dispatch, story 1.18; dispatch key `…+":runner"` `:415`).
   All six orchestration methods gated by `autoDispatchEnabled()`/`planAutoDispatchEnabled()`/`implementationAutoDispatchEnabled()`.

2. **`RunnerBroker.dispatch` body** (`application/runner/RunnerBroker.java:385-664`) — the logic to relocate to the worker action: idempotency reserve (`:401`), repo-context resolve + bundle compose (`createForSpecInvestigation`/`create`, `:449-520`), `recordPort.insertPending` (`:588`) inside `dispatchTransactionTemplate` (REQUIRED), `RUNNER_STARTED` (mock) / `RUNNER_DISPATCHED` (docker via `appendRunnerDispatchedEventIfDocker`) event, `runnerAdapter.dispatch(request)` (`:641`). Tx template factories `requiredTemplate`/`requiresNewTemplate` (`:367-379`). **`onResult` (`:700`) is the harvest path — LEAVE IT ALONE.**

3. **Stale detection / orphan reclamation (story 3.2)** in `RunnerBroker`: `scanForTimeouts()` (`:2036`, `ACTIVE_STATUSES=[PENDING,RUNNING]` where `timeout_at < now()`), `scanForStaleExecutions()` (`:2174`, per-stage two-phase: `runner.heartbeatStale` WARN at 1× stage-timeout then `ORPHANED` flip at `staleThresholdMultiplier`×, default 2.0, via `last_activity_at`), `recoverOnStartup()` (`:2446`, `@EventListener(ApplicationReadyEvent.class)`). `RunnerExecutionService` owns guarded transitions (`recordOrphaned` etc). **AC-reclamation extends `scanForStaleExecutions` for leased-but-crashed rows (D6).**

4. **From 3.17a (substrate this slice consumes):** `RunnerExecutionStatus.QUEUED`, `RunnerExecutionQueue.enqueue(...)/dequeue(workerId)`, `runner.queued` event, `RUNNER_QUEUE_FULL` (503 retryable), V12 columns `dispatched_at`/`worker_id`/`queue_priority`/`queue_attempt_count`/`correlation_id`, `deliveryline.runner.queue-max-depth`, ADR 0006, `application.runner.queue` package.

5. **Config + scheduling** `RunnerProperties` (`application/runner/RunnerProperties.java`, clamp precedent in nested `Docker` `:415-464`, `defaults()`). `@Scheduled` beans in `RunnerConfiguration` (`@EnableScheduling`), each gated `if (!runnerProperties.scheduling().enabled()) return;`. yaml `application.yml` (`:148-231`) + `src/test/resources/application.yml` (`:49-116`, shadows).

6. **MDC (story 1.19)** `application/observability/MdcKeys.java`: `beginScope(key,value)→prior`/`endScope(key,prior)`/`sanitizeForLog`. The worker thread starts with an empty MDC — restore `correlationId`(from the row's `correlation_id`)+`workflowRunId`+`runnerExecutionId` for the dispatch duration (AC8).

7. **RunnerAdapter SPI** (`application/runner/spi/RunnerAdapter.java`): `RunnerDispatchAck dispatch(RunnerDispatchRequest)` — non-blocking, idempotent on `runnerExecutionId`. `RecoverableRunnerAdapter` (docker) overrides `emitsDispatchedAfterAck()=true`. `DockerRunnerAdapter.dispatch` (`adapters/runner/DockerRunnerAdapter.java:188`) idempotent.

8. **Tx post-commit hook** `WorkflowTransitionService:159-184` (`registerSynchronization`/`afterCommit`) — the NOTIFY precedent. **ArchUnit** `ArchitectureRuleCatalog`: caller-restriction pattern `ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE` (`:361-376`), `ONLY_ORCHESTRATION_AUTO_ADVANCES…` (`:390-405`).

9. **Net-new runtime, all absent today:** `ThreadPoolTaskExecutor`/`@Async`/`@EnableAsync` (zero), `LISTEN`/`NOTIFY`/`PGConnection` (zero), `SmartLifecycle`/graceful drain (only a cleanup `@PreDestroy`), `SKIP LOCKED` (built in 3.17a). IT harness: `TestcontainersConfiguration` (Postgres 17.2, `@ServiceConnection`); `*IT`+`@Tag("integration")`.

## Scope Boundary — what 3.17b BUILDS vs REUSES vs DEFERS

| Concern | 3.17b | Note |
|---|---|---|
| `RunnerWorkerPool` (`application.runner.queue`): `ThreadPoolTaskExecutor`, size `deliveryline.runner.worker-pool-size` (default 2, min 1, max 32); per-worker `dequeue → dispatch → repeat`; idle backoff 1s→10s exponential | **BUILD** | epic AC3 |
| PostgreSQL `LISTEN runner_queue_updated` low-latency wakeup (p95 < 500ms idle); `NOTIFY` from enqueue afterCommit | **BUILD (net-new)** | epic AC4 |
| Queue-only path: relocate `RunnerBroker.dispatch` body → worker action; switch all 7 callers to `enqueue`; reconcile their return-value consumers | **BUILD (the big refactor)** | epic AC5 — R1, Trap T1 |
| `correlationId` restored into worker MDC for the dispatch duration | **BUILD** | epic AC8 (2nd half) |
| Worker-crash lease reclamation → `orphaned` (extend `scanForStaleExecutions` for leased rows past `dispatched_at + staleThresholdMultiplier×stage_timeout`) | **BUILD** | epic AC9 — D6 |
| Graceful shutdown: `SmartLifecycle` worker pool; SIGTERM → 60s drain; queued items remain `queued` | **BUILD** | epic AC10 |
| One shared pool (no per-stage pools) — realize the 3.17a ADR 0006 decision | **BUILD** | epic AC6 |
| ArchUnit caller-restrictions: only `WorkflowOrchestrationService`+`RecoveryService`+worker may `enqueue`; only worker may `dequeue`; nobody outside the worker loop may call the relocated dispatch / `adapter.dispatch` | **BUILD** | epic AC5/AC11 — D4 |
| Config: `worker-pool-size`, backoff (1s/10s), `worker-pool.enabled` (false in test yaml) | **BUILD** | epic AC3/AC10 |
| ITs: LISTEN/NOTIFY latency, worker-crash reclamation, graceful drain, no-direct-dispatch (ArchUnit) | **BUILD** | epic AC12 (subset) |
| `RunnerExecutionQueue.enqueue/dequeue`, `QUEUED`, `runner.queued`, `RUNNER_QUEUE_FULL`, V12, `queue-max-depth`, ADR 0006 | **REUSE (from 3.17a)** | — |
| `RunnerBroker.onResult` + `@Scheduled pollActiveExecutions` (DISPATCH→RESULT harvest) | **REUSE UNCHANGED** | R2, Trap T2 |
| Per-stage worker pools; Redis/RabbitMQ/Kafka; UI queue inspection / Grafana | **DEFER** | per-stage = never; inspection = story 3.19; batch = story 3.18 |

## Acceptance Criteria

> Derived from `epic-03-agent-execution.md` §"Story 3.17" (lines 344–357), scoped to the activation slice, with **binding clarifications** in **bold parentheticals**.

1. **Given** `RunnerWorkerPool` is a Spring-managed `ThreadPoolTaskExecutor` with size from `application.yml` `deliveryline.runner.worker-pool-size` (default 2, min 1, max 32) — each worker thread runs a loop: `dequeue → if work then dispatch via DockerRunnerAdapter → on completion mark runner_executions.status → repeat`; idle workers sleep on documented backoff (default 1s, exponential up to 10s). **(The "dispatch" the worker runs IS the relocated `RunnerBroker.dispatch` body (Fact 2) — bundle compose + `adapter.dispatch` + `runner.dispatched` event — NOT a re-implementation. The dequeued `queued` row (already flipped to `running` by 3.17a's `dequeue`) replaces the fresh `insertPending` — reconcile so exactly one row per execution exists. Gate the loop start on a NEW `deliveryline.runner.worker-pool.enabled` (default `true` main / **`false` in test yaml**) so unrelated `@SpringBootTest`s stay deterministic — mirror the `scheduling.enabled` gate, Trap T5/T8.)**

2. **Given** PostgreSQL LISTEN/NOTIFY for low-latency wake-up, **Then** `enqueue` issues `NOTIFY runner_queue_updated` after commit; idle workers `LISTEN` so newly-enqueued work is picked up within p95 < 500ms when workers are idle (measured by integration test). **(Net-new. The `NOTIFY` fires from a `TransactionSynchronizationManager.registerSynchronization` `afterCommit` hook added to `enqueue` (in-repo precedent `WorkflowTransitionService:159-184`) so the `queued` row is durable before the wake. Build a single dedicated listener connection (separate single-conn DataSource or `DriverManager`) whose background thread calls `PGConnection.getNotifications(timeout)` and signals idle workers. If the listener is unavailable, workers MUST still drain via the AC1 backoff poll — `LISTEN/NOTIFY` is a latency optimization on a correct poller, never the sole liveness path. See OQ-1.)**

3. **Given** **queue-only execution path**, **Then** there is **no direct-dispatch API** — `RunnerBroker.dispatch(...)` is refactored so the queue is the only entry; ArchUnit asserts no caller invokes `DockerRunnerAdapter.dispatch` directly outside the worker loop. **(R1 — the central refactor. Switch ALL 7 callers (Fact 1) from `runnerBroker.dispatch(...)` to `runnerExecutionQueue.enqueue(...)`. Relocate the heavy `RunnerBroker.dispatch` body (Fact 2) into the worker-loop action; restrict its callers to the worker via the ArchUnit `callMethod` rule (mirror `:390-405`). Trace + reconcile every consumer of the 6 orchestration methods' `RunnerDispatchResult` so "queued" reads correctly instead of "dispatched" — Trap T1.)**

3b. **Given** ArchUnit boundary, **Then** only `WorkflowOrchestrationService` and the worker pool (+ `RecoveryService`, caller #7) may invoke `RunnerExecutionQueue.enqueue`; only the worker pool may invoke `dequeue`. **(D4: add `callMethod` caller-restriction rules to `ArchitectureRuleCatalog` mirroring `ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE` (`:361-376`). ArchUnit runs in Failsafe — Trap T9.)**

4. **Given** correlation propagation per story 1.19, **Then** the worker carries the enqueued `correlationId` into MDC for the duration of the dispatch. **(Read the `correlation_id` persisted on the row by 3.17a; `MdcKeys.beginScope(CORRELATION_ID, …)`+`WORKFLOW_RUN_ID`+`RUNNER_EXECUTION_ID` at the start of the worker's dispatch action, `endScope` in finally — mirror `RunnerBroker.dispatch:394-396,661-662`.)**

5. **Given** worker crash mid-dispatch, **When** a worker thread dies, **Then** the in-flight `runner_executions` row's lease is reclaimed by story 3.2's stale-detection (extended to cover leased rows whose `dispatched_at + 2 × stage_timeout` has passed); reclaimed rows transition to `orphaned` for E4 recovery. **(D6: a `queued` row with no `worker_id` is NOT orphaned — it is correctly waiting and stays enqueueable. Reclamation targets LEASED-but-stalled rows: `worker_id IS NOT NULL`/`dispatched_at` past `staleThresholdMultiplier × stage_timeout`. Extend `scanForStaleExecutions` (`RunnerBroker:2174`) with this leased-row predicate, flipping via the existing `recordOrphaned` + `runner.orphaned` path — do NOT fork a second orphan path. A worker that crashed mid-`adapter.dispatch` leaves a `running` row with a stale `dispatched_at`; the existing `last_activity_at` orphan scan already covers `running`, so confirm the predicate covers leased-but-never-advanced rows too.)**

6. **Given** graceful shutdown, **Then** the worker pool participates in Spring Boot shutdown lifecycle: SIGTERM triggers a documented drain timeout (default 60s); queued items remain queued for next backend restart. **(Net-new. Implement `SmartLifecycle` on `RunnerWorkerPool` — `stop(Runnable)` signals workers to finish their CURRENT dispatch and exit, then `callback.run()`; bound by `spring.lifecycle.timeout-per-shutdown-phase: 60s` in `application.yml`. "queued items remain queued" is automatic — `queued` rows are untouched; in-flight `running` rows continue under the existing recover-on-startup path. Do NOT cancel running containers on shutdown — that is recovery's job, story 3.2/E4.)**

7. **Given** the test suite, **Then** integration tests cover: LISTEN/NOTIFY wake-up latency (p95 < 500ms idle), worker-crash orphan reclamation, graceful shutdown drains in-progress, no direct-dispatch path callable from outside the worker loop. **(Plus regression: an end-to-end enqueue→worker-dispatch→`onResult` harvest proving the relocated dispatch body still completes a run. Testcontainers Postgres; `*IT`, `@Tag("integration")` — Trap T7. The no-direct-dispatch test is the ArchUnit rule (AC3/AC3b). The substrate ITs — SKIP-LOCKED, backpressure, correlationId persist — are 3.17a's; 3.17b adds the runtime/lifecycle/NOTIFY ones.)**

**Logging instrumentation** (cross-cutting; see task below) — `INFO` on worker dispatch start/finish, pool start/drain-begin/drain-complete; `WARN` on LISTEN connection loss + fallback-to-poll, lease reclamation (orphan); `ERROR` only on unexpected worker-loop failure. Carry `correlationId`, `workflowRunId`, `runnerExecutionId`, `workerId`. **Never** log bundle bytes, secrets, or the NOTIFY payload beyond the rex id.

## Tasks / Subtasks

- [x] **Task 1 — `RunnerWorkerPool`: pool + loop + backoff + MDC** (AC: #1, #4)
  - [x] New `org.dradgo.application.runner.queue.RunnerWorkerPool` (manages its own `ThreadPoolTaskExecutor`, core=max=`worker-pool.size`). Each worker: `dequeue → executeQueuedDispatch (which restores correlationId/workflowRunId/runnerExecutionId MDC) → repeat`; worker thread carries `workerId` MDC; idle backoff via `RunnerQueueSignal.awaitSignal` 1s→10s exponential, reset on work. Loop start gated on `worker-pool.enabled` (Trap T8). **Deviation:** the pool is a `SmartLifecycle @Component` managing the executor internally (cleaner lifecycle) rather than a separate `@Bean` executor.
  - [x] Wired as `@Component` (auto-detected); `RunnerWorkerPoolProperties` registered via `@EnableConfigurationProperties` in `RunnerConfiguration`.

- [x] **Task 2 — Relocate `RunnerBroker.dispatch` body + switch 7 callers** (AC: #3)
  - [x] Added `RunnerBroker.executeQueuedDispatch(RunnerExecutionSnapshot leased)` — the relocated dispatch body run by the worker off the leased (already-`running`) row (no `insertPending`; the dequeued row IS the one row per execution). Reuses idempotency-reserve / bundle-compose / `adapter.dispatch` / event sequence. **Persistence writes wrapped in `dispatchTransactionTemplate`** since the worker has no ambient caller tx (the synchronous path ran inside the caller's tx). **`onResult` + poller UNCHANGED (R2, Trap T2).** Legacy `dispatch()` retained (tests only; no production caller).
  - [x] Switched all 7 callers to `enqueue`: `WorkflowOrchestrationService` (3 enqueue sites covering the 6 dispatch methods, via a new `enqueueDispatch` helper) + `RecoveryService.retry`. **T1 reconciliation:** added a `RunnerDispatchResult.Queued` variant (keeps the 6 method signatures + the 5 result-ignoring consumers stable); `logDispatchOutcome` now logs "queued"; `RecoveryService` reads the queued rex + uses the `runner.queued` event as the retry audit anchor. **Both callers dropped their `RunnerBroker` dep entirely** (net-zero ctor arity; also fully breaks the broker↔orchestration cycle). In-flight guard `ACTIVE_STATUSES` extended to include `QUEUED` so a queued row is seen as in-flight (no duplicate enqueue).
  - [x] `RUNNER_QUEUE_FULL` propagates from `enqueue` and rolls back the caller's submit/approve tx (run stays in prior state) / surfaces as retryable 503 — never a FAILED transition (Trap T6). Recovery's out-of-tx enqueue opens its own tx + afterCommit NOTIFY (OQ-4).
  - [x] No checkstyle suppression shift (the relocated method was ADDED, not moved over a suppressed line; `RunnerBroker`/`WorkflowOrchestrationService`/`RecoveryService` carry no line-anchored suppressions). Checkstyle 0 violations.

- [x] **Task 3 — LISTEN/NOTIFY** (AC: #2)
  - [x] `RunnerExecutionQueue.enqueue` fires `NOTIFY runner_queue_updated` from a `registerSynchronization` `afterCommit` hook (precedent `WorkflowTransitionService`) via a new application-owned `RunnerQueueNotificationPort` (adapter `RunnerQueueNotificationAdapter` issues the `NOTIFY`, keeping JDBC out of the application layer, Trap T11). `RunnerQueueListener` (adapter) holds a dedicated connection, blocks on `PGConnection.getNotifications`, and signals idle workers via `RunnerQueueSignal`; reconnects with backoff on drop and degrades to the AC1 backoff poll (D8/OQ-1). Gated on `worker-pool.enabled`. **The pgjdbc driver moved to compile scope** (the listener uses the `PGConnection` notification API at compile time).

- [x] **Task 4 — Worker-crash lease reclamation** (AC: #5)
  - [x] Added the `findLeasedStaleByStageAndDispatchedAtBefore` SPI finder (`worker_id IS NOT NULL` + `dispatched_at` AND `last_activity_at` past `staleThresholdMultiplier × stage_timeout` — dual predicate spares healthy long-running heartbeating jobs); `scanForStaleExecutions` Phase 3 routes them through the existing `processStaleOrphan` → `recordOrphaned` + `runner.orphaned` (D6, no forked orphan path). Worker-less `queued` rows are never returned (verified by IT).

- [x] **Task 5 — Graceful shutdown** (AC: #6)
  - [x] `RunnerWorkerPool implements SmartLifecycle` (high phase so it stops before the DataSource); `stop(Runnable)` clears running, wakes all workers, waits up to a 60s drain for in-flight dispatches (lock/condition, never `Thread.sleep`), then shuts the executor + callbacks. `spring.lifecycle.timeout-per-shutdown-phase: 60s` added to `application.yml`. Queued rows are untouched (verified); containers are NOT cancelled.

- [x] **Task 6 — Config: worker pool keys (main + test)** (AC: #1, #6)
  - [x] **Deviation:** added a dedicated `RunnerWorkerPoolProperties` (`deliveryline.runner.worker-pool.*`: `enabled`, `size` clamp [1,32] default 2, nested `Backoff` initial 1s/max 10s) rather than expanding the `RunnerProperties` record — avoids the ~10-site `new RunnerProperties(...)` fan-out ([[runnerproperties-record-component-fanout]]). Mirrored into `application.yml` (enabled true) AND `src/test/resources/application.yml` (`enabled: false`, Trap T5/T8).

- [x] **Task 7 — ArchUnit caller-restrictions** (AC: #3, #3b)
  - [x] Added 3 catalog rules: only `WorkflowOrchestrationService`+`RecoveryService`+`RunnerWorkerPool` may `enqueue`; only `RunnerWorkerPool` may `dequeue`; only `RunnerWorkerPool` may call `RunnerBroker.executeQueuedDispatch`. Registered in `ArchitectureBoundaryTest` (45→48 rules). Widened the existing 3.17a queue-placement rule to admit `org.springframework.{context,scheduling,transaction.support}` (SmartLifecycle/ThreadPoolTaskExecutor/TransactionSynchronization — framework primitives, not adapters; Trap T11 intact). Verified via Failsafe (Trap T9): 48/0.

- [x] **Task 8 — Tests** (AC: #7)
  - [x] ITs (`*IT`, `@Tag("integration")`, Testcontainers Postgres): `RunnerWorkerPoolActivationIT` (e2e enqueue→worker-dispatch→`adapter.dispatch`, MDC correlationId propagated); `RunnerLeasedReclamationIT` (worker-crash → orphan reclamation + `queued`-row-never-reclaimed D6 + finder predicate); `RunnerExecutionQueueIT` (3.17a, updated for carriage). ArchUnit no-direct-dispatch via `ArchitectureBoundaryTest`.
  - [x] Unit (mock adapter): `RunnerWorkerPoolTest` (gate, dequeue→dispatch loop, worker MDC restore, drain), `RunnerWorkerPoolPropertiesTest` (size clamp + backoff curve), `RunnerExecutionQueueTest` + `RunnerExecutionQueueLoggingContractTest` (carriage + NOTIFY + backpressure). **Partial:** a rigorous LISTEN/NOTIFY p95<500ms latency-measurement IT + an explicit graceful-drain-leaves-queued IT are NOT added (the activation IT proves the wakeup pipeline within a 15s budget; the drain is covered by the unit test). See Completion Notes.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J + MDC on `RunnerWorkerPool` loop/lifecycle (`workerId`), `executeQueuedDispatch` (correlationId/workflowRunId/runnerExecutionId), the LISTEN listener (NOTIFY-fallback WARN), and lease reclamation (existing `RUNNER_ORPHANED` path). New `MdcKeys.WORKER_ID`. Worker MDC restore pinned by `RunnerWorkerPoolTest`; enqueue/dequeue surfaces by `RunnerExecutionQueueLoggingContractTest`.

## Dev Notes

### THE references that matter most

| Concern | File to mirror | Why |
|---|---|---|
| **Dispatch body to relocate** | `RunnerBroker.dispatch` (`application/runner/RunnerBroker.java:385-664`); `requiredTemplate`/`requiresNewTemplate` (`:367-379`) | becomes the worker action (R1/R2); `onResult` (`:700`) stays. |
| **The 7 callers to switch** | `WorkflowOrchestrationService` (`:212/267/454/479/531/556`→`dispatchExecutionInternal:628`), `RecoveryService.retry` (`:418`) | AC3 — `dispatch`→`enqueue` + return reconciliation (Trap T1). |
| **Stale/orphan scan to extend** | `RunnerBroker.scanForStaleExecutions` (`:2174`), `scanForTimeouts` (`:2036`); `RunnerExecutionService.recordOrphaned` | AC5 leased-row reclamation (D6). |
| **Post-commit hook (NOTIFY)** | `WorkflowTransitionService:159-184` (`registerSynchronization`/`afterCommit`) | AC2. |
| **MDC** | `application/observability/MdcKeys.java` (`beginScope`/`endScope`/`sanitizeForLog`) | AC4 worker MDC restore. |
| **Adapter SPI** | `application/runner/spi/RunnerAdapter.java`; `RecoverableRunnerAdapter` (`emitsDispatchedAfterAck`); `DockerRunnerAdapter.dispatch:188` | worker calls `adapter.dispatch`. |
| **Config + scheduling gate** | `RunnerProperties.java:415-464` (clamp), `defaults()`; `RunnerConfiguration` (`@EnableScheduling`, `scheduling.enabled` gate); `application.yml:148-231` + `src/test/resources/application.yml:49-116` | AC1/AC6 (Trap T5/T8). |
| **ArchUnit caller-restriction** | `ArchitectureRuleCatalog.ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE` (`:361-376`), `ONLY_ORCHESTRATION_AUTO_ADVANCES…` (`:390-405`) | AC3/AC3b (D4). |
| **IT harness** | `src/test/java/org/dradgo/TestcontainersConfiguration.java` (Postgres 17.2); `*IT`+`@SpringBootTest`+`@Tag("integration")` | AC7 (Trap T7). |
| **Substrate (3.17a)** | `3-17a-runner-execution-queue-substrate-and-backpressure.md` | `enqueue`/`dequeue`/`QUEUED`/`RUNNER_QUEUE_FULL`/V12/ADR0006 prereqs. |
| **House discipline** | `3-16-…-linear-completion-sync.md` | out-of-slice + reconciliation-first. |

### Decisions (made by this story; rationale)

- **D1 — Retrofit the queue UNDER the 7 existing synchronous callers (R1).** This slice lands last; switch every caller to `enqueue` and reconcile each return-value consumer. No "land it and wait for callers" shortcut.
- **D2 — Bundle composed at DISPATCH (worker), not enqueue.** The relocated `dispatch()` body composes the (repo-cloning, stateful) bundle on the bounded pool; this is what makes "bounded resource use" real.
- **D4 — Caller-restrictions via ArchUnit `callMethod` rules** (`application.runner.queue` worker/orchestration/recovery only). Worker reaches the adapter through the `RunnerAdapter` SPI, never `adapters.runner.DockerRunnerAdapter`.
- **D6 — Lease reclamation EXTENDS story 3.2's scan, not a new orphan path.** Target `worker_id`/`dispatched_at`-aged rows; never orphan a worker-less `queued` row.
- **D8 — `LISTEN/NOTIFY` is a latency optimization atop a correct backoff poller.** Workers are always correct via the AC1 dequeue-backoff loop; NOTIFY only shortens idle latency to meet AC2's p95<500ms. Listener failure degrades to polling, never to stalling.
- **D9 — New `worker-pool.enabled` flag (default true main / false test), parallel to `scheduling.enabled`.** Keeps `@SpringBootTest` tiers deterministic.

### Open Questions (each carries a recommendation — proceed unless the architect objects)

- **OQ-1 — LISTEN/NOTIFY connection management.** A dedicated listener connection outside Hikari is non-trivial (reconnect on drop, thread lifecycle, test determinism). **Recommend** a single-connection listener with reconnect + a hard fallback to the AC1 backoff poll (D8); if flaky, ship pure short-interval polling and defer NOTIFY (AC2's p95<500ms would then need a sub-second idle poll). Confirm the latency budget is hard.
- **OQ-3 — Idempotency reservation point: enqueue (3.17a) vs worker-dispatch (here).** 3.17a's `enqueue` persists the key without reserving; the relocated dispatch body still reserves at worker time, preserving today's replay semantics. **Recommend** keeping reservation in the worker dispatch action unless a duplicate enqueue should replay to the existing `queued` rex (then move reservation into enqueue). Confirm against the `IdempotencyService` replay contract.
- **OQ-4 — `RecoveryService.retry` runs outside a transaction (Fact 1 #7).** enqueue opens its own tx + afterCommit NOTIFY. **Recommend** an IT for the recovery→enqueue path verifying enqueue works with no ambient tx.

### Traps (wiring hazards — each maps to a memory or a verified code fact)

- **T1 — Return-value reconciliation (the silent breaker).** The 6 orchestration dispatch methods return `RunnerDispatchResult`; their consumers (CLI submit output, approval flows) read it. After `enqueue` the outcome is "queued", not "dispatched" — trace EVERY consumer or the UX/CLI silently misreports. Highest-risk part of AC3.
- **T2 — Do NOT touch `onResult` or `pollActiveExecutions` (R2).** The queue owns ENQUEUE→DISPATCH only; result harvest is unchanged. Do not "move harvest into the worker too" — out of scope, breaks late-result/recovery paths.
- **T5 — Validated-config / test-yaml shadow** ([[validated-config-needs-test-yaml]]) — mirror every new `worker-pool*` key into `src/test/resources/application.yml` with `worker-pool.enabled: false`, or every `@SpringBootTest` reds at binding.
- **T6 — `RUNNER_QUEUE_FULL` must NOT drive a FAILED transition** — AC (3.17a) "run stays in prior state"; callers surface the transient error and leave the workflow where it was.
- **T7 — `*IT` naming + Testcontainers** ([[springboot-testcontainers-test-must-be-IT]]) — name Postgres tests `*IT` (Failsafe), `@Tag("integration")`; not `@Tag("docker-runner-it")` unless pulling runner images ([[docker-it-needs-exact-docker-runner-it-tag]]).
- **T8 — Worker pool OFF in tests by default (D9).** An always-on pool dequeues/dispatches during unrelated `@SpringBootTest`s → nondeterminism. Gate on `worker-pool.enabled` (false in test yaml); tests that exercise it set it true.
- **T9 — ArchUnit runs in Failsafe, not Surefire** ([[archunit-runs-in-failsafe-not-surefire]]) — verify caller-restriction rules via `failsafe:integration-test -Dit.test=**/architecture/**/*Test`.
- **T10 — Checkstyle line-anchored suppressions** ([[checkstyle-suppressions-line-anchored]]) — relocating the `RunnerBroker.dispatch` body shifts many lines; re-anchor any `lines="N"` forbidden-call suppression in `RunnerBroker`/`WorkflowOrchestrationService`.
- **T11 — `application/…` cannot import `adapters.…`** ([[application-cannot-import-adapters]]) — the worker reaches the runner via the `RunnerAdapter` SPI; never import `adapters.runner.DockerRunnerAdapter`.
- **T14 — Constructor fan-out** ([[docker-adapter-ctor-dep-fans-out]] / [[two-public-constructors-need-autowired]]) — injecting `RunnerExecutionQueue` into `WorkflowOrchestrationService`/`RecoveryService` changes ctors; keep exactly one `@Autowired` ctor and update every `new …(…)` test site. Watch the broker↔orchestration cycle ([[broker-orchestration-lazy-supplier]]) if the worker calls back into orchestration (the existing lazy-Supplier callback in `RunnerBroker` already exists — the relocated dispatch body keeps using it).
- **T2-onResult — The relocated dispatch body STILL feeds the existing auto-advance.** `RunnerBroker.onResult`'s INVESTIGATION/EXECUTION branches call `orchestration.onSpecStageSucceeded`/`onPlanStageSucceeded`/`onPrOutputStageSucceeded` via the lazy Supplier (`:1085-1122`). The worker model dispatches but the SAME poller→`onResult`→orchestration path harvests — confirm the auto-advance still fires unchanged after the dispatch relocation.
- **T15 — Run gates via PowerShell** ([[rtk-hook-only-matches-bash]]); reproduce Docker/foundation tiers in a clean env / WSL2 ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback. No `System.out`/`printStackTrace()`. ADR `0019-structured-logging`.
- **Surface:** `RunnerWorkerPool` loop + lifecycle, the relocated dispatch action, the LISTEN listener, the lease-reclamation scan. `INFO` lifecycle (dispatch start-finish, pool start-drain), `WARN` recoverable (LISTEN drop→poll fallback, orphan reclamation), `ERROR` only unexpected worker-loop failure.
- **Required keys:** `correlationId`, `workflowRunId`, `runnerExecutionId`, `workerId`. **Forbidden:** bundle bytes, secrets, NOTIFY payload beyond the rex id, host paths.
- **Test contract:** new surfaces pinned by a focused list-appender assertion.

### Project Structure Notes

- Backend `deliveryline-backend/`, base `org.dradgo`, Java 21, Spring Boot 4.0.6.
- New in `org.dradgo.application.runner.queue` — `RunnerWorkerPool` (+ the listener).
- Worker-pool config bean → `infrastructure/config/RunnerConfiguration` (or sibling).
- Dispatch body relocation → `org.dradgo.application.runner.RunnerBroker` (extract method; ArchUnit-restrict callers). **`onResult` + poller UNCHANGED.**
- Stale/orphan extension → `RunnerBroker.scanForStaleExecutions` + `RunnerExecutionRecordPort`/adapter.
- Config → `RunnerProperties` + `application.yml` (main + test) — `worker-pool-size`/backoff/`worker-pool.enabled` + `spring.lifecycle.timeout-per-shutdown-phase`.
- ArchUnit → `architecture/ArchitectureRuleCatalog.java`.
- **Callers to switch:** `application/workflow/WorkflowOrchestrationService.java` (6 methods) + `application/recovery/RecoveryService.java` (`retry`).
- **Reuse from 3.17a (do NOT rebuild):** `RunnerExecutionQueue`, `QueuedRunnerExecution`, `QUEUED`, `runner.queued`, `RUNNER_QUEUE_FULL`, V12, `queue-max-depth`, ADR 0006.

### Verification commands (PowerShell — [[rtk-hook-only-matches-bash]])

- Focused unit: `mvnw -pl deliveryline-backend test -Dtest=RunnerWorkerPoolTest,WorkflowOrchestrationServiceTest,RecoveryServiceTest,RunnerBroker*Test`.
- ArchUnit (Failsafe — Trap T9): `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=**/architecture/**/*Test`.
- Activation ITs (Testcontainers Postgres): `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=*WorkerPool*IT,*QueueActivation*IT`.
- Foundation gate (Docker up): `mvnw -pl deliveryline-backend -Pfoundation-gate verify -Dtest=ZzzNone -Dsurefire.failIfNoSpecifiedTests=false`.
- Static + fast tier: `mvnw -pl deliveryline-backend spotless:apply checkstyle:check` then `mvnw -pl deliveryline-backend test`.
- WSL2 Linux smoke of the foundation gate ([[wsl-linux-ci-reproduction]] / [[verify-ci-fixes-in-clean-env]]).

### References

- Epic: [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.17] — ACs 3, 4, 5, 8(2nd half), 9, 10, 11 (activation subset), lines 344–357 + the (inverted) execution-order note line 342. Companion slice: **3.17a** (substrate). Adjacent: Story 3.18 (batch submission, depends on this queue, lines 359–375), Story 3.19 (queue inspection/Grafana, lines 383–394), Story 3.22 (takeover cancels queued executions, line 455).
- Predecessor: [Source: _bmad-output/implementation-artifacts/3-17a-runner-execution-queue-substrate-and-backpressure.md] — the substrate this slice activates.
- Dispatch + stale + callers: `application/runner/RunnerBroker.java:367-664` (dispatch + tx templates), `:700,1085-1122` (onResult — unchanged), `:2174` (scanForStaleExecutions); `application/workflow/WorkflowOrchestrationService.java:212,267,454,479,531,556,628`; `application/recovery/RecoveryService.java:242,418`; `application/runner/RunnerExecutionService.java`.
- Runtime + tx + MDC: `application/runner/spi/RunnerAdapter.java`; `RecoverableRunnerAdapter.java`; `adapters/runner/DockerRunnerAdapter.java:188`; `application/workflow/WorkflowTransitionService.java:159-184`; `application/observability/MdcKeys.java`.
- Config + ArchUnit: `application/runner/RunnerProperties.java:415-464`; `infrastructure/config/RunnerConfiguration.java:25-78`; `application.yml:148-231` + `src/test/resources/application.yml:49-116`; `architecture/ArchitectureRuleCatalog.java:361-376,390-405`.
- IT harness: `src/test/java/org/dradgo/TestcontainersConfiguration.java` (Postgres 17.2).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context) — bmad-create-story 2026-06-11 (split of 3.17 into 3.17a/3.17b).

### Debug Log References

- `executeQueuedDispatch` initially threw `InvalidDataAccessApiUsageException: No active transaction` under the worker pool (the worker has no ambient caller tx, unlike the synchronous path). Fixed by wrapping every persistence write (`touchActivity`, `RUNNER_STARTED`/`RUNNER_DISPATCHED` event appends, `recordOrphaned`/`recordFailed`) in `dispatchTransactionTemplate`; disk/adapter I/O stays outside a tx.
- Activation IT teardown race (the worker keeps dispatching after the test asserts the lease; `@AfterEach` deleted the run mid-dispatch) — fixed by draining the pool (`workerPool.stop()`) before the deletes.

### Completion Notes List

**Architecture decisions confirmed with Alex (the two central forks the substrate under-specified):**
1. **Idempotency/actor carriage = V14 migration, reserve at the worker.** V12 persisted only `correlation_id`; the worker also needs the idempotency key + the originating actor (RecoveryService passes a non-system actor) to run the relocated dispatch body. Chose a `V14__add_queue_dispatch_carriage.sql` (`idempotency_key`/`actor_identity`/`actor_type`) threaded through `enqueue`→row→`dequeue`→worker; the worker reserves idempotency at dispatch time (preserves today's replay semantics).
2. **Build full LISTEN/NOTIFY now** (dedicated `RunnerQueueListener` connection + afterCommit `NOTIFY`), with the backoff poll as the always-correct liveness fallback (D8).
3. **(Surfaced, defaulted)** With the bundle now composed asynchronously on the worker (D2), a clone/compose failure can no longer unwind the submit/approve tx — it instead drives the run to `Failed` via the existing `driveWorkflowFailed` machinery (mirrors a runner failure). This is an inherent behavioral change of the queue model.

**Verification (all GREEN via PowerShell — [[rtk-hook-only-matches-bash]], [[maven-arglineation-goal-crash]]):** full fast Surefire **954/0/11skip** (no regressions; all `@SpringBootTest` contexts start with the new `@Component` beans — queue notification adapter, listener, worker pool); ArchUnit Failsafe **48/0** (3 new caller-restriction rules + widened placement rule); checkstyle **0 violations**, spotless applied; focused units — RunnerExecutionQueueTest 8/0, RunnerExecutionQueueLoggingContractTest 3/0, RunnerWorkerPoolTest 2/0, RunnerWorkerPoolPropertiesTest 7/0, RecoveryServiceUnitTest 32/0, RecoveryLoggingContractTest 8/0, WorkflowOrchestrationServiceTest 34/0, RunnerBrokerUnitTest 44/0, RunnerPropertiesTest 15/0; **Testcontainers Postgres ITs** — RunnerWorkerPoolActivationIT 1/0 (e2e enqueue→worker-dispatch, MDC correlationId propagated), RunnerLeasedReclamationIT 2/0 (AC5/D6), RunnerExecutionQueueIT 5/0 (carriage + SKIP-LOCKED). V14 applies cleanly (proven by every IT booting Flyway).

**Deviations from the story's literal task text (each a lower-risk realization):**
- Worker-pool config is a dedicated `RunnerWorkerPoolProperties` (`worker-pool.size`/`.enabled`/`.backoff`), NOT new `RunnerProperties` components — dodges the ~10-site ctor fan-out ([[runnerproperties-record-component-fanout]]). Config key is `worker-pool.size` (nested) rather than the epic's top-level `worker-pool-size`.
- T1 reconciliation via a new `RunnerDispatchResult.Queued` variant keeps the 6 orchestration method signatures + their result-ignoring callers stable; both callers DROPPED their `RunnerBroker` dep (net-zero ctor arity, fully breaks the broker↔orchestration cycle).
- Legacy `RunnerBroker.dispatch()` is retained (tests only, no production caller) rather than deleted; the ArchUnit no-direct-dispatch rule pins `executeQueuedDispatch` to the worker pool.

**Remaining (recommended before merge):** (a) a rigorous LISTEN/NOTIFY p95<500ms latency-measurement IT (the activation IT proves the wakeup pipeline within a generous 15s budget but does not measure p95); (b) an explicit graceful-shutdown "queued rows survive a restart" IT (the drain is unit-covered + the activation IT drains in teardown); (c) `-Pfoundation-gate verify` + a WSL2/Linux clean-env Docker confirm ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]); (d) code-review with a different LLM. No new DomainErrorCode / WorkflowEventType / registry enum values were added, so the foundation-gate contract symmetry surfaces are unaffected.

Memory refs: runnerproperties-record-component-fanout, validated-config-needs-test-yaml, two-public-constructors-need-autowired, broker-orchestration-lazy-supplier, application-cannot-import-adapters, archunit-runs-in-failsafe-not-surefire, springboot-testcontainers-test-must-be-IT, post-commit-hook-needs-requires-new, checkstyle-suppressions-line-anchored, rtk-hook-only-matches-bash, maven-arglineation-goal-crash, verify-ci-fixes-in-clean-env, wsl-linux-ci-reproduction.

### File List

**New (production):**
- `deliveryline-backend/src/main/resources/db/migration/V14__add_queue_dispatch_carriage.sql`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/queue/RunnerWorkerPool.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/queue/RunnerQueueSignal.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/queue/spi/RunnerQueueNotificationPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerWorkerPoolProperties.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/RunnerQueueNotificationAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/RunnerQueueListener.java`

**Modified (production):**
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/queue/RunnerExecutionQueue.java` (enqueue takes `ActorContext` + persists carriage + afterCommit NOTIFY)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` (`executeQueuedDispatch` + `composeQueuedBundle` + scan Phase 3)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerDispatchResult.java` (`Queued` variant + `isQueued()`)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionRecordPort.java` (+`insertQueued` carriage args, +`findLeasedStaleByStageAndDispatchedAtBefore`)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionSnapshot.java` (+3 carriage fields + shim)
- `deliveryline-backend/src/main/java/org/dradgo/application/observability/MdcKeys.java` (+`WORKER_ID`)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowOrchestrationService.java` (enqueue + `RunnerExecutionQueue` dep, drop `RunnerBroker`)
- `deliveryline-backend/src/main/java/org/dradgo/application/recovery/RecoveryService.java` (enqueue + `RunnerExecutionQueue` dep, drop `RunnerBroker`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/RunnerExecutionPersistenceAdapter.java` (insertQueued carriage + leased-stale finder)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/RunnerExecutionEntity.java` (+3 carriage columns)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/RunnerExecutionEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/RunnerExecutionRepository.java` (leased-stale query)
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RunnerConfiguration.java` (`@EnableConfigurationProperties` += `RunnerWorkerPoolProperties`)
- `deliveryline-backend/src/main/resources/application.yml` + `src/test/resources/application.yml` (worker-pool block + `spring.lifecycle`)
- `deliveryline-backend/pom.xml` (postgresql driver runtime→compile scope)

**New (test):**
- `.../application/runner/queue/RunnerWorkerPoolTest.java`, `.../application/runner/RunnerWorkerPoolPropertiesTest.java`
- `.../application/runner/queue/RunnerWorkerPoolActivationIT.java`, `.../application/runner/queue/RunnerLeasedReclamationIT.java`

**Modified (test):**
- `.../application/runner/queue/RunnerExecutionQueueTest.java`, `RunnerExecutionQueueLoggingContractTest.java`, `RunnerExecutionQueueIT.java`
- `.../application/workflow/WorkflowOrchestrationServiceTest.java`
- `.../application/recovery/RecoveryServiceUnitTest.java`, `RecoveryLoggingContractTest.java`
- `.../architecture/ArchitectureRuleCatalog.java`, `ArchitectureBoundaryTest.java`

### Change Log

- 2026-06-15 — bmad-dev-story: implemented story 3.17b (worker pool + queue activation). V14 dispatch-carriage migration; `RunnerWorkerPool` (SmartLifecycle, bounded ThreadPoolTaskExecutor, backoff, worker MDC); relocated dispatch body into `RunnerBroker.executeQueuedDispatch`; switched all 7 dispatch callers to `RunnerExecutionQueue.enqueue` with `RunnerDispatchResult.Queued` return reconciliation (T1); LISTEN/NOTIFY (`RunnerQueueListener` + afterCommit NOTIFY + `RunnerQueueSignal`); worker-crash lease reclamation (scan Phase 3 + leased-stale finder); graceful shutdown drain; 3 ArchUnit caller-restriction rules. `ready-for-dev → review`.

### Review Findings

> bmad-code-review 2026-06-15 — three adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). 4 decision-needed, 3 patch, 2 deferred, 7 dismissed as noise. Claims verified against live code before classification. **All 4 decisions + 3 patches resolved (option 1 each) and applied 2026-06-15.** Verification: compile ✓; focused unit 138/0; ArchUnit Failsafe 49/0 (the new D4 rule passing confirms zero production callers of legacy `dispatch()`); Testcontainers ITs 10/0 (incl. new `RunnerNotifyLatencyIT` + `RunnerGracefulShutdownIT`); spotless+checkstyle clean.

- [x] [Review][Decision] **Adapter-dispatch failure in the worker strands the run — poisoned idempotency key + stuck `running` row + no `Failed` transition.** `executeQueuedDispatch` commits `idempotencyService.complete(..., COMPLETED)` ([`RunnerBroker.java:838`]) in its own tx BEFORE `scratchStore.writeContextBundle` (`:843`) and `runnerAdapter.dispatch` (`:860`), with NO try/catch around the disk/adapter I/O. If `adapter.dispatch` (or the post-ack `RUNNER_DISPATCHED` write, or `writeContextBundle`) throws, the worker loop's catch (`RunnerWorkerPool.java:176`) only logs + backs off: the row stays `running`, the idempotency record is durably `COMPLETED`, and there is no `driveWorkflowFailed`. A later recovery re-enqueue with the same `…:runner` key then hits `REPLAY` → `retireDuplicateLeasedRow` → never re-dispatches (run can wedge until/through the stale scan). This is a real regression vs the synchronous path, which unwound the `COMPLETED` write inside the caller's tx on adapter failure (dev's own comment, `RunnerBroker.java:751`). The compose-failure branch (`:789-809`) already models the correct async handling (complete `FAILED` + `driveWorkflowFailed`); the adapter path does not. **Decision:** treat an adapter-dispatch failure as a run failure (mirror the compose-failure catch) vs move `complete(COMPLETED)` to after a successful ack vs leave to stale-scan recovery.
- [x] [Review][Decision] **LISTEN connection is borrowed from the shared Hikari pool and held indefinitely.** `RunnerQueueListener.listenLoop` calls `dataSource.getConnection()` ([`RunnerQueueListener.java:110`]) on the injected Spring (Hikari) `DataSource` and never returns it (blocks in `getNotifications` for the app's life). This permanently consumes one pool permit; the Javadoc's "reconnects when Hikari's `maxLifetime` closes it" (`:33`) does not hold for a checked-out connection (maxLifetime only retires returned/idle connections). The story (AC2) specified a **dedicated single-connection DataSource or `DriverManager`** for exactly this reason. **Decision:** dedicated `DriverManager` connection vs a separate `max=1` HikariDataSource for the listener vs accept the one-permit cost (and fix the Javadoc).
- [x] [Review][Decision] **AC2/AC10 measurement ITs not implemented (dev-acknowledged).** AC2 says the p95 < 500ms idle wake-up is "measured by integration test" — no latency-measurement IT exists; `RunnerWorkerPoolActivationIT` only proves wake-up within a 15s budget and cannot distinguish the NOTIFY path from the 1s backoff poll. AC10's "queued rows survive a restart" has no dedicated IT (drain is unit-covered + teardown-covered only). Both are flagged in Completion Notes "Remaining (a)/(b)". **Decision:** accept the gap and merge vs add the two ITs first.
- [x] [Review][Decision] **AC3 ("queue is the only entry") is weaker than stated — legacy `RunnerBroker.dispatch()` retained but NOT ArchUnit-restricted.** The 3 new caller-restriction rules pin `executeQueuedDispatch`, `enqueue`, and `dequeue`, but the legacy synchronous `dispatch()` (which still calls `adapter.dispatch` directly) is guarded only by "has no production caller." A future caller could invoke it and bypass the queue. **Decision:** add an ArchUnit rule / `@Deprecated` marker scoping `dispatch()` to tests only, vs delete it and migrate its tests onto `executeQueuedDispatch`, vs accept the documented deviation.
- [x] [Review][Patch] **NOTIFY wakes only one worker per notification batch.** `RunnerQueueListener` calls `signal.signal()` once per `getNotifications` array regardless of length [`RunnerQueueListener.java:121`]; a burst of N enqueues with N idle workers wakes only one immediately, the rest wait out their backoff. Liveness is safe (poll covers it) but AC2's p95 only holds for the single-item case. Fix: `signalAll()` on notify, or have a worker re-signal after it takes work.
- [x] [Review][Patch] **`RunnerQueueSignal.awaitSignal` can drop a permit on the timeout boundary.** After `await(...)` returns, `pending` is cleared unconditionally [`RunnerQueueSignal.java:71`]; a `signal()` that lands in the window between the timeout firing and the waiter re-acquiring the lock is overwritten, losing that wake-up (worst case one extra backoff window of idle latency; liveness safe via poll). Fix: only clear `pending` when consuming it / re-check `pending` after `await`.
- [x] [Review][Patch] **Shutdown drain bound is a hard-coded constant decoupled from config, and is sequential with executor termination.** `RunnerWorkerPool.DRAIN_TIMEOUT = Duration.ofSeconds(60)` [`RunnerWorkerPool.java:49`] is independent of `spring.lifecycle.timeout-per-shutdown-phase: 60s` (they agree today but can silently drift), and `stop()` waits `drainInFlight()` (≤60s) then `pool.shutdown()`/`awaitTermination` (≤60s) sequentially [`:126-133`] — worst case ~120s before Spring's phase timeout interrupts. Fix: bind the drain from the lifecycle property (or document the coupling) and/or budget the two waits against a single deadline.
**Fixes applied 2026-06-15 (option 1 for each):**

- **D1** — `RunnerBroker.executeQueuedDispatch`: the bundle-write + `adapter.dispatch` + post-ack event are now wrapped in a try/catch that mirrors the composition-failure branch (complete idempotency `FAILED` + `recordFailedBestEffort` + `driveWorkflowFailed`, `FailureCategory.RUNNER_CRASH`); `complete(COMPLETED)` moved to AFTER a successful dispatch so the reservation reflects true success. No more stranded `running` row behind a poisoned `COMPLETED` key.
- **D2** — `RunnerQueueListener` now opens a dedicated PHYSICAL connection via `DriverManager` (JDBC coordinates derived from the Hikari pool, so prod + Testcontainers both work) instead of borrowing a pooled permit; Javadoc corrected.
- **D3** — added `RunnerNotifyLatencyIT` (asserts AC2 idle wake-up p95 < 500ms with the poll pinned to 5s so a sub-500ms wake can only be NOTIFY-driven) and `RunnerGracefulShutdownIT` (queued rows survive `stop()` untouched and are dispatched after a `start()` restart).
- **D4** — legacy `RunnerBroker.dispatch(...)` marked `@Deprecated` + new ArchUnit rule `NO_PRODUCTION_CALLER_MAY_INVOKE_LEGACY_SYNCHRONOUS_DISPATCH` (48→49 rules) pins it to zero production callers.
- **P1** — listener wakes idle workers via `signal.signalAll()` (not `signal()`) so a NOTIFY batch drains concurrently.
- **P2** — `RunnerQueueSignal.awaitSignal` re-checks `pending` after `await` and only clears it when consuming, closing the timeout-boundary lost-wakeup.
- **P3** — `RunnerWorkerPool` drain timeout bound from `spring.lifecycle.timeout-per-shutdown-phase` (default 60s) via `@Value`; the in-flight drain and executor termination now share ONE deadline (no longer additive).

- [x] [Review][Defer] **`contextBundleVersion` is minted at enqueue, consumed at worker-dispatch — narrow idempotency-fingerprint drift.** [`RunnerExecutionQueue.java` enqueue → `RunnerBroker.java:767` `dispatchFingerprint`] — deferred, narrow (requires interleaved same-run dispatches); the dev deliberately chose reserve-at-worker to preserve replay semantics.
- [x] [Review][Defer] **`enqueue` now rejects blank `idempotencyKey` with an unchecked `IllegalArgumentException` (→ 500, not a typed retryable error).** [`RunnerExecutionQueue.java:114`] — deferred, theoretical; all 7 production callers pass a non-blank key, 3.17a was dormant so no legacy null-key rows exist.
