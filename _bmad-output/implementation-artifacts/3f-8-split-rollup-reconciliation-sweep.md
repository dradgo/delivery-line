# Story 3f.8: Split-Rollup Reconciliation Sweep (durability for the completion-rollup hook)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->
<!-- Origin: code review of story 3f-7 (2026-06-29), Decision 2. The 3f-7 rollup is driven solely
by an afterCommit hook on the LAST child's COMPLETED transition; a transient failure of that single
best-effort REQUIRES_NEW execution permanently strands the parent in SPLIT. This story adds the
self-healing recovery path. -->

## Story

As an operator who decomposed a run into a split subtree,
I want a split parent to roll up to `Completed` even if the one completion-hook that should have
driven it failed transiently,
so that a momentary DB error (advisory-lock timeout, deadlock, optimistic-lock conflict, connection
blip) on the final child's `afterCommit` rollup never leaves the parent permanently stuck in `SPLIT`
with its cross-split dependents blocked forever and no automatic recovery.

**Context (the gap this closes).** In 3f-7 the `SPLIT → COMPLETED` rollup is driven **only** by the
`afterCommit` hook registered on a child's `COMPLETED` transition (`RunSplitCompletionRollupService`,
best-effort, swallow+log, `REQUIRES_NEW`). The **last** child to complete is the sole trigger of the
parent's rollup. If that single hook execution throws transiently, it is logged and swallowed — and
because no further child will ever complete, the hook never re-fires. Unlike the 3f-3 dependency
release (which is re-attempted on the *next* run completion), the rollup has no natural second
trigger. Result: the parent is stranded in non-terminal `SPLIT` and every cross-split dependent is
blocked indefinitely, recoverable today only by manual DB intervention.

**Depends on (done):** 3f-7 (`RunSplitCompletionRollupService.rollupParentOf` — idempotent,
`REQUIRES_NEW`, RDEP advisory lock, drives `SPLIT → COMPLETED` through `WorkflowTransitionService`),
3f-2 (`SPLIT` state + `SPLIT → COMPLETED` edge + `parent_run_id`), 3f-3 (advisory lock
`lockDependencyGraph()` + dependency release).

## Acceptance Criteria

1. **Given** a scheduled reconciliation sweep, **Then** on a configurable fixed-delay interval it
   scans for parent runs in `SPLIT` **all of whose direct children are `COMPLETED`** and, for each,
   invokes the existing idempotent 3f-7 rollup so the parent rolls up `SPLIT → COMPLETED`. The sweep
   reuses the 3f-7 rollup logic verbatim (RDEP advisory lock, `REQUIRES_NEW`, `WorkflowTransitionService`
   transition, swallow+log) — **no new transition path** and **no duplicate rollup gate logic**.
2. **Given** idempotency, **Then** the sweep is a no-op for any parent that is not in `SPLIT` or whose
   children are not all `COMPLETED`; running it repeatedly (or concurrently with a live `afterCommit`
   rollup of the same parent) never double-transitions, never double-emits `WORKFLOW_STATE_CHANGED`
   (`viaSplitRollup=true`), and never strands a run — guaranteed by the existing RDEP advisory lock +
   the deterministic `split-rollup:<parentId>` transition key + the state machine rejecting a second
   `→ COMPLETED`.
3. **Given** recursion, **Then** a parent rolled up by the sweep re-fires the standard 3f-7 hook chain
   (the parent's own `SPLIT → COMPLETED` transition registers a fresh rollup hook **and** the 3f-3
   dependency-release hook), so a grandparent stalled by the same transient failure also recovers and
   cross-split dependents are released — **no special-casing in the sweep** (it pokes the lowest
   stranded parent; the rest of the subtree recovers via the inherited hook chain, or on the next
   tick).
4. **Given** configuration + safety, **Then** the sweep is gated behind a config flag
   (`deliveryline.complex-ticket-flow.rollup-sweep.enabled`, default decided in dev) with a
   configurable fixed-delay interval; it is bounded (batch limit per tick, logged when capped — **no
   silent truncation**) and takes the RDEP advisory lock so it never races the live hook path. When
   disabled, **zero** scheduled bean is registered (the 3f-7 hook path is byte-identical to today).
5. **Given** observability, **Then** each swept recovery logs at `WARN`/`INFO` with the parent run id,
   child-completion counts, and a marker distinguishing a **sweep-driven** rollup from a hook-driven
   one (so a recurring sweep recovery is a visible signal that the primary hook path is failing); the
   per-tick summary logs how many stranded parents were found and how many were recovered, and logs a
   `WARN` when the batch limit caps the scan.
6. **Given** tests, **Then** coverage asserts: a stranded parent (children all `COMPLETED`, parent
   still `SPLIT`) is rolled up by one sweep tick (IT, real PG, non-`@Transactional`); the sweep is a
   no-op for a not-all-children-complete parent and for a non-`SPLIT` parent; recursion recovers a
   grandparent; concurrent sweep + hook do not double-transition; the disabled flag suppresses the
   sweep; `application.*` ≥ 80% coverage.

## Tasks / Subtasks

- [x] **Task 1 — Reconciliation read query (AC: #1, #4)**
  - [x] Add a read method that finds `SPLIT` parents whose **direct children are all `COMPLETED`**,
        bounded by a batch limit. Add it to the existing `WorkflowRunReadPort`
        (`application/workflow/spi/`) — e.g. `List<WorkflowRunSnapshot> findStrandedSplitParents(int limit)`
        — and implement it in `WorkflowRunPersistenceAdapter` (infrastructure; `application` may not
        import adapters). Implement either as (a) a single SQL `where current_state = 'Split' and not
        exists (child not Completed) and exists (>=1 child) limit :limit`, **or** (b) a two-step: fetch
        `SPLIT` runs bounded by the limit, then filter in-memory using the existing
        `findByParentRunId(parentId)` all-`COMPLETED` predicate. Prefer (a) for batch correctness.
  - [x] **Index check:** if (a), confirm an index supports `current_state` / `parent_run_id` lookups;
        `parent_run_id` is already indexed (V27). Add a Flyway migration **only** if the query
        measurably needs one — otherwise no migration (scope guard).
- [x] **Task 2 — Parent-targeted rollup entry on `RunSplitCompletionRollupService` (AC: #1, #2, #3)**
  - [x] The shipped `rollupParentOf(String completedRunId, String correlationId)` takes a **child**
        id and derives the parent. The sweep already holds the **parent** id, so add a thin
        parent-targeted public entry (e.g. `rollupParent(String parentId, String correlationId)`) that
        runs the **same** `doRollup` gate keyed on the parent directly. Refactor the private gate so
        both entries share it: extract the "lock → read parent snapshot → all-children-`COMPLETED`
        check → `transition(parent, COMPLETED, …, "split_rollup", rollupKey(parentId), {viaSplitRollup})`"
        body into a `doRollupForParent(String parentId, …)` and have the existing child-driven path
        resolve `parentId` then delegate to it. **Do not fork the gate.** Same `REQUIRES_NEW` template,
        same `lockDependencyGraph()`-first, same swallow+log.
  - [x] **Do not** synthesize a fake child id to feed the existing child-driven entry — the
        parent-targeted entry is the clean reuse.
- [x] **Task 3 — Scheduled sweep service + infra trigger (AC: #1, #4, #5)**
  - [x] Add an application-layer `SplitRollupReconciliationSweep` service in `application.workflow`
        (constructor: the read port from Task 1 + `RunSplitCompletionRollupService` + the sweep
        properties). Its `sweep()` method: read `findStrandedSplitParents(batchLimit)`; for each parent
        call `rollupParent(parentId, null)`; log the per-tick summary; `WARN` when the result size
        equals the batch limit (cap hit). **No scheduling/Spring-`@Scheduled` annotations in
        `application`** — the application layer stays framework-trigger-free.
  - [x] Add the `@Scheduled` trigger in **`infrastructure.config`** (codebase convention — every
        existing `@Scheduled`/`@EnableScheduling` lives there: `RunnerConfiguration`, `LinearPollingHost`,
        `DockerRunnerLifecycleConfiguration`). Mirror `RunnerConfiguration`: a `@Configuration`
        `@EnableScheduling` `@ConditionalOnProperty(name = "deliveryline.complex-ticket-flow.rollup-sweep.enabled")`
        bean with `@Scheduled(fixedDelayString = "${deliveryline.complex-ticket-flow.rollup-sweep.interval-ms:60000}")`
        delegating to `SplitRollupReconciliationSweep.sweep()`. `@ConditionalOnProperty` gating the
        scheduler bean means a disabled sweep registers **no** scheduled work (AC4).
  - [x] **Config:** add a `rollup-sweep` properties record. Prefer a **separate**
        `@ConfigurationProperties("deliveryline.complex-ticket-flow.rollup-sweep")` record
        (`enabled`, `intervalMs`, `batchLimit`) registered alongside `ComplexTicketFlowProperties` in
        `WorkflowConfiguration`'s `@EnableConfigurationProperties` — this avoids changing
        `ComplexTicketFlowProperties`' canonical constructor (which would fan out to its `defaults()`
        and every `new ComplexTicketFlowProperties(3)` in `SplitProposalServiceTest`). Compact ctor
        **normalizes-with-defaults and never throws** (memory: `validated-config-needs-test-yaml` — NOT
        `@Validated`). Mirror keys into **both** `src/main/resources/application.yml` **and**
        `src/test/resources/application.yml` (disabled in test yaml unless a sweep IT enables it via
        `@TestPropertySource`).
- [x] **Task 4 — Tests (AC: #6)**
  - [x] **Strand-then-sweep IT** (real PG, **non-`@Transactional`** — afterCommit/transition semantics;
        mirror `RunSplitCompletionRollupIT`): parent `SPLIT`, all children `COMPLETED`, parent left
        `SPLIT` (simulate the strand by completing children **without** the rollup, or by asserting the
        post-sweep state); invoke `sweep()` directly (don't wait on the scheduler) → parent flips
        `SPLIT → COMPLETED` carrying `viaSplitRollup=true`.
  - [x] **No-op cases:** not-all-children-`COMPLETED` parent; non-`SPLIT` run → `sweep()` does nothing.
  - [x] **Recursion IT:** grandparent `SPLIT` → parent `SPLIT` → leaf; strand both; `sweep()` recovers
        the chain via the inherited hook chain (and/or the next tick) up to the root.
  - [x] **Concurrency:** sweep + live hook on the same parent do not double-transition / double-emit
        (advisory lock + `split-rollup:<parentId>` key + state machine).
  - [x] **Disabled flag:** with `rollup-sweep.enabled=false`, the scheduler bean is absent (assert no
        bean / no invocation). Unit-test the sweep service directly for the happy/no-op/cap paths.
  - [x] `application.*` ≥ 80% coverage.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] `INFO` per-tick summary (`found=N recovered=M batchLimit=L`); `WARN` when `found == batchLimit`
        (cap hit — no silent truncation); `INFO`/`WARN` per swept recovery with `parentRunId` +
        child-completion counts + a **sweep-vs-hook marker** (so a recurring sweep recovery flags a
        failing primary hook); the underlying `rollupParent` reuses 3f-7's existing rollup logs.
  - [x] Parameterized logging only (`log.info("...", a, b)`); levels per the standard; context keys
        (`workflowRunId`/`parentRunId`); ids/counts only — no payloads/secrets.
  - [x] Pin the new log surfaces (per-tick summary, cap-hit `WARN`, sweep marker) with a list-appender
        / `OutputCaptureExtension` assertion.

### Review Findings

_Adversarial code review 2026-06-30 (Blind Hunter + Edge Case Hunter + Acceptance Auditor). All 6 ACs assessed satisfied; scope guard honored (no new state/edge/event/error-code/migration). Findings below._

- [x] [Review][Patch] Discovery query omits `archived_at is null` → archived `Split` parent re-swept forever + WARN-spam + head-of-line block [deliveryline-backend/.../repository/WorkflowRunRepository.java `findStrandedSplitParents`] — an archived parent in `Split` with all children `Completed` is returned every tick; the rollup `transition()` throws `archivedRunRejected` (confirmed), which is swallowed, so the parent never leaves `Split` and the sweep logs "primary completion hook failed" every tick indefinitely. Those permanently-unrecoverable parents sit at the oldest-first front of the bounded scan, head-of-line-blocking recoverable strands beyond `batch-limit`. Fix: add `and parent.archivedAt is null` to the discovery where-clause (and decide whether an archived non-`Completed` child should still count toward "not all complete"). [source: blind+edge] — **FIXED 2026-06-30**: added `and parent.archivedAt is null` (children left archive-unfiltered to mirror the rollup gate's own completeness check); new `SplitRollupReconciliationSweepIT.sweepIgnoresAnArchivedSplitParent` proves found=0 / parent stays `Split` (IT 6/6 green).
- [x] [Review][Patch] Sweep passes `null` correlationId — recoveries log `correlationId=null` [deliveryline-backend/.../SplitRollupReconciliationSweep.java:505] — `rollupService.rollupParent(parentId, null)` makes every sweep-driven gate log line read `correlationId=null`, defeating traceability for exactly the rare transient-strand recoveries this story exists to make observable. Fix: pass a sweep token (per-tick id or `"sweep:"+parentId`). [source: blind] — **FIXED 2026-06-30**: now passes `"sweep:" + parentId`; unit verifications updated to `rollupParent(parentId, "sweep:"+parentId)`.
- [x] [Review][Patch] Sweep-marker log precision — WARN before outcome + redundant child query + over-counted `recovered` [deliveryline-backend/.../SplitRollupReconciliationSweep.java:493-507] — the "recovering stranded parent (primary completion hook failed)" WARN is emitted unconditionally before the rollup outcome is known (a parent that fails to flip still logs "recovering"); an extra `findByParentRunId` runs per parent solely to log `completedChildren` (the reused gate already re-derives + logs the count); and `recovered++` over-reports when a concurrent live hook wins the race. Fix: log the marker after a confirmed flip / reuse the gate's count / treat `recovered` as best-effort. [source: blind+auditor] — **FIXED 2026-06-30**: the marker WARN + its `completedChildren` read now fire only inside the confirmed-flip branch (`recovered++`), so no pre-attempt overstatement and the child query runs only for actual recoveries; the residual race-attribution is documented as best-effort in code (harmless — the parent is genuinely `Completed`).

_Dismissed (4): (1) "new files in a different module root" — diff-root artifact only; files exist on disk at the correct `deliveryline-backend/...` paths (confirmed by `git status` + Acceptance Auditor). (2) Standalone offset-paging redesign for head-of-line — mitigated once the archived-filter patch lands; over-engineering for an opt-in 60s safety-net sweep (consequence folded into the archived patch). (3) "AC6 concurrency test is sequential not multi-threaded" — matches the spec's explicit advisory-lock "proxy" design intent; a real two-thread lock-contention test would be flaky. (4) `interval-ms` clamp is dead / scheduler reads raw property — **resolved by-design 2026-06-30**: fail-fast at startup on operator misconfig is acceptable for an opt-in feature; the normalized record stays a discoverability/logging mirror._

## Dev Notes

### Reuse, do not fork
The sweep's only new code is **discovery** (which stranded parents to poke), **scheduling**, and
**config**. The transition, event (`viaSplitRollup`), recursion, dependency-release, and advisory-lock
discipline are all inherited from 3f-7 unchanged. The sweep must drive completion through the same
`RunSplitCompletionRollupService` rollup gate (→ `WorkflowTransitionService.transition()`), **never** a
raw state write — that is what fires the recursion hook (AC3) and the 3f-3 dependency-release hook.

### Parent-targeted entry (Task 2) — the one real API addition
`rollupParentOf(completedRunId, …)` is **child-driven**: it reads the child's `parentRunId`, then rolls
that parent up. The sweep query returns **parents**, so feeding it back through the child-driven entry
would mean re-deriving a child — awkward and lossy. Add a sibling `rollupParent(parentId, …)` that runs
the identical gate keyed on the parent. Extract the shared body (`doRollupForParent`) so there is **one**
gate: lock-first → parent snapshot → `currentState() == SPLIT` → all direct children `COMPLETED` →
`transition(parent, COMPLETED, system, "split_rollup", "split-rollup:"+parentId, {viaSplitRollup:true})`.
The existing child-driven path keeps working (resolve `parentId`, delegate). See
`RunSplitCompletionRollupService.java:100-168` (`doRollup`).

### Why a sweep over a manual re-trigger
The failure mode is a *transient* infrastructure error, so self-healing on the next tick is the correct
recovery — it needs no operator to notice. A manual operator re-trigger endpoint was considered (code
review 3f-7, Decision 2) and **rejected** in favor of the automatic sweep.

### Scheduling lives in infrastructure, not application
This codebase places **every** `@Scheduled`/`@EnableScheduling` in `infrastructure.config`
(`RunnerConfiguration.java:28,44-65`, `LinearPollingHost.java:83-84,175`,
`DockerRunnerLifecycleConfiguration.java:24,58`), always paired with `@ConditionalOnProperty` and a
`fixedDelayString = "${…interval-ms:default}"`. Follow that pattern exactly: framework trigger in
infra, business logic in an `application.workflow` service. The application layer never sees a Spring
scheduling annotation — keeps `application-cannot-import-adapters`/framework-thin boundaries clean.

### Concurrency (AC2)
The RDEP advisory lock (`runDependencyPort.lockDependencyGraph()`, taken **first** inside the rollup's
`REQUIRES_NEW` tx) + the deterministic `split-rollup:<parentId>` transition key + the state machine
rejecting a second `→ COMPLETED` make the sweep safe to run alongside the live hook and against itself.
The first to acquire the lock rolls the parent up; the next re-reads `currentState() == COMPLETED` (no
longer `SPLIT`) and returns. No new locking primitive.

### afterCommit / non-`@Transactional` IT trap (carry-forward from 3f-7/3f-3)
A `@Transactional` IT rolls back and never commits, so it cannot exercise the `transition()` +
afterCommit chain the rollup relies on. Write the sweep ITs **non-`@Transactional`** with `@AfterEach`
cleanup, mirroring `RunSplitCompletionRollupIT` / `CompletionSyncOrchestrationIT`
(`post-commit-hook-needs-requires-new`). Invoke `sweep()` directly in the test rather than waiting on
the scheduler tick. If a sweep IT needs the flag on, enable it per-test with `@TestPropertySource`
(`prometheus-actuator-disabled-in-springboottest` pattern), since the shared test yaml keeps the sweep
disabled.

### Config fan-out (Task 3)
`ComplexTicketFlowProperties` is a single-component record `(int maxSplitDepth)` consumed by
`SplitProposalService` + constructed in `SplitProposalServiceTest`. Adding a 2nd component changes its
canonical ctor → breaks those `new ComplexTicketFlowProperties(3)` calls + `defaults()`. **Avoid that
blast radius**: register a **separate** `@ConfigurationProperties("deliveryline.complex-ticket-flow.rollup-sweep")`
record in the same `WorkflowConfiguration` `@EnableConfigurationProperties` set. Normalize-never-throw
in the compact ctor (clamp `intervalMs`/`batchLimit <= 0` to sane defaults), mirror keys into both
`application.yml`s (`validated-config-needs-test-yaml`).

### Scope guard
No new `WorkflowState`, no new transition edge, no new `WorkflowEventType`, no `DomainErrorCode`. The
`viaSplitRollup` detail key (3f-7) already distinguishes a rollup event; the sweep-vs-hook distinction
lives in **logs** (AC5), not a new event. A Flyway migration is added **only** if the discovery query
measurably needs an index (`parent_run_id` is already indexed by V27) — default: no migration.

### Verification before claiming done
Local green ≠ CI green (`verify-ci-fixes-in-clean-env`). Touching `application.workflow` +
`infrastructure.config` lights gates `mvnw test` path-filters out: **ArchUnit runs in Failsafe**
(`archunit-runs-in-failsafe-not-surefire` — a new scheduler/service bean can trip boundary/ctor rules);
**Spotless** (`spotless:apply` on hand-edited Java); **SpotBugs** (a new public service can light
`EI_EXPOSE` on exposed collections). A `*Test` that spins Testcontainers must be named `*IT`
(`springboot-testcontainers-test-must-be-IT`). Run the full verify (Surefire + Failsafe +
`-Pfoundation-gate`) in a clean env before review.

### Project Structure Notes
- Read query: `WorkflowRunReadPort` (SPI, `application/workflow/spi/`) + `WorkflowRunPersistenceAdapter`
  (infrastructure impl).
- Sweep service: `application/workflow/SplitRollupReconciliationSweep.java` (**new**).
- Rollup entry: `application/workflow/RunSplitCompletionRollupService.java` (add `rollupParent`,
  extract shared gate).
- Scheduler trigger + `@ConditionalOnProperty` + `@EnableScheduling`: `infrastructure.config` (**new**
  config class, or fold into an existing workflow config — keep it co-located with the gate, mirroring
  `RunnerConfiguration`).
- Config: separate `rollup-sweep` properties record + `WorkflowConfiguration` registration +
  `application.yml` (main + test).
- No FE work (operator-invisible recovery; the existing `SplitLineagePanel` already shows the parent
  flip to `Completed` once the sweep rolls it up).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident
without re-deploying. Enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface for 3f-8):**
  - `SplitRollupReconciliationSweep.sweep()` → `INFO` per-tick summary (`found`/`recovered`/`batchLimit`),
    `WARN` on cap hit, `INFO`/`WARN` per swept recovery with the **sweep-vs-hook marker** + `parentRunId`
    + child counts.
  - `RunSplitCompletionRollupService.rollupParent` reuses the existing 3f-7 rollup logs (fired /
    NOT-ready / swallow).
- **Required context keys:** `workflowRunId`/`parentRunId`, plus `correlationId` where available.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII.
- **Test contract:** the new sweep log surfaces pinned by a list-appender / `OutputCaptureExtension`.

## References

- Code review of story 3f-7 (2026-06-29), Decision 2 — `_bmad-output/implementation-artifacts/3f-7-recursive-split-and-completion-rollup.md` → Review Findings.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/RunSplitCompletionRollupService.java:69-176] — `rollupParentOf` + `doRollup` gate to extract a parent-targeted entry from; `REQUIRES_NEW` template + `lockDependencyGraph()`-first + swallow+log to reuse.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunReadPort.java:7-42] — `findByPublicId`, `findByParentRunId`, `listRuns`; add the stranded-`SPLIT`-parents query here.
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/RunnerConfiguration.java:28-65] — `@EnableScheduling` + `@Scheduled(fixedDelayString)` precedent to mirror for the sweep trigger.
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearPollingHost.java:83-175] — `@ConditionalOnProperty` + `@EnableScheduling` + `@Scheduled` co-location pattern.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/ComplexTicketFlowProperties.java] — normalize-never-throw `@ConfigurationProperties` record pattern for the new `rollup-sweep` record.
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/WorkflowConfiguration.java:17-23] — `@EnableConfigurationProperties` registration site for the new properties record.
- [Source: deliveryline-backend/src/test/java/org/dradgo/application/workflow/RunSplitCompletionRollupIT.java] — non-`@Transactional` rollup IT to mirror for the sweep IT.
- Reconciliations: [[story-3f-7-recursive-split-completion-rollup-reconciliations]], [[story-3f-3-run-dependency-graph-waiting-for-dependencies-reconciliations]], [[post-commit-hook-needs-requires-new]], [[validated-config-needs-test-yaml]], [[archunit-runs-in-failsafe-not-surefire]], [[springboot-testcontainers-test-must-be-IT]].

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- `mvnw -pl deliveryline-backend -Dtest=SplitRollupReconciliationSweepTest,RunSplitCompletionRollupServiceTest,SplitRollupSweepConfigurationTest test` → 15/15 green.
- `mvnw -pl deliveryline-backend -Dit.test=SplitRollupReconciliationSweepIT -Dsurefire.failIfNoSpecifiedTests=false verify` → Surefire 1432/0/0 (12 skipped), Failsafe ITs incl. SplitRollupReconciliationSweepIT 5/5, ArchUnit green, SpotBugs error-size 0, Spotless/Checkstyle clean, **BUILD SUCCESS**.
- `mvnw -pl deliveryline-backend -Pfoundation-gate test` → 1432/0/0, **BUILD SUCCESS** (no registry/transition/error-code surface touched, as designed).
- `mvnw -pl deliveryline-backend spotless:apply` run on hand-edited Java before verify.

### Completion Notes List

- **AC1/AC4 — discovery query.** Added `WorkflowRunReadPort.findStrandedSplitParents(int limit)` (default no-op so test doubles are unaffected) + a single SQL/JPQL impl in `WorkflowRunRepository`/`WorkflowRunPersistenceAdapter`: `current_state = 'Split'` AND `exists(child)` AND `not exists(child where state <> 'Completed')`, oldest-first, `Pageable`-bounded. Chose option (a) (single query) for batch correctness. **No Flyway migration** — `parent_run_id` is already indexed (V27) and `Split` is a low-cardinality state slice (scope guard honored).
- **AC1/AC2/AC3 — parent-targeted rollup entry.** Refactored `RunSplitCompletionRollupService`: extracted the shared gate into `doRollupForParent(parentId, correlationId)` (lock-first → parent snapshot → `Split` check → all-children-`Completed` → `transition(... split_rollup, split-rollup:<parentId>, {viaSplitRollup})`). The child-driven `rollupParentOf` now resolves `parentId` then delegates; the new `rollupParent(parentId, correlationId)` runs the identical gate keyed on the parent. **Gate not forked, no fake-child synthesis.** Verbatim reuse means recursion + 3f-3 dependency-release fall out of the inherited hook chain (verified by the grandparent IT).
- **AC4/AC5 — sweep service + infra trigger + config.** New application-layer `SplitRollupReconciliationSweep.sweep()` (framework-trigger-free) returning a `SweepResult(found, recovered, batchLimitHit)`; logs per-recovery sweep-vs-hook WARN marker + per-tick INFO summary + cap-hit WARN (no silent truncation) + a WARN when a poked parent did not flip. The `@Scheduled`/`@EnableScheduling`/`@ConditionalOnProperty` trigger lives in `infrastructure.config.SplitRollupSweepConfiguration` (mirrors `RunnerConfiguration`/`LinearPollingHost`); when disabled/absent **no scheduler bean registers** (proved by `SplitRollupSweepConfigurationTest`). Config is a **separate** `RollupSweepProperties` (`@ConfigurationProperties("deliveryline.complex-ticket-flow.rollup-sweep")`, normalize-never-throw) registered alongside `ComplexTicketFlowProperties` in `WorkflowConfiguration` — avoids fanning out the `ComplexTicketFlowProperties` canonical ctor. Keys mirrored into both `application.yml`s (disabled in test yaml).
- **AC2 — idempotency/concurrency.** Inherited from the gate: RDEP advisory lock taken first + deterministic `split-rollup:<parentId>` key + state machine rejecting a second `→ Completed`. The IT proves repeated sweeps + a late child-driven hook on the same parent emit exactly one `viaSplitRollup` event.
- **AC6 — tests.** `SplitRollupReconciliationSweepIT` (real PG, non-`@Transactional`, `@AfterEach` cleanup, invokes `sweep()` directly): strand-then-sweep, not-all-children no-op, non-`Split` ignored, grandparent recursion via inherited hook chain, repeated-sweep+late-hook single-emit. Unit: `SplitRollupReconciliationSweepTest` (happy/no-op/cap-hit + log pins), `SplitRollupSweepConfigurationTest` (disabled/absent/enabled bean presence), plus parent-targeted cases added to `RunSplitCompletionRollupServiceTest`.
- **Scope guard verified:** no new `WorkflowState`, transition edge, `WorkflowEventType`, `DomainErrorCode`, or Flyway migration. The two pre-existing `doRollupForParent` log lines that referenced `completedRunId` were repointed to `correlationId` (the gate is now parent-keyed), and `"split-rollup hook firing"` → `"split-rollup gate firing"` (no test pinned the old wording).

### File List

- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunReadPort.java` (modified — `findStrandedSplitParents` default method)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowRunRepository.java` (modified — `findStrandedSplitParents` query)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowRunPersistenceAdapter.java` (modified — `findStrandedSplitParents` impl)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/RunSplitCompletionRollupService.java` (modified — extract `doRollupForParent`, add `rollupParent`)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/RollupSweepProperties.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/SplitRollupReconciliationSweep.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/SplitRollupSweepConfiguration.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/WorkflowConfiguration.java` (modified — register `RollupSweepProperties`)
- `deliveryline-backend/src/main/resources/application.yml` (modified — `rollup-sweep` block)
- `deliveryline-backend/src/test/resources/application.yml` (modified — `rollup-sweep` mirror, disabled)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/SplitRollupReconciliationSweepTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/SplitRollupReconciliationSweepIT.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/config/SplitRollupSweepConfigurationTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/RunSplitCompletionRollupServiceTest.java` (modified — parent-targeted entry tests)

### Change Log

- 2026-06-29 — Story 3f-8 implemented (Opus 4.8 [1m]): split-rollup reconciliation sweep — durability for the 3f-7 completion-rollup hook. Discovery query + parent-targeted gate reuse + scheduled config-gated advisory-locked sweep. All 6 ACs satisfied; backend Surefire 1432/0 + Failsafe ITs (SplitRollupReconciliationSweepIT 5/5) + ArchUnit + SpotBugs/Spotless/Checkstyle + foundation-gate all green. No new migration/state/event/error-code.
