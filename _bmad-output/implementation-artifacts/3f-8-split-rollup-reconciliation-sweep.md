# Story 3f.8: Split-Rollup Reconciliation Sweep (durability for the completion-rollup hook)

Status: backlog

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
`REQUIRES_NEW`, RDEP advisory lock), 3f-2 (`SPLIT` state + `SPLIT → COMPLETED` edge), 3f-3 (advisory
lock + dependency release).

## Acceptance Criteria

1. **Given** a scheduled reconciliation sweep, **Then** on a configurable interval it scans for
   parent runs in `SPLIT` **all of whose direct children are `COMPLETED`** and, for each, invokes the
   existing idempotent `rollupParentOf(childId, null)` (or an equivalent parent-targeted entry) so the
   parent rolls up `SPLIT → COMPLETED`. The sweep reuses the 3f-7 rollup logic verbatim (RDEP advisory
   lock, `REQUIRES_NEW`, swallow+log) — **no new transition path** and **no duplicate rollup logic**.
2. **Given** idempotency, **Then** the sweep is a no-op for any parent that is not in `SPLIT` or whose
   children are not all `COMPLETED`; running it repeatedly (or concurrently with a live `afterCommit`
   rollup of the same parent) never double-transitions, never double-emits `WORKFLOW_STATE_CHANGED`
   (`viaSplitRollup=true`), and never strands a run — guaranteed by the existing advisory lock + the
   `split-rollup:<parentId>` rollup key + the state-machine rejecting re-completion.
3. **Given** recursion, **Then** a parent rolled up by the sweep re-fires the standard rollup +
   dependency-release hooks (the 3f-7 hook chain), so a grandparent stalled by the same transient
   failure also recovers and cross-split dependents are released — no special-casing in the sweep.
4. **Given** configuration + safety, **Then** the sweep is gated behind a config flag
   (`deliveryline.complex-ticket-flow.rollup-sweep.enabled`, default decided in dev) with a configurable
   fixed-delay/interval; it is bounded (batch limit per tick, logged when capped — no silent
   truncation) and takes the RDEP advisory lock so it never races the live hook path.
5. **Given** observability, **Then** each swept recovery logs at `WARN`/`INFO` with the parent run id,
   child-completion counts, and a marker distinguishing a sweep-driven rollup from a hook-driven one
   (so a recurring sweep recovery is a visible signal that the primary hook path is failing).
6. **Given** tests, **Then** coverage asserts: a stranded parent (children all `COMPLETED`, parent
   still `SPLIT`) is rolled up by one sweep tick (IT, real PG, non-`@Transactional`); the sweep is a
   no-op for a not-all-children-complete parent and for a non-`SPLIT` parent; recursion recovers a
   grandparent; concurrent sweep + hook do not double-transition; the disabled flag suppresses the
   sweep; `application.*` ≥ 80% coverage.

## Tasks / Subtasks

- [ ] **Task 1 — Reconciliation read port + query**
  - [ ] Add a read-port method to find `SPLIT` parents whose direct children are all `COMPLETED`
        (bounded by a batch limit). Place the query in infrastructure behind an SPI port
        (`application` may not import adapters).
- [ ] **Task 2 — Scheduled sweep bean**
  - [ ] Add a `@Scheduled` (fixed-delay) reconciler in `application.workflow` (or an adapter scheduler
        delegating to an application service) that, per found parent, invokes the existing
        `RunSplitCompletionRollupService` rollup entry. Reuse — do not re-implement — the lock +
        `REQUIRES_NEW` + swallow+log discipline.
  - [ ] Gate with `@ConditionalOnProperty` / config flag; add interval + batch-limit config to
        `ComplexTicketFlowProperties` (or a nested record) and to both `application.yml`s.
- [ ] **Task 3 — Observability**
  - [ ] Distinct log marker for sweep-driven vs hook-driven rollup; log batch caps.
- [ ] **Task 4 — Tests**
  - [ ] Strand-then-sweep IT (real PG, non-`@Transactional`); no-op cases; recursion; concurrency;
        disabled-flag; coverage.

## Dev Notes

- **Reuse, do not fork.** The sweep must call the same `rollupParentOf` path 3f-7 ships. The only new
  code is *discovery* (which stalled parents to poke) + *scheduling* + *config*. The transition,
  event, recursion, and dependency-release are all inherited unchanged.
- **Why a sweep over a manual re-trigger.** The failure mode is a *transient* infrastructure error, so
  self-healing on the next tick is the correct recovery — it needs no operator to notice. A manual
  operator re-trigger endpoint was considered (code review 3f-7, Decision 2) and rejected in favor of
  the automatic sweep.
- **Concurrency.** The RDEP advisory lock + the deterministic `split-rollup:<parentId>` key + the
  state machine rejecting a second `→ COMPLETED` make the sweep safe to run alongside the live hook.
- **Scope guard.** No new `WorkflowState`, no new transition edge, no Flyway migration (pure
  orchestration + scheduling over shipped substrate), unless the read query needs an index.

## References

- Code review of story 3f-7 (2026-06-29), Decision 2 — `_bmad-output/implementation-artifacts/3f-7-recursive-split-and-completion-rollup.md` → Review Findings.
- `RunSplitCompletionRollupService` (3f-7), `RunDependencyReleaseService` (3f-3).
