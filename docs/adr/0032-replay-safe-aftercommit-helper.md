# ADR 0032 — Shared Replay-Safe afterCommit Side-Effect Helper (B1)

**Status:** Proposed (2026-07-04) — to be confirmed on merge of story 3h-0
**Driver:** Epic 3f retrospective action **A1** ("B1 shared replay-safe afterCommit"), reassigned from "Epic 4" to "pre-3h" by the 2026-07-04 sprint-change proposal and delivered by story `3h-0`. Three shipped hook sites independently re-implement the same async side-effect machinery, and that machinery has already been mis-derived **twice**, each time silently corrupting or dropping data. Epic 3h's three bounded fix-loops (`3h-1` build, `3h-2` lint, `3h-5` CI) would each re-derive it again unless one tested helper exists first.

## Context

An `afterCommit` side effect that does JPA work is deceptively hard to get right, and the same subtle machinery is duplicated across three sites in `org.dradgo.application.workflow`:

| Site | File | Layers used |
|---|---|---|
| 3f-3 dependency release | `RunDependencyReleaseService` | afterCommit hook + `REQUIRES_NEW` + advisory-lock-first + per-item swallow |
| 3f-7 split-completion rollup | `RunSplitCompletionRollupService` | afterCommit hook + `REQUIRES_NEW` + advisory-lock-first + swallow |
| 3f-8 rollup sweep | `SplitRollupReconciliationSweepService` | re-invokes 3f-7's `REQUIRES_NEW` path (Layer B only, no hook) |

The `afterCommit` registration boilerplate is duplicated 3× in `WorkflowTransitionService`; the `REQUIRES_NEW` `TransactionTemplate` construction is duplicated verbatim in the two 3f services. There was **no shared base class, no shared `TransactionTemplate` factory, and no shared afterCommit helper** — each rolled its own.

Two mis-derivations of this exact machinery have already reached production:

1. **The 3f post-commit `REQUIRES_NEW` trap.** During `afterCommit` the original transaction has committed but `TransactionSynchronizationManager` still reports it active — a plain `@Transactional` (`REQUIRED`) side effect *joins* the dying transaction and a `FOR UPDATE` read throws `InvalidDataAccessApiUsageException`, which the best-effort swallow then hides (a silent no-op). The fix is to run the side effect in a fresh `PROPAGATION_REQUIRES_NEW` transaction.
2. **The 3g stale-entity token clobber** (a B1-family transaction-boundary bug on a "safe" additive epic). A `REQUIRES_NEW` metadata side-write committed real token counts, then a terminal `running → completed` full-row `UPDATE` from a **stale managed entity** (loaded before the side-write) wrote those columns back to `NULL`. 0 of 99 rows ever kept tokens. The fix is `@DynamicUpdate` (narrow every `UPDATE` to dirty columns) so concurrent per-column `REQUIRES_NEW` writes coexist. This is the exact transaction-boundary / stale-entity / replay defect class B1 was named to prevent.

## Decision

**1. One shared helper — `@Component AfterCommitSideEffectRunner` (in `application.workflow`) — encapsulates the two-layer machinery, and new async hooks MUST consume it rather than re-deriving it.**

- **Layer A — replay-safe afterCommit registration** (`runAfterCommit(label, contextId, sideEffect)`): guards on `TransactionSynchronizationManager.isSynchronizationActive()` (WARN + skip when inactive), registers a `TransactionSynchronization` whose `afterCommit` logs an INFO "…fired… (post-commit)" line and runs the side effect under a swallow-and-WARN guard that can NEVER roll the already-committed transition back.
- **Layer B — `REQUIRES_NEW` side-effect execution** (`runInNewTransaction(label, contextId, work)`): runs `work` in a fresh `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW` under the same swallow-and-WARN guard. Any tx-scoped step the caller needs (e.g. an advisory lock, a `FOR UPDATE` read) goes as the FIRST statement of `work` — the helper stays domain-agnostic and never references a lock or a port.

Both layers are individually callable: the sweep uses Layer B alone; the hooks compose A over B. The helper is a **leaf bean** (only `PlatformTransactionManager`), so it injects directly with no DI cycle. It is annotated `@Component` (not `@Service`) on purpose — it is infrastructure plumbing, not a domain service, so the `APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES` rule (which binds only `@Service`) does not force a `*Service`/`*Orchestrator` suffix onto a `*Runner` plumbing name.

**2. The helper never mutates workflow state.** It runs a `Runnable`; it does not call `WorkflowTransitionService.transition(...)`. The lazy-collaborator null-guard (`ObjectProvider.getIfAvailable()` → WARN + skip) and the state mutation both stay in the **caller's** callback lambda (whose bytecode belongs to the consumer class — a permitted caller), keeping the `only_workflow_transition_service_may_mutate_workflow_state` ArchUnit boundary intact.

**3. The no-clobber save rule (documented here, applied by each entity owner).** *Any out-of-band `REQUIRES_NEW` metadata write onto a row that a full-row `UPDATE` (from a possibly-stale managed entity) also touches requires `@DynamicUpdate` (narrow the `UPDATE` to dirty columns) or an explicit re-read-before-save on that entity.* Otherwise the stale full-row save clobbers the freshly-committed columns back. The concrete `@DynamicUpdate` on `RunnerExecutionEntity` and its regression IT (`RunnerExecutionTokenUsagePersistenceIT#terminalTransitionDoesNotClobberTokenUsage`, the production exemplar of the full stale-entity shape) are **owned by story 3g-5**; `3h-0` extracts and documents the *rule*, and proves the same stale ambient entity / out-of-band helper write / terminal save shape with `AfterCommitSideEffectRunnerIT#outOfBandRequiresNewWriteSurvivesTheSubsequentStaleEntityTerminalSave`.

**4. Reference consumer retrofitted.** The 3f-7 split-rollup is retrofitted onto the helper — the afterCommit registration in `WorkflowTransitionService#registerSplitCompletionRollupHookIfApplicable` (Layer A) and the `REQUIRES_NEW` path in `RunSplitCompletionRollupService#rollupParentOf` (Layer B) — behavior-preserving (same `split-rollup:<parentId>` idempotency key, same RDEP advisory lock taken first, same swallow semantics; log wording moved from `completedRunId=`/`workflowRunId=` to the helper's uniform `contextId=`). The 3f-3 dependency-release hook, the 3.16 completion-sync hook, and the 3f-8 sweep-driven `rollupParent` entry are left on their inline registration for now (optional/forward retrofit — the sweep keeps its distinct `"(sweep)"` swallow signal).

**5. Consume-note for the Epic 3h fix-loop stories.** The following note MUST be folded into `3h-1` / `3h-2` / `3h-5` when those stories are drafted (they are all `backlog` today, so it is recorded here rather than in non-existent story files — no AC change to those stories):

> **The bounded fix-loop (build / lint / CI) MUST consume the 3h-0 shared replay-safe afterCommit helper (`AfterCommitSideEffectRunner`) — do NOT re-derive `REQUIRES_NEW` + advisory-lock + swallow/log + idempotent re-invoke inline.**

## Alternatives Considered

### Alt 1 — A shared abstract base class the hook services extend
**Rejected.** Inheritance couples the three services to a base's lifecycle and makes the "Layer B alone" sweep case (which is not a hook) awkward. Composition via a leaf `@Component` with two small methods keeps each consumer's control flow explicit and lets the sweep call Layer B directly.

### Alt 2 — Bake the RDEP advisory lock into the helper
**Rejected.** It would make the helper domain-aware (referencing `RunDependencyPort`) and useless to any future hook that needs a *different* tx-scoped step. The lock stays as the caller's first `work` statement; the helper stays a generic `REQUIRES_NEW` + swallow runner.

### Alt 3 — Let the helper own the `@DynamicUpdate` / no-clobber enforcement
**Rejected / impossible.** `@DynamicUpdate` is an entity-mapping concern owned by each entity's persistence adapter, not something a transaction-runner can enforce. The helper documents the rule (this ADR) and proves the `REQUIRES_NEW` isolation it relies on; each entity owner applies `@DynamicUpdate` or re-read-before-save.

### Alt 4 — Defer B1 to Epic 4 (the original 3f-retro placement)
**Rejected by the 2026-07-04 correct-course.** The 3g token clobber was the second consecutive B1-family mis-derivation, and Epic 3h's build/lint/CI fix-loops would be a third, fourth, and fifth. Extracting the helper *before* 3h-1 means those loops consume it instead of re-deriving it.

## Consequences

- Every new async post-commit hook has one tested substrate to consume; the transaction-boundary / stale-entity / replay bug class cannot recur by re-derivation.
- ADR 0030 (governed delivery tail) references this ADR as the substrate for its build/lint/CI bounded fix-loops (decisions 2, 3, 7).
- Follow-up (forward, not in 3h-0): retrofit the 3f-3 dependency-release hook, the 3.16 completion-sync hook, and the 3f-8 sweep entry onto the helper for full consolidation.
