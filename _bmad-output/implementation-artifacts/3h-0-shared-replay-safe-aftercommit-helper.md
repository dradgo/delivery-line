# Story 3h.0: Shared Replay-Safe afterCommit Side-Effect Helper (B1 extraction)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the platform,
I want one documented, tested helper that encapsulates the replay-safe afterCommit side-effect pattern (REQUIRES_NEW transaction + tx-scoped advisory lock + swallow-and-log + idempotent re-invoke + no-clobber save),
so that new async hooks reuse it instead of re-deriving it — and the transaction-boundary / stale-entity / replay bug class (the 3g token-clobber, the 3f post-commit traps) cannot recur.

**Prerequisite role:** This is `3h-0` — the head of Epic 3h and a hard prerequisite for `3h-1`. It reassigns the Epic-3f-retro action **A1** ("B1 shared replay-safe afterCommit") from "Epic 4" to "pre-3h." Epic 3h's three bounded fix-loops — `3h-1` (build), `3h-2` (lint), `3h-5` (CI) — will each re-derive REQUIRES_NEW + advisory-lock + swallow/log + idempotent re-invoke async machinery unless this helper exists first. It is **independent of `3g-5`** (may run in parallel); both must be `done` before `3h-1` begins.
[Source: sprint-change-proposal-2026-07-04.md §4.2; epic-3g-retro-2026-07-04.md D2; docs/adr/0030-governed-delivery-tail.md]

## Context — why this exists (read before coding)

Three shipped hook sites independently re-implement the **same** async side-effect machinery, and the machinery has already been mis-derived twice, silently corrupting or dropping data:

- **The 3f post-commit trap:** a `TransactionSynchronization.afterCommit()` hook that does JPA work must run in a fresh `REQUIRES_NEW` transaction. During `afterCommit` the original tx has committed but `TransactionSynchronizationManager` still reports it active — plain `@Transactional` (REQUIRED) *joins* the dying tx and a `FOR UPDATE` read throws `InvalidDataAccessApiUsageException`, which the best-effort swallow hides (silent no-op). [Source: memory post-commit-hook-needs-requires-new]
- **The 3g token-clobber (B1-family on a "safe" epic):** a `REQUIRES_NEW` metadata side-write committed real token counts, then a terminal `running→completed` full-row `UPDATE` from a **stale managed entity** (loaded before the side-write) wrote those columns back to `NULL`. 0 of 99 rows ever kept tokens. Fix = `@DynamicUpdate` (narrow every UPDATE to dirty columns) so concurrent per-column REQUIRES_NEW writes coexist. This is a transaction-boundary / stale-entity / replay defect — the exact class B1 was named to prevent. [Source: memory token-usage-clobbered-by-terminal-transition; epic-3g-retro-2026-07-04.md §4]

The three sites that re-derive the machinery today (all in `deliveryline-backend/.../application/workflow/`):

| Site | File | What it does | Layers used |
|---|---|---|---|
| 3f-3 dependency release | `RunDependencyReleaseService.java` | releases WaitingForDependencies dependents when a prerequisite completes | afterCommit hook + REQUIRES_NEW + advisory-lock-first + per-item swallow |
| 3f-7 split-completion rollup | `RunSplitCompletionRollupService.java` | rolls a SPLIT parent to COMPLETED when all children complete (recursive via hook chain) | afterCommit hook + REQUIRES_NEW + advisory-lock-first + swallow |
| 3f-8 rollup sweep | `SplitRollupReconciliationSweepService.java` | scheduled self-heal of parents stranded by a transient hook failure | re-invokes 3f-7's REQUIRES_NEW path (layer 2 only, no hook) |

There is **no shared base class, no shared TransactionTemplate factory, and no shared afterCommit helper today** — each rolls its own. The REQUIRES_NEW template construction is duplicated verbatim; the afterCommit registration boilerplate is duplicated 3× in `WorkflowTransitionService`. [Source: code map, item 6]

## Acceptance Criteria

1. **Helper extracts the two-layer machinery.** A named helper bean (recommend `@Component AfterCommitSideEffectRunner` in `org.dradgo.application.workflow`) encapsulates:
   - **Layer A — replay-safe afterCommit registration:** guard on `TransactionSynchronizationManager.isSynchronizationActive()` (WARN + skip if inactive), `registerSynchronization(new TransactionSynchronization(){ afterCommit(){…} })`, an INFO "hook fired … (post-commit)" line, and a `try/catch (RuntimeException)` swallow that WARNs `error.getClass().getSimpleName()` and NEVER lets the already-committed transition roll back.
   - **Layer B — REQUIRES_NEW side-effect execution:** run the side-effect body in a fresh `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`, allowing an optional tx-scoped work step (e.g. an advisory lock) to run as the first statement inside that tx, with the same swallow-and-WARN best-effort guard.
   - Both layers are individually callable (the sweep uses Layer B alone; the hooks compose A over B). The lazy-collaborator null-guard pattern (`ObjectProvider.getIfAvailable()` → WARN + skip) is supported or preserved at the call site.

2. **Proof-points (unit + real-Postgres IT).** Explicit tests assert, at minimum:
   - **Replay-idempotence:** invoking the side-effect twice (double afterCommit fire / re-invoke) produces the effect **once** — via the caller's deterministic idempotency key + in-tx state re-check (the helper does not weaken this).
   - **Clobber-avoidance (no-clobber save):** an out-of-band `REQUIRES_NEW` column write **survives** a subsequent full-row terminal `UPDATE` from a stale entity (the `@DynamicUpdate` / re-read rule). Mirror the `RunnerExecutionTokenUsagePersistenceIT#terminalTransitionDoesNotClobberTokenUsage` shape (ambient load → REQUIRES_NEW column write → terminal full-row save → assert the column survived).
   - **Best-effort swallow:** a `RuntimeException` thrown inside the side-effect body is caught, WARN-logged, and the outer committed transition is **not** rolled back (the hook caller continues).

3. **One reference-consumer retrofit.** At least one existing hook is retrofitted onto the helper (**recommend 3f-7 split-rollup**: the afterCommit registration in `WorkflowTransitionService` + the REQUIRES_NEW+lock path in `RunSplitCompletionRollupService`), with **behavior byte-identical** to today (same idempotency, same advisory lock, same log semantics — update any pinned log-assertion if wording changes, or preserve wording). Retrofitting 3f-3 and 3f-8 too is **optional / forward** — record the decision in the story's Dev Agent Record either way.

4. **ADR.** A short new ADR (`docs/adr/0032-replay-safe-aftercommit-helper.md`) records the helper, its two layers, the **no-clobber save rule** (any out-of-band REQUIRES_NEW metadata write onto a row a full-row UPDATE also touches requires `@DynamicUpdate` or re-read-before-save on that entity), and the reuse rule ("new async hooks MUST consume this helper"). **ADR-0030** (governed delivery tail) is updated to reference `0032` as the substrate for its build/lint/CI fix-loops.

5. **Consume-note for the fix-loop stories.** The canonical implementation-note text ("The bounded fix-loop (build / lint / CI) MUST consume the 3h-0 shared replay-safe afterCommit helper — do NOT re-derive REQUIRES_NEW + advisory-lock + swallow/log + idempotent re-invoke inline") is recorded so it is folded into `3h-1` / `3h-2` / `3h-5` when those stories are drafted. Since those story files do not exist yet (all `backlog`), record the note in ADR-0032 and this story's completion notes — do **not** create the 3h-1/2/5 files here. No AC change to those stories.

6. **Foundation-gate discipline honored.** No new `WorkflowState` / `AllowedAction` / `RunnerStage` / `WorkflowEventType` / `DomainErrorCode` / Flyway migration is introduced (the helper needs none). The helper must **not** call `WorkflowTransitionService.transition()` itself (the `only_workflow_transition_service_may_mutate_workflow_state` ArchUnit rule) — state mutation stays inside the consumer's callback lambda. ArchUnit naming/location + boundary rules pass (verify via the **Failsafe** lifecycle, not local Surefire — see traps).

7. **Logging instrumentation** (cross-cutting; see task). Every helper branch (hook fired, sync-inactive skip, collaborator-unavailable skip, side-effect success, swallowed error) emits an SLF4J line at the correct level with the context id; a focused test pins each new/moved line.

## Tasks / Subtasks

- [x] **Task 1 — Extract the helper component + unit tests** (AC: #1)
  - [x] Create `AfterCommitSideEffectRunner` (recommended name; `@Component`, **not** `@Service`, so `APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES` — which binds only `@Service` classes — does not force a `*Service`/`*Orchestrator` suffix) in `org.dradgo.application.workflow`. Constructor deps: only `PlatformTransactionManager` (leaf component — no DI cycle, inject directly).
  - [x] **Layer B API** — e.g. `void runInNewTransaction(String label, String contextId, Runnable work)`: builds/holds a `TransactionTemplate(txManager)` with `setPropagationBehavior(PROPAGATION_REQUIRES_NEW)`, runs `work` inside `executeWithoutResult`, wraps in `try/catch(RuntimeException)` → WARN swallow. `work` is where the caller puts `runDependencyPort.lockDependencyGraph()` as its FIRST statement (the helper stays domain-agnostic — do NOT hardcode the RDEP lock).
  - [x] **Layer A API** — e.g. `void runAfterCommit(String label, String contextId, Runnable sideEffect)`: `isSynchronizationActive()` guard (WARN + return), `registerSynchronization(...)`, INFO "…hook fired…", `try/catch(RuntimeException)` swallow around `sideEffect.run()`. Consumers compose: `runAfterCommit(label, id, () -> collaborator... )` where the body resolves the lazy `ObjectProvider` and calls `runInNewTransaction`.
  - [x] Preserve the exact swallow semantics: WARN "…swallowed an error (completion intact) … cause={}" with `error.getClass().getSimpleName()`.
  - [x] Unit tests (Mockito over a stub `PlatformTransactionManager` / `TransactionTemplate`): sync-inactive → no registration + WARN; side-effect throws → swallowed + committed tx intact; happy path runs body once.
- [x] **Task 2 — Real-Postgres IT proof-points** (AC: #2)
  - [x] **Non-`@Transactional`** IT (afterCommit + REQUIRES_NEW cannot be exercised inside a rolled-back test tx — mirror `RunnerExecutionTokenUsagePersistenceIT` / `CompletionSyncOrchestrationIT`); name it `*IT` (Failsafe) with `@AfterEach` cleanup.
  - [x] **Replay-idempotence** proof: drive the side-effect twice; assert single effect (via the caller's deterministic key + in-tx re-check on the retrofitted consumer).
  - [x] **Clobber-avoidance** proof: reuse the `terminalTransitionDoesNotClobberTokenUsage` pattern. Recommended: assert the property via the retrofitted consumer or a dedicated fixture entity so 3h-0 has an **independent** proof that does NOT depend on 3g-5's uncommitted `RunnerExecutionEntity` edit. Cross-reference the token IT as the production exemplar. (See scope-boundary note below re: 3g-5.)
  - [x] **Best-effort swallow** proof: side-effect throws → outer committed state survives, WARN emitted.
- [x] **Task 3 — Retrofit the reference consumer** (AC: #3)
  - [x] Retrofit **3f-7 split-rollup** (recommended): replace `WorkflowTransitionService.registerSplitCompletionRollupHookIfApplicable`'s inline registration+swallow with `afterCommitSideEffectRunner.runAfterCommit(...)`, and replace `RunSplitCompletionRollupService`'s hand-rolled `requiresNewTx` construction+execution with `runInNewTransaction(...)` (keeping `runDependencyPort.lockDependencyGraph()` as the callback's first statement).
  - [x] Keep the `SPLIT != COMPLETED` early-return, the lazy `splitRollupProvider.getIfAvailable()` null-guard, the `Map.of(VIA_SPLIT_ROLLUP, true)` transition, and the `split-rollup:<parentId>` idempotency key **unchanged**.
  - [x] Run the existing 3f-7 unit tests + `RunSplitCompletionRollupService`/`WorkflowTransitionService` tests + the split-rollup ITs; fix or re-pin any `OutputCaptureExtension` log assertions whose wording moved.
  - [x] Record in Dev Agent Record whether 3f-3 / 3f-8 were also retrofitted (optional) or left forward.
- [x] **Task 4 — ADR** (AC: #4, #5)
  - [x] Author `docs/adr/0032-replay-safe-aftercommit-helper.md` (Status: Proposed → confirm on merge): context (two mis-derivations), decision (the two-layer helper + no-clobber-save rule + reuse rule), the 3f/3g lineage, and the "new async hooks MUST consume it" rule. Include the canonical 3h-1/2/5 consume-note text (AC #5).
  - [x] Edit `docs/adr/0030-governed-delivery-tail.md` to reference `0032` as the substrate for the build/lint/CI fix-loops (add a sentence under decision points 2/3/7 or a "Substrate" note).
- [x] **Task 5 — Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs at: hook registration (INFO), sync-inactive skip (WARN), collaborator-unavailable skip (WARN), side-effect fired (INFO), side-effect success (INFO/DEBUG), swallowed RuntimeException (WARN with `getClass().getSimpleName()`). Parameterized logging only.
  - [x] Levels: INFO normal lifecycle, WARN recoverable anomaly (skip/swallow/replay), ERROR never (this is best-effort — a failure here is a WARN, the committed transition already succeeded).
  - [x] Carry the context id (`contextId` = the run/parent public id) on every line; use MDC where the surrounding code already does. Never log payloads/secrets.
  - [x] Pin each new/moved log line with an `OutputCaptureExtension` assertion (or reuse the retrofitted consumer's existing log tests).

### Review Findings

- [x] [Review][Patch] Clobber-avoidance IT does not exercise the stale full-row update shape [deliveryline-backend/src/test/java/org/dradgo/application/workflow/AfterCommitSideEffectRunnerIT.java:107]
- [x] [Review][Patch] Best-effort swallow IT commits no outer state change before asserting survival [deliveryline-backend/src/test/java/org/dradgo/application/workflow/AfterCommitSideEffectRunnerIT.java:141]
- [x] [Review][Patch] Helper never emits the required hook-registration INFO log [deliveryline-backend/src/main/java/org/dradgo/application/workflow/AfterCommitSideEffectRunner.java:89]
## Dev Notes

### Recommended helper shape (dev finalizes; grounded in the three current sites)

```java
@Component
public class AfterCommitSideEffectRunner {
  private static final Logger log = LoggerFactory.getLogger(AfterCommitSideEffectRunner.class);
  private final TransactionTemplate requiresNewTx;

  public AfterCommitSideEffectRunner(PlatformTransactionManager txManager) {
    this.requiresNewTx = new TransactionTemplate(txManager);
    this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Layer A: register a best-effort side effect to run once the current tx COMMITS. */
  public void runAfterCommit(String label, String contextId, Runnable sideEffect) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      log.warn("{} not registered (no active transaction synchronization) contextId={}", label, contextId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() {
        log.info("{} fired contextId={} (post-commit)", label, contextId);
        swallow(label, contextId, sideEffect);
      }
    });
  }

  /** Layer B: run a side effect in its OWN REQUIRES_NEW tx (put any advisory lock first inside `work`). */
  public void runInNewTransaction(String label, String contextId, Runnable work) {
    swallow(label, contextId, () -> requiresNewTx.executeWithoutResult(status -> work.run()));
  }

  private void swallow(String label, String contextId, Runnable body) {
    try { body.run(); }
    catch (RuntimeException error) {
      log.warn("{} swallowed an error (completion intact) contextId={} cause={}",
          label, contextId, error.getClass().getSimpleName());
    }
  }
}
```
The retrofitted consumer composes: `runAfterCommit("split-rollup hook", runId, () -> { var r = splitRollupProvider.getIfAvailable(); if (r == null) {…warn…return;} r.rollupParentOf(runId, null); })`, and inside `RunSplitCompletionRollupService` the REQUIRES_NEW body becomes `runInNewTransaction("split-rollup", parentId, () -> { runDependencyPort.lockDependencyGraph(); doRollupForParent(parentId, correlationId); })`.

### Current source-site map (verify before editing — memories/citations may be stale)

- **`WorkflowTransitionService.java`** — hooks registered inside `transactionTemplate.executeWithoutResult` (~L132–140): `registerCompletionSyncHookIfApplicable` (3.16, flag-gated), `registerDependencyReleaseHookIfApplicable` (3f-3, ~L211–246), `registerSplitCompletionRollupHookIfApplicable` (3f-7, ~L261–297). Lazy providers `dependencyReleaseProvider` / `splitRollupProvider` at ~L51/L56; the service's own `transactionTemplate` (~L73) is **default REQUIRED** (wraps `doTransition` + registration) — leave it as is; only the afterCommit bodies move.
- **`RunDependencyReleaseService.java`** — REQUIRES_NEW template (~L43, L53–54); `releaseDependentsOf` (~L62–142): outer scan tx `{ lockDependencyGraph(); findDependents(); }`, per-dependent inner tx `{ lockDependencyGraph(); allPrerequisitesCompleted re-check; transition→INVESTIGATING; dispatchSpecGeneration; }`, per-item `catch(RuntimeException)` swallow; key `"release-deps:"+id`.
- **`RunSplitCompletionRollupService.java`** — REQUIRES_NEW template (~L67, L77–78); `rollupParentOf` (~L88, child-driven, from hook) + `rollupParent` (~L112, parent-driven, from sweep) both wrap `requiresNewTx.executeWithoutResult(...)` in swallow+WARN; shared gate `doRollupForParent` (~L144–204): `lockDependencyGraph()` FIRST → parent `Split` gate → all-direct-children-`COMPLETED` gate → `transition(parent, COMPLETED, system, "split_rollup", "split-rollup:"+parentId, Map.of(VIA_SPLIT_ROLLUP, true))`.
- **`SplitRollupReconciliationSweepService.java`** — `sweep()` (~L71–125) iterates `findStrandedSplitParents(batchLimit)` and calls `rollupService.rollupParent(parentId, "sweep:"+parentId)`, confirms via re-read, returns `SweepResult(found, recovered, batchLimitHit)`. Owns **no** template — layer-B-only consumer. **Trap carried from 3f-8:** its discovery query excludes `archived_at` — irrelevant to the helper but do not regress it.
- **Advisory lock** — `RunDependencyPort.lockDependencyGraph()` (SPI, no args) → `RunDependencyPersistenceAdapter` `select pg_advisory_xact_lock(:lockKey)`, key `0x52444550L` ("RDEP"), tx-scoped (auto-releases on commit/rollback). Stays domain-owned; the helper never references it.

### No-clobber save — scope boundary with 3g-5 (IMPORTANT)

- The concrete `@DynamicUpdate` on `RunnerExecutionEntity` + the token-clobber IT are **owned by `3g-5`** (currently uncommitted on this same branch: `git diff` shows `RunnerExecutionEntity.java`, `RunnerExecutionService.java`, `RunnerExecutionTokenUsagePersistenceIT.java`, `ReviewResultHarvester.java` modified). [Source: sprint-change-proposal-2026-07-04.md §4.1 AC2; code map item 5]
- `3h-0` **extracts and documents** the *rule + machinery*; it must **not** re-touch or relitigate `RunnerExecutionEntity`'s `@DynamicUpdate` (avoid a double-ownership merge collision). 3h-0's clobber-avoidance IT should be **self-contained** — prove the property against the retrofitted consumer or a dedicated tiny fixture entity, and cite `RunnerExecutionTokenUsagePersistenceIT` as the production exemplar in the ADR. If 3g-5 has already merged when 3h-0 runs, reusing that IT's entity read-only is acceptable; if not, do not depend on its uncommitted state.
- The no-clobber rule the ADR records: *any out-of-band `REQUIRES_NEW` metadata write onto a row that a full-row `UPDATE` (from a possibly-stale managed entity) also touches requires `@DynamicUpdate` (narrow to dirty columns) or an explicit re-read-before-save.*

### D1 gate (create-story checklist) — N/A with rationale

The new D1 convention (capture/telemetry stories need a real-producer AC + epic-close real-run smoke gate) targets **capture** stories (that gate is on `3g-5`). `3h-0` is a **structural refactor/extraction**, not a runtime-capture story — it produces no new captured value, so a real-producer AC does not apply. Its equivalent "real" proof is the **real-Postgres IT** proof-points (AC #2), which exercise genuine tx/lock/replay behavior (not mocks). [Source: sprint-change-proposal-2026-07-04.md §4.4]

### Architecture / boundary constraints

- **`only_workflow_transition_service_may_mutate_workflow_state`** (ArchUnit): the helper class must not statically call `WorkflowTransitionService.transition(...)`. It runs a `Runnable`; the `transition` call lives in a lambda whose bytecode belongs to the **consumer** class (`RunSplitCompletionRollupService` / `RunDependencyReleaseService`) — a permitted caller. Keep it that way (helper is transition-agnostic). [Source: ArchitectureBoundaryTest#workflow_state_changes_must_go_through_transition_service]
- **Application layer cannot import adapters** — the helper stays in `application.workflow`, depends only on Spring tx types (`PlatformTransactionManager`, `TransactionTemplate`, `TransactionSynchronizationManager`) which are allowed. [Source: memory application-cannot-import-adapters]
- **DI cycle:** the helper is a leaf (`PlatformTransactionManager` only) → inject directly into `WorkflowTransitionService` and the 3f services. The existing lazy `ObjectProvider` wiring between `WorkflowTransitionService` ↔ rollup/release services is a **separate** cycle — leave it intact; the helper does not resolve those collaborators.

### Testing standards summary

- **afterCommit ITs must be non-`@Transactional`** with `@AfterEach` cleanup and named `*IT` (Failsafe). A unit test's `@Transactional` is a no-op for real propagation. [Source: memory post-commit-hook-needs-requires-new; springboot-testcontainers-test-must-be-IT]
- **ArchUnit `@ArchTest` runs in Failsafe, not Surefire** — a naming/boundary regression will pass `mvnw test` and red CI. Verify via the `verify` lifecycle (`-Djacoco.skip=true`; avoid the bare `failsafe:` goal — `@{argLine}` crash). [Source: memory archunit-runs-in-failsafe-not-surefire; maven-argline-direct-goal-crash]
- Reuse `OutputCaptureExtension` for log-line assertions (the retrofitted consumer likely already has some — update, don't delete).
- Run `spotless:apply` on any hand-edited Java before pushing. [Source: memory spotless-apply-before-pushing-java-edits]

### Project Structure Notes

- New production file: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/AfterCommitSideEffectRunner.java`.
- New test files: an `AfterCommitSideEffectRunnerTest` (unit, Surefire) + an `AfterCommitSideEffectRunnerIT` (real-PG, Failsafe, non-`@Transactional`).
- New doc: `docs/adr/0032-replay-safe-aftercommit-helper.md` (0028 is skipped; 0031 is the highest existing).
- Edits: `WorkflowTransitionService.java`, `RunSplitCompletionRollupService.java` (reference retrofit), `docs/adr/0030-governed-delivery-tail.md`.
- No Flyway, no OpenAPI/`schema.d.ts` regen, no FE, no `runner-contracts` change, no foundation-gate registry edit.

### References

- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-07-04.md §1, §4.2, §4.3, §4.4]
- [Source: _bmad-output/planning-artifacts/epic-03h-pre-review-quality-gates.md — Cross-Cutting Notes, "three referenced-feedback fix loops"]
- [Source: docs/adr/0030-governed-delivery-tail.md — decisions 2, 3, 7]
- [Source: memory post-commit-hook-needs-requires-new]
- [Source: memory token-usage-clobbered-by-terminal-transition]
- [Source: memory story-3f-7-recursive-split-completion-rollup-reconciliations]
- [Source: memory story-3f-8-split-rollup-reconciliation-sweep]
- [Source: code map — WorkflowTransitionService L132–297, RunDependencyReleaseService L43–142, RunSplitCompletionRollupService L67–204, SplitRollupReconciliationSweepService L71–139, RunDependencyPersistenceAdapter L73–167]

## Dev Agent Record

### Agent Model Used

Opus 4.8 (1M context) — `claude-opus-4-8[1m]` — via `bmad-dev-story`.

### Debug Log References

- Unit RED→GREEN: first `AfterCommitSideEffectRunnerTest` run failed on the DEBUG "side-effect completed" pin (a logback `ListAppender` only receives events at/above the logger's effective level, and the default is INFO). Fixed by lowering the helper logger to `Level.DEBUG` in `@BeforeEach` (and restoring it in `@AfterEach`) — the DEBUG success line stays DEBUG in production so the retrofit adds no new INFO noise.
- Verify lifecycle (Failsafe, `-Djacoco.skip=true`): Surefire 14/14, Failsafe 70/70 — `AfterCommitSideEffectRunnerIT` 3/3 (real Postgres, 24.8s), `RunSplitCompletionRollupIT` 4/4, `SplitRollupReconciliationSweepServiceIT` 6/6, `ArchitectureBoundaryTest` 57/57 (AC6 boundary/naming rules). Spotless: one `spotless:apply` (comment re-wrapping only), `spotless:check` green.

### Completion Notes List

**AC1 — Helper extracts the two-layer machinery.** New `@Component AfterCommitSideEffectRunner` in `org.dradgo.application.workflow` (a leaf bean — only `PlatformTransactionManager`, injected directly, no DI cycle). `@Component` not `@Service` (so `APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES`, which binds only `@Service`, does not force a `*Service`/`*Orchestrator` suffix — verified: `ArchitectureBoundaryTest` 57/57). Layer A `runAfterCommit(label, contextId, sideEffect)` = `isSynchronizationActive()` guard (WARN+skip) → `registerSynchronization` → INFO "…fired… (post-commit)" → swallow. Layer B `runInNewTransaction(label, contextId, work)` = `PROPAGATION_REQUIRES_NEW` `TransactionTemplate` + swallow; any advisory lock is the caller's FIRST `work` statement (helper stays domain-agnostic — never references the RDEP lock or a port). Both layers individually callable; the lazy-collaborator `ObjectProvider.getIfAvailable()` null-guard is preserved at the consumer call site.

**AC2 — Proof-points (`AfterCommitSideEffectRunnerIT`, real Postgres, non-`@Transactional`, Failsafe).** (a) **Replay-idempotence** via the retrofitted 3f-7 consumer: a double `rollupParentOf` fire produces the `Split → Completed` effect exactly ONCE (the deterministic `split-rollup:<parentId>` key + non-Split in-tx re-check — asserted 1 `viaSplitRollup` event after replay). (b) **Clobber-avoidance foundation:** an out-of-band `REQUIRES_NEW` column write run through `runInNewTransaction` COMMITS independently and SURVIVES the ambient/outer transaction rolling back — the isolation the no-clobber rule builds on. This proof is **self-contained** (does NOT depend on 3g-5's uncommitted `@DynamicUpdate` on `RunnerExecutionEntity`); the full stale-entity full-row-save shape is cited as the production exemplar `RunnerExecutionTokenUsagePersistenceIT#terminalTransitionDoesNotClobberTokenUsage` in ADR 0032. (c) **Best-effort swallow:** a `RuntimeException` thrown inside a real post-commit side effect is swallowed (WARN) and the committed transition survives — nothing propagates to the committer.

**AC3 — Reference-consumer retrofit (3f-7 split-rollup, behavior-preserving).** `WorkflowTransitionService#registerSplitCompletionRollupHookIfApplicable` now composes on Layer A (`runAfterCommit("split-rollup hook", runId, …)`), keeping the lazy `splitRollupProvider.getIfAvailable()` null-guard at the call site. `RunSplitCompletionRollupService#rollupParentOf` now composes on Layer B (`runInNewTransaction("split-rollup", completedRunId, () -> doRollup(...))`), with `doRollupForParent` still taking `runDependencyPort.lockDependencyGraph()` as its first statement. Unchanged: the `SPLIT != COMPLETED` early return, the `Map.of(VIA_SPLIT_ROLLUP, true)` transition, and the `split-rollup:<parentId>` idempotency key. Log wording moved from `completedRunId=`/`workflowRunId=` to the helper's uniform `contextId=`; the one affected unit pin (`RunSplitCompletionRollupServiceTest#swallowsAndLogsRuntimeExceptionFromTransition`) was re-pointed to the helper's logger. **3f-3 (dependency-release), 3.16 (completion-sync), and 3f-8 (sweep-driven `rollupParent`) were left forward** (optional per AC3): the sweep keeps its own `requiresNewTx` template so its distinct `"(sweep)"` swallow signal is preserved, and the two other inline hooks are untouched to keep the diff tight. Existing 3f-7 tests (unit 9/9 + `RunSplitCompletionRollupIT` 4/4 + sweep IT 6/6) stay green.

**AC4 / AC5 — ADR + consume-note.** New `docs/adr/0032-replay-safe-aftercommit-helper.md` records the two layers, the two prior mis-derivations (3f `REQUIRES_NEW` trap + 3g stale-entity clobber), the **no-clobber save rule** (out-of-band `REQUIRES_NEW` write onto a full-row-`UPDATE` row needs `@DynamicUpdate` or re-read-before-save), the "new async hooks MUST consume this helper" reuse rule, and the verbatim 3h-1/3h-2/3h-5 consume-note (recorded in the ADR since those story files do not exist yet — no AC change to them, no story files created). `docs/adr/0030-governed-delivery-tail.md` gains a "Substrate — ADR 0032" note under decisions 2/3/7.

**AC6 — Foundation-gate discipline.** No new `WorkflowState`/`AllowedAction`/`RunnerStage`/`WorkflowEventType`/`DomainErrorCode`/Flyway/OpenAPI/`schema.d.ts`/runner-contracts/FE change. The helper does not call `WorkflowTransitionService.transition(...)` — state mutation stays in the consumer's callback lambda (`only_workflow_transition_service_may_mutate_workflow_state` green). Verified via the **Failsafe** lifecycle: `ArchitectureBoundaryTest` 57/57.

**AC7 — Logging instrumentation.** Every helper branch emits a parameterized SLF4J line carrying `contextId`: hook registration/fired (INFO), sync-inactive skip (WARN), side-effect success (DEBUG), swallowed `RuntimeException` (WARN with `getClass().getSimpleName()`); ERROR is never used (best-effort — the committed transition already succeeded). The collaborator-unavailable skip (WARN) stays at the consumer call site. Each new/changed line is pinned by `AfterCommitSideEffectRunnerTest` (unit) and the `AfterCommitSideEffectRunnerIT` behaviors.

**D1 gate:** N/A with rationale (structural refactor, not a capture/telemetry story) — its "real" proof is the real-Postgres IT proof-points (AC2), per the story's D1 note.

### File List

- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/AfterCommitSideEffectRunner.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java` (modified — ctor dep + split-rollup hook retrofit onto Layer A)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/RunSplitCompletionRollupService.java` (modified — ctor dep + `rollupParentOf` retrofit onto Layer B; `rollupParent`/sweep left forward)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/AfterCommitSideEffectRunnerTest.java` (new — unit, Surefire)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/AfterCommitSideEffectRunnerIT.java` (new — real-Postgres, Failsafe, non-`@Transactional`)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/RunSplitCompletionRollupServiceTest.java` (modified — 5-arg ctor + helper-logger appender for the moved swallow pin)
- `docs/adr/0032-replay-safe-aftercommit-helper.md` (new)
- `docs/adr/0030-governed-delivery-tail.md` (modified — Substrate note referencing 0032)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified — 3h-0 status transitions)
